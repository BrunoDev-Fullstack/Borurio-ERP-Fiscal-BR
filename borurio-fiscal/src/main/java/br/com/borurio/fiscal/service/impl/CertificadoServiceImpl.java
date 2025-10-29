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
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * =============================================================================
 * SERVIÇO: CertificadoServiceImpl (Unificado – HOM / PRD)
 * -----------------------------------------------------------------------------
 * Função:
 *   Carrega e inicializa o certificado digital A1 (.pfx) configurado via
 *   variáveis de ambiente ou application.yml, gerando um SSLContext válido
 *   para comunicação com os webservices da SEFAZ-SP (NF-e 4.00).
 *
 * Perfis suportados:
 *   - hom : ambiente de homologação real SEFAZ-SP
 *   - prd : ambiente de produção real SEFAZ-SP
 *
 * Variáveis esperadas:
 *   - fiscal.cert.path → Caminho absoluto do certificado (.pfx)
 *   - fiscal.cert.pass → Senha do certificado
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Java / DevSecOps
 * Data: 29/10/2025
 */
@Service
@Profile({"hom", "prd"})
public class CertificadoServiceImpl implements CertificadoService {

    private static final Logger logger = LoggerFactory.getLogger(CertificadoServiceImpl.class);

    @Value("${fiscal.cert.path:}")
    private String certPath;

    @Value("${fiscal.cert.pass:}")
    private String certPass;

    private SSLContext sslContext;

    /**
     * Inicializa o SSLContext com base no certificado informado.
     */
    @PostConstruct
    public void init() {
        try {
            if (certPath == null || certPath.isEmpty()) {
                logger.warn("[CertificadoServiceImpl] Caminho do certificado não informado — SSLContext desativado.");
                return;
            }

            logger.info("[CertificadoServiceImpl] Iniciando carregamento do certificado: {}", certPath);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(certPath)) {
                keyStore.load(fis, certPass.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, certPass.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            logger.info("[CertificadoServiceImpl] Certificado digital carregado com sucesso (SSLContext ativo).");

        } catch (Exception e) {
            logger.error("[CertificadoServiceImpl] Falha ao inicializar certificado digital: {}", e.getMessage(), e);
            sslContext = null;
        }
    }

    @Override
    public SSLContext getSslContext() {
        return sslContext;
    }

    /**
     * Retorna o status atual do serviço de certificado.
     *
     * @return String descritiva com o estado do certificado.
     */
    public String getStatus() {
        return (sslContext != null)
                ? "CertificadoServiceImpl: SSLContext ativo — certificado carregado com sucesso"
                : "CertificadoServiceImpl: SSLContext inativo — certificado não inicializado";
    }
}
