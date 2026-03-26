package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.service.impl.NfeAuthorizeServiceImpl;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class NfeAuthorizeServiceTest {

    @Mock
    private NfeLogService nfeLogService;

    private NfeAuthorizeService nfeAuthorizeService;

    @BeforeEach
    void setUp() {
        XsdValidator xsdValidator = new XsdValidator();
        nfeAuthorizeService = new NfeAuthorizeServiceImpl(nfeLogService, xsdValidator);
    }

    @Test
    @DisplayName("Deve autorizar NF-e mock e retornar protocolo com cStat=100")
    void deveAutorizarNFeComSucesso() throws Exception {

        File xmlFile = new File("src/test/resources/xml/mockEnviNFe.xml");
        assertTrue(xmlFile.exists(), "Arquivo mockEnviNFe.xml não encontrado");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document xmlDocumento = builder.parse(xmlFile);

        Document xmlResposta = nfeAuthorizeService.autorizarNFe(xmlDocumento);
        assertNotNull(xmlResposta);

        String xmlString = documentToString(xmlResposta);

        assertAll(
                () -> assertTrue(xmlString.contains("<retEnviNFe")),
                () -> assertTrue(xmlString.contains("<protNFe")),
                () -> assertTrue(xmlString.contains("<cStat>100</cStat>")),
                () -> assertTrue(xmlString.contains("Autorizado o uso da NF-e"))
        );

        File output = new File("logs/mock_retEnviNFe_test.xml");
        Files.createDirectories(output.getParentFile().toPath());
        Files.writeString(output.toPath(), xmlString, StandardCharsets.UTF_8);
    }

    private String documentToString(Document doc) throws Exception {
        var transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();

        transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");

        var out = new java.io.ByteArrayOutputStream();

        transformer.transform(
                new javax.xml.transform.dom.DOMSource(doc),
                new javax.xml.transform.stream.StreamResult(out)
        );

        return out.toString(StandardCharsets.UTF_8);
    }
}