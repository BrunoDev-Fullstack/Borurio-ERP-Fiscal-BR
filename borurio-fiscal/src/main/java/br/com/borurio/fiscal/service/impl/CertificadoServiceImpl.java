package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.properties.FiscalCertificateProperties;
import br.com.borurio.fiscal.service.CertificadoService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * =============================================================================
 * SERVIÇO: CertificadoServiceImpl
 * -----------------------------------------------------------------------------
 * Responsável por:
 *  - Carregar certificado digital A1 (PFX / PKCS12)
 *  - Carregar truststore ICP-Brasil / SEFAZ
 *  - Inicializar SSLContext TLS 1.2+
 *
 * Comportamento por ambiente:
 *  - DEV / HOM: certificado pode ser desabilitado (mock SEFAZ)
 *  - PRD: certificado obrigatório (fail-fast)
 *
 * IMPORTANTE:
 *  - Não utiliza classpath
 *  - Caminhos sempre via filesystem (/app/certs)
 * =============================================================================
 */
@Service
@Profile({"dev", "hom", "prd"})
public class CertificadoServiceImpl implements CertificadoService {

    private static final Logger logger =
            LoggerFactory.getLogger(CertificadoServiceImpl.class);

    private final FiscalCertificateProperties properties;

    private SSLContext sslContext;

    public CertificadoServiceImpl(FiscalCertificateProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {

        if (!properties.isEnabled()) {
            logger.warn(
                    "[CertificadoService] Certificado digital DESABILITADO para este ambiente"
            );
            return;
        }

        logger.info("[CertificadoService] Inicializando certificado fiscal (A1)");

        try {
            validarConfiguracao();

            logger.info("[CertificadoService] Tipo: {}", properties.getType());
            logger.info("[CertificadoService] PFX: {}", properties.getPfxPath());
            logger.info("[CertificadoService] TrustStore: {}", properties.getTruststorePath());

            // ------------------------------------------------------------------
            // 1) KeyStore A1 (PKCS12)
            // ------------------------------------------------------------------
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream ksStream =
                         new FileInputStream(properties.getPfxPath())) {
                keyStore.load(
                        ksStream,
                        properties.getPfxPassword().toCharArray()
                );
            }

            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(
                            KeyManagerFactory.getDefaultAlgorithm()
                    );
            kmf.init(
                    keyStore,
                    properties.getPfxPassword().toCharArray()
            );

            // ------------------------------------------------------------------
            // 2) TrustStore ICP-Brasil / SEFAZ
            // ------------------------------------------------------------------
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream tsStream =
                         new FileInputStream(properties.getTruststorePath())) {
                trustStore.load(
                        tsStream,
                        properties.getTruststorePassword().toCharArray()
                );
            }

            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(
                            TrustManagerFactory.getDefaultAlgorithm()
                    );
            tmf.init(trustStore);

            // ------------------------------------------------------------------
            // 3) SSLContext TLS
            // ------------------------------------------------------------------
            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(
                    kmf.getKeyManagers(),
                    tmf.getTrustManagers(),
                    null
            );

            logger.info("[CertificadoService] SSLContext inicializado com sucesso");

        } catch (Exception e) {
            logger.error(
                    "[CertificadoService] ERRO CRÍTICO na inicialização do certificado",
                    e
            );
            throw new IllegalStateException(
                    "Falha ao inicializar CertificadoService",
                    e
            );
        }
    }

    private void validarConfiguracao() {

        validarArquivo("Certificado A1", properties.getPfxPath());
        validarArquivo("TrustStore SEFAZ", properties.getTruststorePath());

        if (properties.getPfxPassword() == null
                || properties.getPfxPassword().isBlank()) {
            throw new IllegalStateException("Senha do certificado A1 não informada");
        }

        if (properties.getTruststorePassword() == null
                || properties.getTruststorePassword().isBlank()) {
            throw new IllegalStateException("Senha do truststore não informada");
        }
    }

    private void validarArquivo(String descricao, String path) {

        if (path == null || path.isBlank()) {
            throw new IllegalStateException(descricao + " não informado");
        }

        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalStateException(
                    descricao + " inválido ou não encontrado: " + path
            );
        }
    }

    @Override
    public SSLContext getSslContext() {
        return sslContext;
    }

    @Override
    public String getStatus() {
        return isAtivo()
                ? "CertificadoService: SSLContext ativo"
                : "CertificadoService: SSLContext inativo (mock)";
    }

    public boolean isAtivo() {
        return sslContext != null;
    }
}
