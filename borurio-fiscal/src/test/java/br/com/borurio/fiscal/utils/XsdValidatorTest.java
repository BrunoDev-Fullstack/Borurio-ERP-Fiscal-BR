package br.com.borurio.fiscal.utils;

import org.junit.jupiter.api.BeforeEach;
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
 * Responsável por validar o comportamento do componente {@link XsdValidator},
 * garantindo que os XMLs fiscais de mock estejam em conformidade com os schemas
 * oficiais da SEFAZ (NF-e 4.00 / PL009 / NT 2025.002).
 *
 * Ambiente: DEV / Homologação
 * Módulo: borurio-fiscal
 * Autor: Bruno Ribeiro — DevSecOps / Fiscal BR
 * =============================================================================
 */
public class XsdValidatorTest {

    private XsdValidator validator;

    @BeforeEach
    void setUp() {
        validator = new XsdValidator();
    }

    /**
     * Testa a validação do XML de envio de NF-e (enviNFe).
     */
    @Test
    void testValidarEnviNFeXml() throws Exception {
        Document xml = carregarXml("xml/mockEnviNFe.xml");
        assertDoesNotThrow(() -> validator.validate(xml, "xsd/enviNFe_v4.00.xsd"));
    }

    /**
     * Testa a validação do XML de retorno de NF-e (retEnviNFe).
     */
    @Test
    void testValidarRetEnviNFeXml() throws Exception {
        Document xml = carregarXml("xml/mockRetEnviNFe.xml");
        assertDoesNotThrow(() -> validator.validate(xml, "xsd/retEnviNFe_v4.00.xsd"));
    }

    /**
     * Testa falha proposital — XML inválido (simulação de erro estrutural).
     */
    @Test
    void testXmlInvalidoDeveFalhar() throws Exception {
        Document xml = carregarXml("xml/mockEnviNFe.xml");
        xml.getDocumentElement().removeAttribute("xmlns"); // remove o namespace
        Exception exception = assertThrows(Exception.class, () ->
                validator.validate(xml, "xsd/enviNFe_v4.00.xsd"));
        assertTrue(exception.getMessage().contains("Falha de conformidade"));
    }

    /**
     * Carrega o XML do classpath de teste.
     */
    private Document carregarXml(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        assertTrue(resource.exists(), "Arquivo XML de teste não encontrado: " + path);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();

        try (var inputStream = resource.getInputStream()) {
            return builder.parse(inputStream);
        }
    }
}
