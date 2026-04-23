package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.CertificadoService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Enumeration;

@Service
@Profile({"dev", "hom", "prd", "test"})
public class CertificadoServiceImpl implements CertificadoService {

    private static final Logger log = LoggerFactory.getLogger(CertificadoServiceImpl.class);

    @Value("${fiscal.certificate.path}")
    private String certPath;

    @Value("${fiscal.certificate.password}")
    private String certPassword;

    @Value("${fiscal.certificate.type:PKCS12}")
    private String certType;

    private SSLContext sslContext;
    private KeyStore keyStore;
    private String alias;

    @PostConstruct
    public void init() {
        try {
            carregarCertificado();
        } catch (Exception e) {
            log.error("[CERT] Falha na inicialização do certificado", e);
            throw new IllegalStateException("Erro crítico ao carregar certificado A1", e);
        }
    }

    private void carregarCertificado() throws Exception {

        // CORREÇÃO 1: a guarda de idempotência estava em sslContext (último campo
        // inicializado). Se o método falhasse após carregar o KeyStore mas antes
        // de criar o SSLContext, uma segunda chamada retornaria imediatamente com
        // alias e keyStore em estado inconsistente.
        // Guarda correta: keyStore — primeiro campo crítico inicializado.
        if (keyStore != null) {
            return;
        }

        if (certPath == null || certPath.isBlank()) {
            throw new IllegalStateException("Propriedade 'fiscal.certificate.path' não configurada");
        }
        if (certPassword == null || certPassword.isBlank()) {
            throw new IllegalStateException("Propriedade 'fiscal.certificate.password' não configurada");
        }

        log.info("[CERT] Carregando certificado: path={} type={}", certPath, certType);

        try (InputStream is = carregarArquivo(certPath)) {
            keyStore = KeyStore.getInstance(certType);
            keyStore.load(is, certPassword.toCharArray());
        }

        // CORREÇÃO 2: aliases().nextElement() pegava o primeiro alias sem verificar
        // se é uma entrada de chave privada (isKeyEntry). Em PKCS12 A1 de algumas ACs
        // (ex: Serasa, Valid), o primeiro alias pode ser um certificado público —
        // getPrivateKey() retornaria null silenciosamente e quebraria a assinatura XMLDSIG.
        alias = resolverAliasChavePrivada();
        log.info("[CERT] Alias de chave privada: {}", alias);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, certPassword.toCharArray());

        // CORREÇÃO 3: SSLContext.getInstance("TLS") sem protocolo mínimo permitia
        // negociação de TLSv1.0/1.1, desabilitados na SEFAZ desde 2020.
        // Protocolo mínimo exigido pela SEFAZ: TLSv1.2.
        sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(kmf.getKeyManagers(), null, null);

        log.info("[CERT] SSLContext TLSv1.2 inicializado — alias={}", alias);
    }

    /**
     * Resolve o alias da entrada de chave privada no KeyStore.
     *
     * Itera todos os aliases e retorna o primeiro que for isKeyEntry.
     * Nunca usa posição (nextElement) — o índice não é garantido por contrato.
     *
     * Compatível com PKCS12 de qualquer AC ICP-Brasil (Certisign, Serasa,
     * Soluti, Valid, SafeWeb, etc).
     */
    private String resolverAliasChavePrivada() throws Exception {
        Enumeration<String> aliases = keyStore.aliases();

        if (!aliases.hasMoreElements()) {
            throw new IllegalStateException(
                    "KeyStore PKCS12 não contém nenhum alias — verifique o arquivo .pfx");
        }

        while (aliases.hasMoreElements()) {
            String candidate = aliases.nextElement();
            if (keyStore.isKeyEntry(candidate)) {
                return candidate;
            }
        }

        // Nenhum alias com chave privada encontrado — .pfx pode ser só certificado público
        throw new IllegalStateException(
                "Nenhuma entrada de chave privada (isKeyEntry) encontrada no KeyStore. " +
                        "O arquivo '" + certPath + "' é um certificado A1 válido com chave privada?");
    }

    private InputStream carregarArquivo(String path) throws Exception {
        // 1. Classpath (testes, embed)
        ClassPathResource resource = new ClassPathResource(path);
        if (resource.exists()) {
            log.debug("[CERT] Fonte: classpath");
            return resource.getInputStream();
        }

        // 2. Filesystem (Docker / volume externo)
        java.io.File file = new java.io.File(path);
        if (file.exists()) {
            log.debug("[CERT] Fonte: filesystem");
            return new java.io.FileInputStream(file);
        }

        throw new IllegalStateException(
                "Certificado não encontrado em: " + path +
                        " (verificado classpath e filesystem)");
    }

    @Override
    public SSLContext getSslContext() {
        if (sslContext == null) throw new IllegalStateException("SSLContext não inicializado");
        return sslContext;
    }

    @Override
    public KeyStore getKeyStore() {
        if (keyStore == null) throw new IllegalStateException("KeyStore não inicializado");
        return keyStore;
    }

    @Override
    public String getAlias() {
        if (alias == null) throw new IllegalStateException("Alias não inicializado");
        return alias;
    }

    @Override
    public char[] getSenha() {
        if (certPassword == null) throw new IllegalStateException("Senha não inicializada");
        return certPassword.toCharArray();
    }

    @Override
    public String getStatus() {
        if (sslContext == null) return "SSLContext inativo";
        try {
            // Informa validade do certificado para monitoramento
            java.security.cert.X509Certificate cert =
                    (java.security.cert.X509Certificate) keyStore.getCertificate(alias);
            cert.checkValidity(); // lança CertificateExpiredException se vencido
            return "SSLContext ativo | valido ate: " + cert.getNotAfter();
        } catch (java.security.cert.CertificateExpiredException e) {
            return "CERTIFICADO EXPIRADO";
        } catch (Exception e) {
            return "SSLContext ativo";
        }
    }
}