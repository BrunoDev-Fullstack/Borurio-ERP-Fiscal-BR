package br.com.borurio.fiscal.config.ssl;

import br.com.borurio.fiscal.config.properties.FiscalCertificateProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * =============================================================================
 * CONFIGURAÇÃO SSL FISCAL — BORURIO ERP FISCAL BR
 * =============================================================================
 * Responsável por:
 *  - Carregar certificado A1 (PFX / PKCS12)
 *  - Carregar TrustStore ICP-Brasil / SEFAZ
 *  - Criar SSLContext fiscal
 *
 * ATIVA SOMENTE quando:
 * fiscal.certificate.enabled = true
 *
 * Este bean pertence EXCLUSIVAMENTE ao módulo borurio-fiscal.
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * =============================================================================
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
        prefix = "fiscal.certificate",
        name = "enabled",
        havingValue = "true"
)
public class FiscalSslConfig {

    private final FiscalCertificateProperties properties;

    public FiscalSslConfig(FiscalCertificateProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SSLContext fiscalSslContext() {

        log.info("Inicializando SSL fiscal");
        log.info("Tipo de certificado: {}", properties.getType());
        log.info("PFX: {}", properties.getPfxPath());
        log.info("TrustStore: {}", properties.getTruststorePath());

        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            try (InputStream pfxInput =
                         Files.newInputStream(Path.of(properties.getPfxPath()))) {
                keyStore.load(
                        pfxInput,
                        properties.getPfxPassword().toCharArray()
                );
            }

            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(
                    keyStore,
                    properties.getPfxPassword().toCharArray()
            );

            KeyStore trustStore = KeyStore.getInstance("JKS");

            try (InputStream tsInput =
                         Files.newInputStream(Path.of(properties.getTruststorePath()))) {
                trustStore.load(
                        tsInput,
                        properties.getTruststorePassword().toCharArray()
                );
            }

            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(
                    kmf.getKeyManagers(),
                    tmf.getTrustManagers(),
                    new SecureRandom()
            );

            log.info("SSLContext fiscal inicializado com sucesso");
            return sslContext;

        } catch (Exception e) {
            log.error("Falha crítica ao inicializar SSLContext fiscal", e);
            throw new IllegalStateException("Erro ao configurar SSL fiscal", e);
        }
    }
}
