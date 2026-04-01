package br.com.borurio.fiscal;

import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

public class TesteCertificadoReal {

    @Test
    void deveCarregarCertificadoPfx() throws Exception {

        String caminho = "C:\\Projetos\\borurio-erp-br\\docker\\certs\\pfx\\certificado-jcho.pfx";
        String senha = "2025@Qz1";

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new FileInputStream(caminho), senha.toCharArray());

        Enumeration<String> aliases = ks.aliases();

        assertTrue(aliases.hasMoreElements(), "Nenhum alias encontrado");

        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();

            System.out.println("Alias: " + alias);

            if (ks.isKeyEntry(alias)) {
                PrivateKey key = (PrivateKey) ks.getKey(alias, senha.toCharArray());

                assertNotNull(key, "PrivateKey não pode ser null");

                System.out.println("✔ PrivateKey carregada");
            }
        }

        System.out.println("CERTIFICADO OK");
    }
}