package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import javax.net.ssl.SSLContext;

/**
 * Teste unitário para validar o carregamento do certificado digital A1 (.pfx)
 * e a inicialização correta do SSLContext.
 *
 * Compatível com a versão sem @PostConstruct.
 * O SSLContext é inicializado sob demanda via getSslContext().
 */
@ActiveProfiles("dev")
public class CertificadoServiceImplTest {

    @Test
    void deveCarregarCertificadoEAbrirSSLContext() {
        // Instancia o serviço
        CertificadoServiceImpl service = new CertificadoServiceImpl();

        // Define parâmetros manualmente (simulando injeção do Spring)
        System.setProperty("fiscal.certificate.path", "certs/generic-dev-cert.pfx");
        System.setProperty("fiscal.certificate.password", "senha123");
        System.setProperty("fiscal.certificate.type", "PKCS12");

        // Força inicialização sob demanda
        SSLContext sslContext = service.getSslContext();

        // Validação
        if (sslContext != null) {
            System.out.println("[TESTE OK] Certificado carregado e SSLContext criado (TLS 1.2).");
        } else {
            System.out.println("[AVISO] Certificado não carregado — verifique o caminho e a senha do .pfx.");
        }

        Assertions.assertNotNull(sslContext, "O SSLContext não deveria ser nulo após carregar o certificado.");
    }
}
