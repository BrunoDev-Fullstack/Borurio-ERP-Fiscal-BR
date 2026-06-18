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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OmsFiscalAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(OmsFiscalAuthorizationService.class);

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
        OmsApiKey apiKey = omsApiKeyMapper.findAtivaPorHash(sha256Hex(rawApiKey));
        if (apiKey == null) {
            log.warn("[OmsAuth] API Key inválida ou revogada");
            throw BusinessException.invalidApiKey();
        }

        // 2. Localizar empresa pré-cadastrada
        Empresa empresa = empresaMapper.buscarPorCnpj(req.getCnpj());
        if (empresa == null || Boolean.FALSE.equals(empresa.getAtivo())) {
            throw BusinessException.companyNotFound(req.getCnpj());
        }

        // 3. Decodificar e carregar PKCS12
        byte[] pfxBytes = decodificarBase64(req.getCertBase64());
        KeyStore ks = carregarKeyStore(pfxBytes, req.getCertSenha());

        // 4. Extrair certificado X.509
        X509Certificate x509 = extrairCertificado(ks);

        // 5. Verificar validade
        if (x509.getNotAfter().before(new Date())) {
            throw BusinessException.certificateExpired();
        }

        // 6. Validar CNPJ do certificado vs CNPJ enviado
        String cnpjCert = extrairCnpjDoCertificado(x509);
        if (!cnpjCert.equals(req.getCnpj())) {
            throw BusinessException.cnpjCertificateMismatch(req.getCnpj(), cnpjCert);
        }

        // 7. Fingerprint SHA-256
        String thumbprint = sha256HexDeCert(x509);

        // 8. Converter datas
        LocalDateTime notBefore = toLocalDateTime(x509.getNotBefore());
        LocalDateTime notAfter  = toLocalDateTime(x509.getNotAfter());

        // 9. Criptografar PFX e senha
        byte[] pfxEnc      = encryptor.encryptBytes(pfxBytes);
        String senhaEnc    = encryptor.encrypt(req.getCertSenha());

        // 10. Localizar ou criar slot de autorização
        OmsFiscalAuthorization auth = omsAuthMapper.buscarPorSlot(
                empresa.getId(), apiKey.getIntegratorId(), req.getCodigoEmpresaOms());

        String jti = UUID.randomUUID().toString();

        if (auth == null) {
            auth = new OmsFiscalAuthorization();
            auth.setEmpresaId(empresa.getId());
            auth.setIntegratorId(apiKey.getIntegratorId());
            auth.setCodigoOms(req.getCodigoEmpresaOms());
            auth.setJti(jti);
            auth.setTokenExpiraEm(notAfter);
            omsAuthMapper.inserir(auth);
            log.info("[OmsAuth] Nova autorização criada | empresaId={} | codigoOms={}",
                    empresa.getId(), req.getCodigoEmpresaOms());
        } else {
            omsAuthMapper.atualizarToken(auth.getId(), jti, notAfter);
            auth.setJti(jti);
            auth.setTokenExpiraEm(notAfter);
            log.info("[OmsAuth] Autorização atualizada (reautorização) | authId={} | empresaId={}",
                    auth.getId(), empresa.getId());
        }

        // 11. Substituir certificado ativo
        LocalDateTime agora = LocalDateTime.now();
        omsCertMapper.desativarCertsAtivos(auth.getId(), agora);

        OmsCompanyCertificate cert = new OmsCompanyCertificate();
        cert.setAuthId(auth.getId());
        cert.setThumbprint(thumbprint);
        cert.setCertPfxEnc(pfxEnc);
        cert.setCertSenhaEnc(senhaEnc);
        cert.setKeyVersion("v1");
        cert.setNotBefore(notBefore);
        cert.setNotAfter(notAfter);
        cert.setAtivo(true);
        omsCertMapper.inserir(cert);

        // 12. Gerar token JWT técnico
        String token = jwtUtil.generateOmsToken(
                req.getCodigoEmpresaOms(), empresa.getId(), jti, notAfter);

        log.info("[OmsAuth] Token OMS emitido | empresaId={} | cnpj={} | notAfter={}",
                empresa.getId(), empresa.getCnpj(), notAfter);

        return new OmsFiscalAuthorizationResponse(
                token, empresa.getId(), empresa.getCnpj(), empresa.getRazaoSocial(), notAfter);
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

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

        // Prioridade: campo SERIALNUMBER (OID 2.5.4.5)
        Matcher serial = Pattern.compile("(?i)(?:SERIALNUMBER|OID\\.2\\.5\\.4\\.5)=([^,]+)").matcher(dn);
        if (serial.find()) {
            String val = serial.group(1).replaceAll("\\D", "");
            if (val.length() == 14 && isCnpjValido(val)) return val;
        }

        // Fallback: primeiro trecho de 14 dígitos no DN que passe na validação de CNPJ
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
