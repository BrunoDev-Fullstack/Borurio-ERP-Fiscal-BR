package br.com.borurio.fiscal.config;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

public class SslConfig {

    public static SSLContext criarSSLContext() {
        try {
            String caminho = "C:\\Projetos\\borurio-erp-br\\docker\\certs\\pfx\\certificado-jcho.pfx";
            String senha = "2025@Qz1";

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