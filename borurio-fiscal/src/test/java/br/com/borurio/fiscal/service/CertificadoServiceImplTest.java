package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;

/**
 * Teste unitário para validar o carregamento do certificado digital A1 (.pfx)
 * e a inicialização correta do SSLContext.
 *
 * Execução sem Spring (teste unitário puro).
 */
class CertificadoServiceImplTest {

    @Test
    void deveCarregarCertificadoEAbrirSSLContext() {

        // Define propriedades (equivalente ao -D do Maven)
        System.setProperty("fiscal.certificate.path", "cert/test-cert.pfx");
        System.setProperty("fiscal.certificate.password", "2025@Qz1");
        System.setProperty("fiscal.certificate.type", "PKCS12");

        // Instancia o serviço
        CertificadoServiceImpl service = new CertificadoServiceImpl();

        // Força inicialização (equivalente ao @PostConstruct)
        service.init();

        // Obtém o SSLContext
        SSLContext sslContext = service.getSslContext();

        // Validação
        Assertions.assertNotNull(
                sslContext,
                "O SSLContext não deveria ser nulo após carregar o certificado."
        );

        System.out.println("[TESTE OK] Certificado carregado e SSLContext criado com sucesso.");
    }
}