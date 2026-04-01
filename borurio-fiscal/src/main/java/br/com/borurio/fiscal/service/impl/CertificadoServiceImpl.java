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
            carregarCertificado();
        } catch (Exception e) {
            log.error("[CERT] Falha na inicialização via @PostConstruct", e);
        }
    }

    private void carregarCertificado() throws Exception {

        if (this.sslContext != null) {
            return;
        }

        String certPath = System.getProperty("fiscal.certificate.path");
        String certPass = System.getProperty("fiscal.certificate.password");
        String certType = System.getProperty("fiscal.certificate.type", "PKCS12");

        // 🔥 FALLBACK PARA TESTE / DEV
        if (certPath == null || certPass == null) {
            log.warn("[CERT] Propriedades não definidas, usando fallback local");

            certPath = "C:\\Projetos\\borurio-erp-br\\docker\\certs\\pfx\\certificado-jcho.pfx";
            certPass = "2025@Qz1";
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

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
        );
        kmf.init(keyStore, certPass.toCharArray());

        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        log.info("[CERT] SSLContext inicializado com sucesso");
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
        try {
            if (sslContext == null) {
                log.warn("[CERT] SSLContext null → inicializando via lazy load");
                carregarCertificado();
            }
            return sslContext;

        } catch (Exception e) {
            throw new IllegalStateException("Erro ao obter SSLContext", e);
        }
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
            throw new IllegalStateException("Senha não inicializada");
        }
        return certPassword.toCharArray();
    }

    @Override
    public String getStatus() {
        return sslContext != null
                ? "SSLContext ativo"
                : "SSLContext inativo";
    }
}