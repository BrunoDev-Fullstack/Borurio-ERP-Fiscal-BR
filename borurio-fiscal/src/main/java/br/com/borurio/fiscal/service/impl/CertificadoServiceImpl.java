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

/**
 * =============================================================================
 * SERVIÇO: CertificadoServiceImpl
 * =============================================================================
 * Responsabilidade:
 *  - Carregar certificado digital A1 (PKCS12)
 *  - Inicializar SSLContext compatível com SEFAZ (TLSv1.2)
 *
 * Suporte:
 *  - Classpath (testes / jar)
 *  - Filesystem (docker / produção)
 *
 * Variáveis:
 *  - fiscal.certificate.path
 *  - fiscal.certificate.password
 * =============================================================================
 */
@Service
@Profile({"dev", "hom", "prd", "test"})
public class CertificadoServiceImpl implements CertificadoService {

    private static final Logger log = LoggerFactory.getLogger(CertificadoServiceImpl.class);

    private SSLContext sslContext;

    @PostConstruct
    public void init() {
        try {

            String certPath = System.getProperty("fiscal.certificate.path");
            String certPass = System.getProperty("fiscal.certificate.password");
            String certType = System.getProperty("fiscal.certificate.type", "PKCS12");

            if (certPath == null || certPass == null) {
                throw new IllegalStateException("Propriedades do certificado não definidas");
            }

            log.info("[CERT] Carregando certificado A1: {}", certPath);

            InputStream is = carregarArquivo(certPath);

            KeyStore keyStore = KeyStore.getInstance(certType);
            keyStore.load(is, certPass.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, certPass.toCharArray());

            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(kmf.getKeyManagers(), null, null);

            log.info("[CERT] Certificado A1 carregado com sucesso. SSLContext ativo.");

        } catch (Exception e) {
            sslContext = null;
            log.error("[CERT] Falha ao inicializar certificado A1", e);
            throw new RuntimeException("Erro ao carregar certificado A1", e);
        }
    }

    private InputStream carregarArquivo(String path) throws Exception {

        // 1. tenta classpath (TESTES / JAR)
        InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream(path);

        if (is != null) {
            log.info("[CERT] Certificado carregado via classpath");
            return is;
        }

        // 2. fallback filesystem (DOCKER / PRODUÇÃO)
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
    public String getStatus() {
        return sslContext != null
                ? "SSLContext ativo (Certificado A1 carregado)"
                : "SSLContext inativo (Certificado não carregado)";
    }
}