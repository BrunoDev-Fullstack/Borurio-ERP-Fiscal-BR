package br.com.borurio.fiscal.config;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

/**
 * Utilitário de SSL para testes manuais locais com certificado A1.
 * Uso: passar -Dtest.cert.path=<caminho> -Dtest.cert.password=<senha> na JVM.
 * Não é usado em produção — o contexto SSL de produção é gerenciado por EmpresaCertificadoService.
 */
public class SslConfig {

    public static SSLContext criarSSLContext() {
        try {
            String caminho = System.getProperty("test.cert.path",
                    "docker/certs/pfx/certificado-jcho.pfx");
            String senha = System.getProperty("test.cert.password", "");

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new FileInputStream(caminho), senha.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm()
            );
            kmf.init(keyStore, senha.toCharArray());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            return sslContext;

        } catch (Exception e) {
            throw new RuntimeException("Erro ao criar SSLContext", e);
        }
    }
}