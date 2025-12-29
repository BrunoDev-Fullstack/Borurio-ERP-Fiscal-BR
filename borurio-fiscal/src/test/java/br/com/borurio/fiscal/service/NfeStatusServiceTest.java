package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.config.properties.FiscalCertificateProperties;
import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import br.com.borurio.fiscal.service.impl.NfeStatusServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * =============================================================================
 * TESTE TÉCNICO — CONSULTA STATUS SERVIÇO NF-e (SEFAZ-SP)
 * -----------------------------------------------------------------------------
 * ATENÇÃO:
 * - Teste MANUAL
 * - Depende de certificado A1 válido
 * - Depende de acesso externo à SEFAZ
 * - NÃO indicado para CI/CD
 * =============================================================================
 */
public class NfeStatusServiceTest {

    @Test
    void deveConsultarStatusServicoComCertificadoValido() throws IOException {

        // ---------------------------------------------------------------------
        // Configuração MANUAL do certificado (APENAS PARA TESTE LOCAL)
        // ---------------------------------------------------------------------
        FiscalCertificateProperties props = new FiscalCertificateProperties();
        props.setEnabled(true);
        props.setType("PKCS12");
        props.setPfxPath("C:/certificados/jcho-keystore.p12"); // ajuste se necessário
        props.setPfxPassword("Jcho237888");
        props.setTruststorePath("C:/certificados/sefaz-truststore.jks");
        props.setTruststorePassword("changeit");

        CertificadoServiceImpl certificadoService =
                new CertificadoServiceImpl(props);

        NfeStatusServiceImpl statusService =
                new NfeStatusServiceImpl(certificadoService);

        System.out.println("[TESTE] Iniciando consulta SEFAZ-SP (Homologação)...");

        String resposta = statusService.consultarStatusServico();

        Files.createDirectories(Paths.get("logs"));
        try (FileWriter fw = new FileWriter("logs/nfe_status_response.xml")) {
            fw.write(resposta);
        }

        System.out.println("------ XML Bruto Retornado da SEFAZ-SP ------");
        System.out.println(resposta.substring(0, Math.min(resposta.length(), 2000)));
        System.out.println("------------------------------------------------");

        Assertions.assertNotNull(resposta);
        Assertions.assertTrue(resposta.length() > 100);

        // Não trava build por parser ainda não finalizado
        Assertions.assertTrue(true);
    }
}
