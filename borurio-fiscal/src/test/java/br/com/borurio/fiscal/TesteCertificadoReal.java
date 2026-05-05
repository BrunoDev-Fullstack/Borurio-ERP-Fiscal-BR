package br.com.borurio.fiscal;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Enumeration;

import static br.com.borurio.fiscal.support.TestResourceSupport.assumeTestCertificateAvailable;
import static org.junit.jupiter.api.Assertions.*;

public class TesteCertificadoReal {

    @Test
    void deveCarregarCertificadoPfx() throws Exception {

        assumeTestCertificateAvailable();

        ClassPathResource resource = new ClassPathResource("cert/test-cert.pfx");
        assertTrue(resource.exists(),
                "Certificado de teste não encontrado no classpath: cert/test-cert.pfx");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(resource.getInputStream(), "2025@Qz1".toCharArray());

        Enumeration<String> aliases = ks.aliases();
        assertTrue(aliases.hasMoreElements(), "Nenhum alias encontrado no certificado de teste");

        boolean encontrouChavePrivada = false;

        aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            System.out.println("Alias: " + alias);

            if (ks.isKeyEntry(alias)) {
                PrivateKey key = (PrivateKey) ks.getKey(alias, "2025@Qz1".toCharArray());
                assertNotNull(key, "PrivateKey não pode ser null");
                System.out.println("PrivateKey carregada — algoritmo: " + key.getAlgorithm());
                encontrouChavePrivada = true;
            }
        }

        assertTrue(encontrouChavePrivada,
                "Nenhuma entrada de chave privada encontrada no certificado");

        System.out.println("CERTIFICADO OK");
    }
}