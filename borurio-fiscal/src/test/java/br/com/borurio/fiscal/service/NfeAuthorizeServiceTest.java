package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.service.impl.NfeAuthorizeServiceImpl;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste unitário do serviço de autorização mock SEFAZ (NfeAuthorizeServiceImpl).
 *
 * Este teste executa o fluxo completo:
 *  - Carrega um XML de NF-e (mockEnviNFe.xml);
 *  - Valida contra os XSDs oficiais;
 *  - Simula a autorização (mock SEFAZ);
 *  - Verifica a presença dos elementos <retEnviNFe>, <protNFe>, <cStat>100</cStat>.
 *
 * Módulo: borurio-fiscal
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 */
@SpringBootTest
public class NfeAuthorizeServiceTest {

    private NfeAuthorizeService nfeAuthorizeService;

    @BeforeEach
    void setUp() {
        // Cria mocks necessários
        NfeLogService nfeLogServiceMock = Mockito.mock(NfeLogService.class);
        XsdValidator xsdValidator = new XsdValidator();

        // Instancia o serviço real com dependências mockadas
        nfeAuthorizeService = new NfeAuthorizeServiceImpl(nfeLogServiceMock, xsdValidator);
    }

    @Test
    @DisplayName("Deve autorizar NF-e mock e retornar protocolo com cStat=100")
    void deveAutorizarNFeComSucesso() throws Exception {
        // Caminho do XML mockado
        File xmlFile = new File("src/test/resources/xml/mockEnviNFe.xml");
        assertTrue(xmlFile.exists(), "Arquivo mockEnviNFe.xml não encontrado em src/test/resources/xml");

        // Carrega o XML em memória
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document xmlDocumento = builder.parse(xmlFile);

        // Executa o fluxo de autorização mock
        Document xmlResposta = nfeAuthorizeService.autorizarNFe(xmlDocumento);
        assertNotNull(xmlResposta, "Documento de resposta não deve ser nulo");

        // Converte para string e valida conteúdo
        String xmlString = documentToString(xmlResposta);

        assertTrue(xmlString.contains("<retEnviNFe"), "Elemento <retEnviNFe> ausente");
        assertTrue(xmlString.contains("<protNFe"), "Elemento <protNFe> ausente");
        assertTrue(xmlString.contains("<cStat>100</cStat>"), "Status 100 (Autorizado) não encontrado");
        assertTrue(xmlString.contains("Autorizado o uso da NF-e"), "Mensagem de sucesso não encontrada");

        // Grava resposta no diretório de logs para auditoria
        File output = new File("logs/mock_retEnviNFe_test.xml");
        Files.createDirectories(output.getParentFile().toPath());
        Files.writeString(output.toPath(), xmlString, StandardCharsets.UTF_8);

        System.out.println("Teste executado com sucesso — resposta salva em: " + output.getAbsolutePath());
    }

    /**
     * Converte um Document XML em String UTF-8 para fins de verificação.
     */
    private String documentToString(Document doc) throws Exception {
        javax.xml.transform.Transformer transformer =
                javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        transformer.transform(new javax.xml.transform.dom.DOMSource(doc),
                new javax.xml.transform.stream.StreamResult(out));
        return out.toString(StandardCharsets.UTF_8);
    }
}
