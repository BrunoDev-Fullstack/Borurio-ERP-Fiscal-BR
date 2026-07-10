package br.com.borurio.web.service;

import br.com.borurio.app.entity.*;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.*;
import br.com.borurio.fiscal.utils.CpfCnpjValidator;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.dto.OmsFiscalAuthorizationRequest;
import br.com.borurio.web.dto.OmsFiscalAuthorizationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OmsFiscalAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(OmsFiscalAuthorizationService.class);

    private static final Set<String> UF_VALIDAS = Set.of(
            "AC","AL","AP","AM","BA","CE","DF","ES","GO","MA",
            "MT","MS","MG","PA","PB","PR","PE","PI","RJ","RN",
            "RS","RO","RR","SC","SP","SE","TO");

    private final OmsApiKeyMapper              omsApiKeyMapper;
    private final OmsFiscalAuthorizationMapper omsAuthMapper;
    private final OmsCompanyCertificateMapper  omsCertMapper;
    private final EmpresaMapper                empresaMapper;
    private final CertSenhaEncryptor           encryptor;
    private final JwtUtil                      jwtUtil;

    public OmsFiscalAuthorizationService(OmsApiKeyMapper omsApiKeyMapper,
                                          OmsFiscalAuthorizationMapper omsAuthMapper,
                                          OmsCompanyCertificateMapper omsCertMapper,
                                          EmpresaMapper empresaMapper,
                                          CertSenhaEncryptor encryptor,
                                          JwtUtil jwtUtil) {
        this.omsApiKeyMapper = omsApiKeyMapper;
        this.omsAuthMapper   = omsAuthMapper;
        this.omsCertMapper   = omsCertMapper;
        this.empresaMapper   = empresaMapper;
        this.encryptor       = encryptor;
        this.jwtUtil         = jwtUtil;
    }

    @Transactional
    public OmsFiscalAuthorizationResponse autorizar(String rawApiKey,
                                                     OmsFiscalAuthorizationRequest req) {
        // 1. Validar API Key
        if (rawApiKey == null || rawApiKey.isBlank()) {
            log.warn("[OmsAuth] X-Api-Key ausente na requisição");
            throw BusinessException.invalidApiKey();
        }
        OmsApiKey apiKey = omsApiKeyMapper.findAtivaPorHash(sha256Hex(rawApiKey));
        if (apiKey == null) {
            log.warn("[OmsAuth] API Key inválida ou revogada");
            throw BusinessException.invalidApiKey();
        }

        // 2. Decodificar e carregar PKCS12
        byte[] pfxBytes = decodificarBase64(req.getCertBase64());
        KeyStore ks = carregarKeyStore(pfxBytes, req.getCertSenha());

        // 3. Extrair certificado X.509
        X509Certificate x509 = extrairCertificado(ks);

        // 4. Verificar validade
        if (x509.getNotAfter().before(new Date())) {
            throw BusinessException.certificateExpired();
        }

        // 5. Validar CNPJ do certificado vs CNPJ enviado
        String cnpjCert = extrairCnpjDoCertificado(x509);
        if (!cnpjCert.equals(req.getCnpj())) {
            throw BusinessException.cnpjCertificateMismatch(req.getCnpj(), cnpjCert);
        }

        // 6. Fingerprint SHA-256 + datas
        String thumbprint = sha256HexDeCert(x509);
        LocalDateTime notBefore = toLocalDateTime(x509.getNotBefore());
        LocalDateTime notAfter  = toLocalDateTime(x509.getNotAfter());

        // 7. Criptografar PFX e senha
        byte[] pfxEnc   = encryptor.encryptBytes(pfxBytes);
        String senhaEnc = encryptor.encrypt(req.getCertSenha());

        // 8. Localizar ou criar automaticamente a empresa para este CNPJ
        Empresa empresa = localizarOuCriarEmpresa(req.getCnpj(), x509);

        // 9. Localizar slot por cliente OMS (sem empresa_id — token é por cliente OMS)
        OmsFiscalAuthorization auth = omsAuthMapper.buscarPorSlot(
                apiKey.getIntegratorId(), req.getCodigoEmpresaOms());

        if (auth == null) {
            // CASO A — primeira autorização para este cliente OMS
            auth = new OmsFiscalAuthorization();
            auth.setEmpresaId(empresa.getId());
            auth.setIntegratorId(apiKey.getIntegratorId());
            auth.setCodigoOms(req.getCodigoEmpresaOms());
            auth.setJti(UUID.randomUUID().toString());
            auth.setTokenExpiraEm(notAfter);
            // emitidoEm truncado a segundos para ser idêntico ao valor armazenado em DB (CURRENT_TIMESTAMP).
            // Garante que o iat do token gerado aqui seja reproduzível nos casos B/C/D.
            auth.setEmitidoEm(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
            omsAuthMapper.inserir(auth);
            inserirCertificado(auth.getId(), empresa.getId(), req.getCnpj(),
                    thumbprint, pfxEnc, senhaEnc, notBefore, notAfter);
            log.info("[OmsAuth] Nova autorização criada | codigoOms={} | cnpj={}",
                    req.getCodigoEmpresaOms(), req.getCnpj());

        } else {
            // Slot existente — identificar caso B, C ou D
            OmsCompanyCertificate certAtivo = omsCertMapper.buscarAtivoPorAuthIdECnpj(
                    auth.getId(), req.getCnpj());
            boolean mesmoCnpj        = certAtivo != null;
            boolean mesmoThumbprint  = mesmoCnpj && thumbprint.equals(certAtivo.getThumbprint());

            if (mesmoThumbprint) {
                // CASO B — mesmo CNPJ, mesmo certificado → nenhuma alteração
                log.info("[OmsAuth] Mesmo certificado reenviado (renovação de token) | authId={} | cnpj={}",
                        auth.getId(), req.getCnpj());

            } else if (mesmoCnpj) {
                // CASO C — mesmo CNPJ, certificado novo → atualiza cert deste CNPJ; mantém JTI
                omsCertMapper.desativarCertsAtivos(auth.getId(), req.getCnpj(), LocalDateTime.now());
                inserirCertificado(auth.getId(), empresa.getId(), req.getCnpj(),
                        thumbprint, pfxEnc, senhaEnc, notBefore, notAfter);
                // Mantém o JTI existente — apenas atualiza tokenExpiraEm para nova validade do cert
                omsAuthMapper.atualizarToken(auth.getId(), auth.getJti(), notAfter);
                auth.setTokenExpiraEm(notAfter);
                log.info("[OmsAuth] Certificado atualizado (mesmo CNPJ, novo cert) | authId={} | cnpj={}",
                        auth.getId(), req.getCnpj());

            } else {
                // CASO D — CNPJ novo para cliente OMS existente → adiciona cert; mantém token intacto
                inserirCertificado(auth.getId(), empresa.getId(), req.getCnpj(),
                        thumbprint, pfxEnc, senhaEnc, notBefore, notAfter);
                log.info("[OmsAuth] Novo CNPJ adicionado ao cliente OMS | authId={} | codigoOms={} | cnpj={}",
                        auth.getId(), req.getCodigoEmpresaOms(), req.getCnpj());
            }
        }

        // 10. Gerar (ou regenerar) token usando emitidoEm do DB como iat.
        //     O emitidoEm (CURRENT_TIMESTAMP, precisão de segundos) é idêntico para todos os casos
        //     que compartilham o mesmo slot (B/C/D), garantindo token string determinístico.
        String token = jwtUtil.generateOmsToken(
                req.getCodigoEmpresaOms(), auth.getEmpresaId(), auth.getJti(),
                auth.getTokenExpiraEm(), auth.getEmitidoEm());

        log.info("[OmsAuth] Token OMS emitido | codigoOms={} | cnpj={} | expira={}",
                req.getCodigoEmpresaOms(), req.getCnpj(), auth.getTokenExpiraEm());

        // empresaId na resposta é sempre o da empresa âncora (primeiro CNPJ autorizado do cliente OMS)
        return new OmsFiscalAuthorizationResponse(
                token, auth.getEmpresaId(), empresa.getCnpj(), empresa.getRazaoSocial(), auth.getTokenExpiraEm());
    }

    // -------------------------------------------------------------------------
    // Empresa — localizar ou criar automaticamente
    // -------------------------------------------------------------------------

    private Empresa localizarOuCriarEmpresa(String cnpj, X509Certificate x509) {
        Empresa empresa = empresaMapper.buscarPorCnpj(cnpj);
        if (empresa != null) {
            if (Boolean.FALSE.equals(empresa.getAtivo())) {
                throw BusinessException.companyInactive(cnpj);
            }
            return empresa;
        }
        empresa = new Empresa();
        empresa.setCnpj(cnpj);
        empresa.setRazaoSocial(extrairRazaoSocialDoCertificado(x509));
        empresa.setUf(extrairUfDoCertificado(x509));
        empresa.setCrt("1");
        empresa.setSerieNfePadrao("1");
        empresa.setAtivo(true);
        empresa.setControleEstoqueAtivo(true);
        empresaMapper.inserir(empresa);
        log.info("[OmsAuth] Empresa auto-criada | cnpj={} | razaoSocial={}", cnpj, empresa.getRazaoSocial());
        return empresa;
    }

    // -------------------------------------------------------------------------
    // Certificado — inserir
    // -------------------------------------------------------------------------

    private void inserirCertificado(Long authId, Long empresaId, String cnpj,
                                     String thumbprint, byte[] pfxEnc, String senhaEnc,
                                     LocalDateTime notBefore, LocalDateTime notAfter) {
        OmsCompanyCertificate cert = new OmsCompanyCertificate();
        cert.setAuthId(authId);
        cert.setEmpresaId(empresaId);
        cert.setCnpj(cnpj);
        cert.setThumbprint(thumbprint);
        cert.setCertPfxEnc(pfxEnc);
        cert.setCertSenhaEnc(senhaEnc);
        cert.setKeyVersion("v1");
        cert.setNotBefore(notBefore);
        cert.setNotAfter(notAfter);
        cert.setAtivo(true);
        omsCertMapper.inserir(cert);
    }

    // -------------------------------------------------------------------------
    // Extração de dados do certificado X.509 (ICP-Brasil)
    // -------------------------------------------------------------------------

    private String extrairRazaoSocialDoCertificado(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        // ICP-Brasil PJ: CN=RAZÃO SOCIAL DA EMPRESA:CNPJ
        Matcher m = Pattern.compile("(?i)CN=([^,]+)").matcher(dn);
        if (m.find()) {
            String cn = m.group(1).trim();
            int colon = cn.lastIndexOf(':');
            if (colon > 0) cn = cn.substring(0, colon).trim();
            return cn.length() > 60 ? cn.substring(0, 60) : cn;
        }
        return "EMPRESA OMS";
    }

    private String extrairUfDoCertificado(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        Matcher m = Pattern.compile("(?i)ST=([A-Za-z]{2})").matcher(dn);
        if (m.find()) {
            String uf = m.group(1).toUpperCase();
            if (UF_VALIDAS.contains(uf)) return uf;
        }
        return "SP";
    }

    private byte[] decodificarBase64(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalidCertificate("base64 inválido: " + e.getMessage());
        }
    }

    protected KeyStore carregarKeyStore(byte[] pfxBytes, String senha) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfxBytes), senha.toCharArray());
            return ks;
        } catch (Exception e) {
            throw BusinessException.invalidCertificate("falha ao abrir PKCS12: " + e.getMessage());
        }
    }

    protected X509Certificate extrairCertificado(KeyStore ks) {
        try {
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                Certificate cert = ks.getCertificate(aliases.nextElement());
                if (cert instanceof X509Certificate x509) {
                    return x509;
                }
            }
        } catch (Exception e) {
            throw BusinessException.invalidCertificate("erro ao ler aliases do PKCS12: " + e.getMessage());
        }
        throw BusinessException.invalidCertificate("nenhum certificado X.509 encontrado no PKCS12");
    }

    private String extrairCnpjDoCertificado(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();

        Matcher serial = Pattern.compile("(?i)(?:SERIALNUMBER|OID\\.2\\.5\\.4\\.5)=([^,]+)").matcher(dn);
        if (serial.find()) {
            String val = serial.group(1).replaceAll("\\D", "");
            if (val.length() == 14 && isCnpjValido(val)) return val;
        }

        Matcher digits = Pattern.compile("\\d{14}").matcher(dn);
        while (digits.find()) {
            String candidate = digits.group();
            if (isCnpjValido(candidate)) return candidate;
        }

        throw BusinessException.invalidCertificate(
                "CNPJ não localizado no Subject do certificado X.509. DN=" + dn);
    }

    private boolean isCnpjValido(String cnpj) {
        try {
            CpfCnpjValidator.validar(cnpj);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private String sha256HexDeCert(X509Certificate cert) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return bytesToHex(hash);
        } catch (Exception e) {
            throw BusinessException.invalidCertificate("falha ao calcular fingerprint: " + e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
