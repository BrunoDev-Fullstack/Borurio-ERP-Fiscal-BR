package br.com.borurio.fiscal.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * =============================================================================
 * TESTE UNITÁRIO: XsdValidatorTest
 * -----------------------------------------------------------------------------
 * Objetivo:
 *   - Validar o comportamento do componente {@link XsdValidator};
 *   - Garantir que os XMLs fiscais de mock estejam em conformidade com os
 *     schemas oficiais da SEFAZ (NF-e 4.00 / NT 2025.002 / PL_010b v1.30);
 *   - Certificar a compatibilidade com o schema consolidado nacionalizado
 *     (nfe_v4.00_consolidado.xsd), utilizado no Borurio ERP Fiscal BR.
 *
 * Contexto:
 *   - Ambiente de desenvolvimento e homologação (JUnit 5 + Xerces);
 *   - Proteção contra XXE e falhas de schema injection;
 *   - Total aderência às práticas DevSecOps.
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Revisão: 3.0.7 — Teste endurecido e tolerância controlada
 * =============================================================================
 */
public class XsdValidatorTest {

    private static final String SCHEMA_CONSOLIDADO = "xsd/consolidado/nfe_v4.00_consolidado.xsd";
    private XsdValidator validator;

    @BeforeEach
    void setUp() {
        validator = new XsdValidator();
    }

    // ========================================================================
    // TESTE 01 — XML válido de envio (mockEnviNFe.xml)
    // ========================================================================
    @Test
    @DisplayName("Validação bem-sucedida do XML de envio da NF-e (enviNFe)")
    void testValidarEnviNFeXml() throws Exception {
        Document xml = carregarXml("xml/mockEnviNFe.xml");

        assertDoesNotThrow(() ->
                        validator.validate(xml, SCHEMA_CONSOLIDADO),
                "O XML mockEnviNFe.xml deveria ser válido conforme o schema consolidado (NT 2025.002 / PL_010b v1.30).");
    }

    // ========================================================================
    // TESTE 02 — XML válido de retorno (mockRetEnviNFe.xml)
    // ========================================================================
    @Test
    @DisplayName("Validação bem-sucedida do XML de retorno da NF-e (retEnviNFe)")
    void testValidarRetEnviNFeXml() throws Exception {
        Document xml = carregarXml("xml/mockRetEnviNFe.xml");

        assertDoesNotThrow(() ->
                        validator.validate(xml, SCHEMA_CONSOLIDADO),
                "O XML mockRetEnviNFe.xml deveria ser válido conforme o schema consolidado (NT 2025.002 / PL_010b v1.30).");
    }

    // ========================================================================
    // TESTE 03 — Falha controlada de conformidade XSD (namespace removido)
    // ========================================================================
    @Test
    @DisplayName("Falha esperada ao remover o namespace raiz do XML")
    void testXmlInvalidoDeveFalhar() throws Exception {
        Document xml = carregarXml("xml/mockEnviNFe.xml");

        // Remove namespace obrigatório e altera o nome da tag raiz
        // para garantir falha estrutural real.
        xml.renameNode(xml.getDocumentElement(), null, "enviNFeSemNamespace");
        xml.getDocumentElement().removeAttribute("xmlns");
        xml.getDocumentElement().removeAttribute("xmlns:xsi");

        // Força ausência de targetNamespace
        xml.getDocumentElement().setAttribute("xmlns", "");

        // Execução controlada: validação deve falhar por não encontrar o namespace raiz
        Exception exception = assertThrows(Exception.class, () ->
                        validator.validate(xml, SCHEMA_CONSOLIDADO),
                "A validação deveria falhar quando o namespace obrigatório é removido ou alterado.");

        assertTrue(
                exception.getMessage().contains("cvc-elt.1.a")
                        || exception.getMessage().contains("SAXParseException")
                        || exception.getMessage().contains("Falha de conformidade"),
                "A mensagem da exceção deve indicar erro de conformidade XML/XSD.");
    }

    // ========================================================================
    // MÉTODO AUXILIAR — Carrega XML do classpath e aplica hardening parser
    // ========================================================================
    private Document carregarXml(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        assertTrue(resource.exists(), "Arquivo XML de teste não encontrado: " + path);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        try (var inputStream = resource.getInputStream()) {
            return builder.parse(inputStream);
        }
    }
}
