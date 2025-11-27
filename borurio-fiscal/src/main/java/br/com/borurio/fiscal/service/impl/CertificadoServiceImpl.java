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
import java.io.File;
import java.io.FileInputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

/**
 * =============================================================================
 * SERVIÇO: CertificadoServiceImpl (DEV / HOM / PRD)
 * -----------------------------------------------------------------------------
 * Responsável por carregar o certificado digital A1 (.pfx) e montar o SSLContext
 * para comunicação mTLS com os webservices SEFAZ-SP (NF-e 4.00).
 *
 * Campos carregados via application.yml:
 *   - fiscal.cert.path
 *   - fiscal.cert.pass
 *
 * Compatível com:
 *   DEV → Homologação SEFAZ real
 *   HOM → Homologação SEFAZ real
 *   PRD → Produção SEFAZ real
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack
 * Revisão Final: 26/11/2025
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

    /**
     * Inicializa o carregamento do certificado A1 após o contexto Spring subir.
     */
    @PostConstruct
    public void init() {
        try {
            log.info("=================================================================");
            log.info("Inicializando Certificado A1 para comunicação SEFAZ-SP...");
            log.info("=================================================================");

            // ------------------------------------------------------------
            // 1) Validação do caminho
            // ------------------------------------------------------------
            if (certPath == null || certPath.isBlank()) {
                log.error("[CERTIFICADO] Caminho do .pfx não informado. Aborte.");
                return;
            }

            File certFile = new File(certPath);
            if (!certFile.exists()) {
                log.error("[CERTIFICADO] Arquivo não encontrado: {}", certFile.getAbsolutePath());
                return;
            }

            log.info("[CERTIFICADO] Arquivo localizado: {}", certFile.getAbsolutePath());
            log.info("[CERTIFICADO] Tamanho: {} bytes", certFile.length());

            // ------------------------------------------------------------
            // 2) Carregando KeyStore PKCS12
            // ------------------------------------------------------------
            KeyStore keyStore = KeyStore.getInstance("PKCS12");

            try (FileInputStream fis = new FileInputStream(certFile)) {
                keyStore.load(fis, certPass.toCharArray());
            }

            // ------------------------------------------------------------
            // 3) Alias e Certificado
            // ------------------------------------------------------------
            Enumeration<String> aliases = keyStore.aliases();
            if (!aliases.hasMoreElements()) {
                throw new IllegalStateException("Nenhum alias encontrado no certificado .pfx");
            }

            String alias = aliases.nextElement();
            log.info("[CERTIFICADO] Alias encontrado: {}", alias);

            X509Certificate certificado = (X509Certificate) keyStore.getCertificate(alias);

            log.info("[CERTIFICADO] Subject: {}", certificado.getSubjectDN());
            log.info("[CERTIFICADO] Issuer : {}", certificado.getIssuerDN());
            log.info("[CERTIFICADO] Validade: {} até {}", certificado.getNotBefore(), certificado.getNotAfter());

            // ------------------------------------------------------------
            // 4) Verificando chave privada (obrigatória para SEFAZ)
            // ------------------------------------------------------------
            Key privateKey = keyStore.getKey(alias, certPass.toCharArray());
            if (privateKey == null) {
                throw new IllegalStateException("Chave privada não encontrada no certificado.");
            }

            log.info("[CERTIFICADO] Chave privada carregada com sucesso.");

            // ------------------------------------------------------------
            // 5) KeyManager e TrustManager
            // ------------------------------------------------------------
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, certPass.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            // ------------------------------------------------------------
            // 6) SSLContext TLS 1.2 (Obrigatório SEFAZ)
            // ------------------------------------------------------------
            sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            log.info("=================================================================");
            log.info("[CERTIFICADO] SSLContext inicializado com sucesso.");
            log.info("[CERTIFICADO] Certificado digital A1 devidamente carregado.");
            log.info("=================================================================");

        } catch (Exception e) {
            log.error("=================================================================");
            log.error("[ERRO CRÍTICO] Falha ao carregar o certificado A1: {}", e.getMessage());
            log.error("Stacktrace completo:", e);
            log.error("=================================================================");
            sslContext = null;
        }
    }

    // ============================================================
    // Métodos públicos
    // ============================================================

    @Override
    public SSLContext getSslContext() {
        return sslContext;
    }

    @Override
    public String getStatus() {
        return (sslContext != null)
                ? "Certificado carregado e SSLContext ativo"
                : "Certificado não carregado ou inválido";
    }
}
