package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.CertificadoService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * =============================================================================
 * SERVIÇO: CertificadoServiceImpl
 * =============================================================================
 * Responsabilidade:
 *  - Carregar certificado digital A1 (PKCS12)
 *  - Inicializar SSLContext compatível com SEFAZ (TLSv1.2)
 *
 * Ambientes:
 *  - dev | hom | prd
 *
 * Variáveis:
 *  - fiscal.cert.path
 *  - fiscal.cert.pass
 *
 * =============================================================================
 * Autor: Bruno Ribeiro
 * =============================================================================
 */
@Service
@Profile({"dev", "hom", "prd"})
public class CertificadoServiceImpl implements CertificadoService {

    private static final Logger log = LoggerFactory.getLogger(CertificadoServiceImpl.class);

    @Value("${fiscal.cert.path:}")
    private String certPath;

    @Value("${fiscal.cert.pass:}")
    private String certPass;

    private SSLContext sslContext;

    @PostConstruct
    public void init() {
        try {
            if (certPath == null || certPath.isBlank()) {
                log.warn("[CERT] Caminho do certificado A1 não informado. SSLContext não será inicializado.");
                return;
            }

            log.info("[CERT] Carregando certificado A1: {}", certPath);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(certPath)) {
                keyStore.load(fis, certPass.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, certPass.toCharArray());

            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(kmf.getKeyManagers(), null, null);

            log.info("[CERT] Certificado A1 carregado com sucesso. SSLContext ativo (TLSv1.2).");

        } catch (Exception e) {
            sslContext = null;
            log.error("[CERT] Falha ao inicializar certificado A1", e);
        }
    }

    @Override
    public SSLContext getSslContext() {
        return sslContext;
    }

    @Override
    public String getStatus() {
        return sslContext != null
                ? "SSLContext ativo (Certificado A1 carregado)"
                : "SSLContext inativo (Certificado não carregado)";
    }
}
