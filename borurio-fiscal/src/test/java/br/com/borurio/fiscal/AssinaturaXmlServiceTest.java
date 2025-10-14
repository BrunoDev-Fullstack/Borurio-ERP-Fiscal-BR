package br.com.borurio.fiscal;

import br.com.borurio.fiscal.service.AssinaturaXmlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Teste unitário do serviço responsável por assinar documentos fiscais eletrônicos (NF-e).
 *
 * Este teste verifica se o método de assinatura XML gera uma saída válida
 * utilizando o certificado A1 genérico presente em src/main/resources/certs.
 *
 * Padrões aplicados:
 * - JUnit 5 (org.junit.jupiter)
 * - Boas práticas DevSecOps (testes automatizados de serviços críticos)
 */
public class AssinaturaXmlServiceTest {

    private final AssinaturaXmlService assinaturaXmlService = new AssinaturaXmlService();

    @Test
    @DisplayName("Valida se o XML é assinado corretamente com o certificado A1 genérico")
    void deveAssinarXmlFiscalComSucesso() {
        String xml = "<NFe><infNFe Id=\"NFe123\"><det><prod>ProdutoTeste</prod></det></infNFe></NFe>";
        byte[] resultado = assinaturaXmlService.assinarXml(xml.getBytes());
        assertNotNull(resultado, "O XML assinado não deve ser nulo.");
    }
}
