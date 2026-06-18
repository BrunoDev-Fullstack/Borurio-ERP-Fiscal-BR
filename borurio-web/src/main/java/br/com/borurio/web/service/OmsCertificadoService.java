package br.com.borurio.web.service;

import br.com.borurio.app.entity.OmsCompanyCertificate;
import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.OmsCompanyCertificateMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import br.com.borurio.fiscal.service.CertificadoContexto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

/**
 * Resolve o contexto de certificado para sessões autenticadas via token OMS.
 * Não usa fallback — se não houver certificado ativo e válido, lança exceção.
 */
@Service
public class OmsCertificadoService {

    private static final Logger log = LoggerFactory.getLogger(OmsCertificadoService.class);

    private final OmsFiscalAuthorizationMapper omsAuthMapper;
    private final OmsCompanyCertificateMapper  omsCertMapper;
    private final CertSenhaEncryptor           encryptor;

    public OmsCertificadoService(OmsFiscalAuthorizationMapper omsAuthMapper,
                                  OmsCompanyCertificateMapper omsCertMapper,
                                  CertSenhaEncryptor encryptor) {
        this.omsAuthMapper = omsAuthMapper;
        this.omsCertMapper = omsCertMapper;
        this.encryptor     = encryptor;
    }

    /**
     * Carrega o contexto de certificado para a autorização identificada pelo jti.
     * Verifica revogação a cada chamada — sem cache — para garantir que tokens
     * revogados sejam recusados imediatamente sem aguardar expiração do JWT.
     */
    public CertificadoContexto resolverPorJti(String jti) {
        OmsFiscalAuthorization auth = omsAuthMapper.buscarPorJti(jti);
        if (auth == null || auth.getRevogadoEm() != null) {
            log.warn("[OmsCert] Autorização revogada ou inexistente | jti={}", jti);
            throw BusinessException.authorizationRevoked();
        }

        OmsCompanyCertificate certRow = omsCertMapper.buscarAtivoPorAuthId(auth.getId());
        if (certRow == null) {
            log.error("[OmsCert] Nenhum certificado ativo encontrado | authId={}", auth.getId());
            throw BusinessException.invalidCertificate(
                    "nenhum certificado ativo para a autorização id=" + auth.getId());
        }

        return carregarContexto(auth.getEmpresaId(), certRow);
    }

    private CertificadoContexto carregarContexto(Long empresaId, OmsCompanyCertificate certRow) {
        try {
            byte[] pfxBytes = encryptor.decryptBytes(certRow.getCertPfxEnc());
            String senha    = encryptor.decrypt(certRow.getCertSenhaEnc());

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(pfxBytes), senha.toCharArray());

            String alias = resolverAlias(ks);
            PrivateKey  pk   = (PrivateKey)  ks.getKey(alias, senha.toCharArray());
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, senha.toCharArray());

            SSLContext ssl = SSLContext.getInstance("TLSv1.2");
            ssl.init(kmf.getKeyManagers(), null, null);

            log.info("[OmsCert] Certificado OMS carregado | empresaId={} | thumbprint={}",
                    empresaId, certRow.getThumbprint());
            return new CertificadoContexto(empresaId, pk, cert, ssl);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Falha ao carregar certificado OMS para empresaId=" + empresaId, e);
        }
    }

    private String resolverAlias(KeyStore ks) throws Exception {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String candidate = aliases.nextElement();
            if (ks.isKeyEntry(candidate)) return candidate;
        }
        throw BusinessException.invalidCertificate("nenhuma chave privada no PKCS12 OMS");
    }
}
