package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mockito;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static br.com.borurio.fiscal.support.TestResourceSupport.assumeTestCertificateAvailable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * =============================================================================
 * TESTE LOCAL PRÉ-TRANSMISSÃO — Borurio ERP Fiscal BR
 * =============================================================================
 */
public class NfePipelineLocalTest {

    private static final String SCHEMA_CONSOLIDADO = "xsd/custom/nfe_v4.00_consolidado.xsd";
    private static final String FIXTURE_ENVINFE = "xml/mockEnviNFe.xml";
    private static final String FIXTURE_NFE_BARE = "xml/nfe-bare-unsigned.xml";
    private static final String CERT_TESTE_PATH = "cert/test-cert.pfx";
    private static final String CERT_TESTE_TYPE = "PKCS12";

    private static final String XML_NFE_NAO_ASSINADO = """
            <NFe xmlns="http://www.portalfiscal.inf.br/nfe">
                <infNFe Id="NFe35260412345678000195550010000000011000000010" versao="4.00">
                    <ide>
                        <cUF>35</cUF>
                        <natOp>VENDA DE MERCADORIA</natOp>
                        <mod>55</mod>
                        <serie>1</serie>
                        <nNF>1</nNF>
                        <dhEmi>2026-04-28T10:00:00-03:00</dhEmi>
                        <tpNF>1</tpNF>
                        <idDest>1</idDest>
                        <cMunFG>3550308</cMunFG>
                        <tpImp>1</tpImp>
                        <tpEmis>1</tpEmis>
                        <cDV>0</cDV>
                        <tpAmb>2</tpAmb>
                        <finNFe>1</finNFe>
                        <indFinal>1</indFinal>
                        <indPres>1</indPres>
                        <procEmi>0</procEmi>
                        <verProc>1.0</verProc>
                    </ide>
                </infNFe>
            </NFe>
            """;

    @Test
    @DisplayName("Deve validar mockEnviNFe.xml contra XSD consolidado NF-e 4.00 sem SOAP")
    void deveValidarXsdLocalSemSoap() throws Exception {

        ClassPathResource resource = new ClassPathResource(FIXTURE_ENVINFE);
        assertTrue(resource.exists(),
                "Fixture '" + FIXTURE_ENVINFE + "' não encontrada no classpath de teste.");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document xmlDoc;
        try (var inputStream = resource.getInputStream()) {
            xmlDoc = builder.parse(inputStream);
        }

        XsdValidator validator = new XsdValidator();

        assertDoesNotThrow(
                () -> validator.validate(xmlDoc, SCHEMA_CONSOLIDADO),
                "O XML '" + FIXTURE_ENVINFE + "' deve ser válido conforme o schema NF-e 4.00."
        );
    }

