package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLContext;

import static br.com.borurio.fiscal.support.TestResourceSupport.assumeTestCertificateAvailable;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CertificadoServiceImplTest {

    @Test
    void deveCarregarCertificadoEAbrirSSLContext() {

        assumeTestCertificateAvailable();

        CertificadoServiceImpl service = new CertificadoServiceImpl();

        // @Value não é injetado via System.setProperty() fora do contexto Spring.
        // ReflectionTestUtils injeta diretamente nos campos privados.
        ReflectionTestUtils.setField(service, "certPath",     "cert/test-cert.pfx");
        ReflectionTestUtils.setField(service, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(service, "certType",     "PKCS12");

        service.init();

        SSLContext sslContext = service.getSslContext();

        assertNotNull(sslContext,
                "O SSLContext não deveria ser nulo após carregar o certificado.");

        System.out.println("[TESTE OK] Certificado carregado e SSLContext criado com sucesso.");
    }
}