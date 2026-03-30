package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.CertificadoService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Enumeration;

/**
 * =============================================================================
 * SERVIÇO: CertificadoServiceImpl
 * =============================================================================
 * Responsabilidade:
 *  - Carregar certificado digital A1 (PKCS12)
 *  - Inicializar SSLContext (TLSv1.2)
 *  - Expor KeyStore, alias e senha para assinatura XML
 * =============================================================================
 */
@Service
@Profile({"dev", "hom", "prd", "test"})
public class CertificadoServiceImpl implements CertificadoService {

    private static final Logger log = LoggerFactory.getLogger(CertificadoServiceImpl.class);

    private SSLContext sslContext;
    private KeyStore keyStore;
    private String alias;
    private String certPassword;

    @PostConstruct
    public void init() {
        try {

            String certPath = System.getProperty("fiscal.certificate.path");
            String certPass = System.getProperty("fiscal.certificate.password");
            String certType = System.getProperty("fiscal.certificate.type", "PKCS12");

            if (certPath == null || certPass == null) {
                throw new IllegalStateException("Propriedades do certificado não definidas");
            }

            this.certPassword = certPass;

            log.info("[CERT] Carregando certificado A1: {}", certPath);

            try (InputStream is = carregarArquivo(certPath)) {

                keyStore = KeyStore.getInstance(certType);
                keyStore.load(is, certPass.toCharArray());
            }

            Enumeration<String> aliases = keyStore.aliases();
            if (!aliases.hasMoreElements()) {
                throw new IllegalStateException("Nenhum alias encontrado no certificado");
            }

            alias = aliases.nextElement();

            log.info("[CERT] Alias encontrado: {}", alias);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, certPass.toCharArray());

            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(kmf.getKeyManagers(), null, null);

            log.info("[CERT] Certificado A1 carregado com sucesso. SSLContext ativo.");

        } catch (Exception e) {
            sslContext = null;
            keyStore = null;
            log.error("[CERT] Falha ao inicializar certificado A1", e);
            throw new RuntimeException("Erro ao carregar certificado A1", e);
        }
    }

    private InputStream carregarArquivo(String path) throws Exception {

        InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream(path);

        if (is != null) {
            log.info("[CERT] Certificado carregado via classpath");
            return is;
        }

        log.info("[CERT] Certificado carregado via filesystem");
        return new FileInputStream(path);
    }

    @Override
    public SSLContext getSslContext() {
        if (sslContext == null) {
            throw new IllegalStateException("SSLContext não inicializado");
        }
        return sslContext;
    }

    @Override
    public KeyStore getKeyStore() {
        if (keyStore == null) {
            throw new IllegalStateException("KeyStore não inicializado");
        }
        return keyStore;
    }

    @Override
    public String getAlias() {
        if (alias == null) {
            throw new IllegalStateException("Alias não inicializado");
        }
        return alias;
    }

    @Override
    public char[] getSenha() {
        if (certPassword == null) {
            throw new IllegalStateException("Senha do certificado não inicializada");
        }
        return certPassword.toCharArray();
    }

    @Override
    public String getStatus() {
        return sslContext != null
                ? "SSLContext ativo (Certificado A1 carregado)"
                : "SSLContext inativo (Certificado não carregado)";
    }
}