    @Test
    @DisplayName("Deve assinar XML NF-e com RSA-SHA1 sem SOAP e sem certificado A1 real")
    void deveAssinarXmlLocalSemSoap() throws Exception {

        assumeTestCertificateAvailable();

        CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();
        ReflectionTestUtils.setField(certificadoService, "certPath",     CERT_TESTE_PATH);
        ReflectionTestUtils.setField(certificadoService, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(certificadoService, "certType",     CERT_TESTE_TYPE);
        certificadoService.init();

        AssinaturaXmlService assinaturaService = new AssinaturaXmlService(certificadoService);

        String xmlAssinado = assinaturaService.assinar(XML_NFE_NAO_ASSINADO);

        assertNotNull(xmlAssinado,
                "O XML assinado não pode ser nulo.");
        assertFalse(xmlAssinado.isBlank(),
                "O XML assinado não pode estar vazio.");

        assertTrue(xmlAssinado.contains("<Signature"),
                "O XML assinado deve conter o bloco <Signature> (XMLDSIG envelopado).");
        assertTrue(xmlAssinado.contains("SignedInfo"),
                "O XML assinado deve conter o elemento <SignedInfo>.");
        assertTrue(xmlAssinado.contains("DigestValue"),
                "O XML assinado deve conter o elemento <DigestValue>.");
        assertTrue(xmlAssinado.contains("SignatureValue"),
                "O XML assinado deve conter o elemento <SignatureValue>.");

        assertTrue(xmlAssinado.contains("xmldsig#rsa-sha1"),
                "A assinatura deve usar RSA-SHA1 conforme schema oficial xmldsig-core-schema_v1.01.xsd.");
        assertTrue(xmlAssinado.contains("xmldsig#sha1"),
                "O digest deve usar SHA-1 conforme schema oficial xmldsig-core-schema_v1.01.xsd.");

        assertFalse(xmlAssinado.contains("rsa-sha256"),
                "A assinatura NÃO deve usar RSA-SHA256 (fora do schema oficial fixed=\"rsa-sha1\").");
        assertFalse(xmlAssinado.contains("xmlenc#sha256"),
                "O digest NÃO deve usar SHA-256 (fora do schema oficial fixed=\"sha1\").");
    }

    @Test
    @DisplayName("Deve executar a cadeia local pré-transmissão (XSD + assinatura) sem SOAP e sem SEFAZ")
    void deveExecutarCadeiaLocalPreTransmissaoSemSoap() throws Exception {

        assumeTestCertificateAvailable();

        ClassPathResource resource = new ClassPathResource(FIXTURE_ENVINFE);
        assertTrue(resource.exists(),
                "Fixture '" + FIXTURE_ENVINFE + "' não encontrada no classpath de teste.");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        Document xmlDoc;
        try (var inputStream = resource.getInputStream()) {
            xmlDoc = factory.newDocumentBuilder().parse(inputStream);
        }

        XsdValidator validator = new XsdValidator();
        assertDoesNotThrow(
                () -> validator.validate(xmlDoc, SCHEMA_CONSOLIDADO),
                "[Etapa 1] XSD validation falhou: '" + FIXTURE_ENVINFE + "' inválido."
        );

        CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();
        ReflectionTestUtils.setField(certificadoService, "certPath",     CERT_TESTE_PATH);
        ReflectionTestUtils.setField(certificadoService, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(certificadoService, "certType",     CERT_TESTE_TYPE);
        certificadoService.init();

        AssinaturaXmlService assinaturaService = new AssinaturaXmlService(certificadoService);
        String xmlAssinado = assinaturaService.assinar(XML_NFE_NAO_ASSINADO);

        assertNotNull(xmlAssinado,
                "[Etapa 2] XML assinado é nulo — assinatura falhou.");
        assertTrue(xmlAssinado.contains("<Signature"),
                "[Etapa 2] Bloco <Signature> ausente — assinatura não foi aplicada.");
        assertTrue(xmlAssinado.contains("xmldsig#rsa-sha1"),
                "[Etapa 2] Algoritmo RSA-SHA1 não encontrado no XML assinado.");
        assertTrue(xmlAssinado.contains("DigestValue"),
                "[Etapa 2] <DigestValue> ausente no XML assinado.");
        assertTrue(xmlAssinado.contains("SignatureValue"),
                "[Etapa 2] <SignatureValue> ausente no XML assinado.");
        assertFalse(xmlAssinado.contains("rsa-sha256"),
                "[Etapa 2] RSA-SHA256 detectado — fora do schema oficial fixed=\"rsa-sha1\".");
    }

    @Test
    @DisplayName("Deve rejeitar input <enviNFe> com IllegalArgumentException antes de qualquer transmissão")
    void deveRejeitarEnviNFeComoInput() {

        XsdValidator xsdValidator = new XsdValidator();
        AssinaturaXmlService mockAssina = Mockito.mock(AssinaturaXmlService.class);
        NfeTransmitService mockTransmit = Mockito.mock(NfeTransmitService.class);
        EmitenteProperties emitente = new EmitenteProperties();

        NfeOrquestradorService orquestrador = new NfeOrquestradorService(
                xsdValidator, mockAssina, mockTransmit, emitente);

        String xmlEnviNFe = """
                <enviNFe xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00">
                    <idLote>12345</idLote>
                    <indSinc>1</indSinc>
                    <NFe xmlns="http://www.portalfiscal.inf.br/nfe">
                        <infNFe Id="NFe35260412345678000195550010000000011000000010" versao="4.00">
                            <ide><cUF>35</cUF></ide>
                        </infNFe>
                    </NFe>
                </enviNFe>
                """;

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> orquestrador.processar(xmlEnviNFe, "12345678000195"),
                "NfeOrquestradorService deve rejeitar <enviNFe> com IllegalArgumentException."
        );

        assertTrue(ex.getMessage().contains("NFe"),
                "A mensagem de erro deve indicar que o elemento raiz esperado é <NFe>.");
        assertTrue(ex.getMessage().contains("elemento raiz"),
                "A mensagem de erro deve mencionar 'elemento raiz' para orientar o chamador.");

        Mockito.verify(mockTransmit, Mockito.never())
               .transmitirXml(
                       Mockito.anyString(),
                       Mockito.anyString(),
                       Mockito.anyString(),
                       Mockito.anyInt());
    }

    @Test
    @DisplayName("Deve confirmar que <Signature> é filho direto de <NFe>, não irmão de <NFe>")
    void deveConfirmarAssinaturaDentroDeNFe() throws Exception {

        assumeTestCertificateAvailable();

        CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();
        ReflectionTestUtils.setField(certificadoService, "certPath",     CERT_TESTE_PATH);
        ReflectionTestUtils.setField(certificadoService, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(certificadoService, "certType",     CERT_TESTE_TYPE);
        certificadoService.init();

        AssinaturaXmlService assinaturaService = new AssinaturaXmlService(certificadoService);

        String xmlAssinado = assinaturaService.assinar(XML_NFE_NAO_ASSINADO);
        assertNotNull(xmlAssinado, "XML assinado não pode ser nulo.");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        Document signedDoc;
        try (var stream = new ByteArrayInputStream(xmlAssinado.getBytes(StandardCharsets.UTF_8))) {
            signedDoc = factory.newDocumentBuilder().parse(stream);
        }

        assertEquals("NFe", signedDoc.getDocumentElement().getLocalName(),
                "O elemento raiz do XML assinado deve ser <NFe>.");

        NodeList signatures = signedDoc.getElementsByTagNameNS(
                "http://www.w3.org/2000/09/xmldsig#", "Signature");
        assertEquals(1, signatures.getLength(),
                "O XML assinado deve conter exatamente um elemento <Signature>.");

        Node signatureNode = signatures.item(0);
        Node parentNode = signatureNode.getParentNode();

        assertEquals("NFe", parentNode.getLocalName(),
                "<Signature> deve ser filho direto de <NFe>, " +
                "mas está sob <" + parentNode.getLocalName() + ">. " +
                "Isso indicaria que o input foi <enviNFe> em vez de <NFe> bare.");

        NodeList nfeChildren = signedDoc.getDocumentElement().getChildNodes();
        boolean foundInfNFe = false;
        boolean foundSignature = false;

        for (int i = 0; i < nfeChildren.getLength(); i++) {
            Node child = nfeChildren.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if ("infNFe".equals(child.getLocalName())) foundInfNFe = true;
            if ("Signature".equals(child.getLocalName())) foundSignature = true;
        }

        assertTrue(foundInfNFe,
                "Elemento <infNFe> não encontrado como filho direto de <NFe>.");
        assertTrue(foundSignature,
                "Elemento <Signature> não encontrado como filho direto de <NFe>.");
    }

    @Test
    @DisplayName("Deve validar bare <NFe> contra schema consolidado NF-e 4.00 sem SOAP")
    void deveValidarNFeBareContraSchemaLocalSemSoap() throws Exception {

        ClassPathResource resource = new ClassPathResource(FIXTURE_NFE_BARE);
        assertTrue(resource.exists(),
                "Fixture '" + FIXTURE_NFE_BARE + "' não encontrada no classpath de teste.");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        Document xmlDoc;
        try (var inputStream = resource.getInputStream()) {
            xmlDoc = factory.newDocumentBuilder().parse(inputStream);
        }

        assertEquals("NFe", xmlDoc.getDocumentElement().getLocalName(),
                "O elemento raiz da fixture deve ser <NFe>. " +
                "A fixture não deve conter wrapper <enviNFe>.");

        NodeList signatures = xmlDoc.getElementsByTagNameNS(
                "http://www.w3.org/2000/09/xmldsig#", "Signature");
        assertEquals(0, signatures.getLength(),
                "A fixture '" + FIXTURE_NFE_BARE + "' não deve conter <Signature> antes da assinatura.");

        XsdValidator validator = new XsdValidator();
        assertDoesNotThrow(
                () -> validator.validate(xmlDoc, SCHEMA_CONSOLIDADO),
                "Bare <NFe> deve ser válido conforme nfe_v4.00_consolidado.xsd. " +
                "O schema (rev. 3.1.5) declara <NFe> como elemento raiz válido."
        );
    }
}