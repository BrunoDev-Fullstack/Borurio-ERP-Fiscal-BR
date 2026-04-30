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

import static org.junit.jupiter.api.Assertions.*;

/**
 * =============================================================================
 * TESTE LOCAL PRÉ-TRANSMISSÃO — Borurio ERP Fiscal BR
 * =============================================================================
 * Objetivo:
 *   Validar a cadeia fiscal local sem SOAP, sem SEFAZ e sem conexão de rede.
 *   Este teste cobre apenas as etapas anteriores à transmissão:
 *     1. Parse + Validação XSD (nfe_v4.00_consolidado.xsd)
 *     2. Assinatura XMLDSIG RSA-SHA256 (AssinaturaXmlService)
 *
 * Restrições:
 *   - NfeTransmitService.transmitirXml() NÃO é invocado.
 *   - Nenhuma conexão HTTPS/SOAP é aberta.
 *   - Certificado A1 real (borurio-jcho) NÃO é utilizado.
 *   - Apenas test-cert.pfx do classpath de teste é utilizado.
 *
 * Ambiente:
 *   - JUnit 5 / Spring Test (sem ApplicationContext)
 *   - Instanciação direta via new (ReflectionTestUtils para @Value)
 *
 * Autor: Bruno Ribeiro — DevSecOps Fiscal BR
 * Módulo: borurio-fiscal
 * Branch: fix/sefaz-xml-structure
 * =============================================================================
 */
public class NfePipelineLocalTest {

    // Schema XSD consolidado NF-e 4.00 (classpath principal do módulo fiscal)
    private static final String SCHEMA_CONSOLIDADO = "xsd/custom/nfe_v4.00_consolidado.xsd";

    // Fixture enviNFe validada pelo XsdValidatorTest (classpath de teste)
    private static final String FIXTURE_ENVINFE = "xml/mockEnviNFe.xml";

    // Fixture bare <NFe> sem enviNFe — extrato do inner <NFe> de mockEnviNFe.xml (sem Signature)
    private static final String FIXTURE_NFE_BARE = "xml/nfe-bare-unsigned.xml";

    // Certificado de teste PKCS12 — isolado do A1 real (classpath de teste)
    private static final String CERT_TESTE_PATH = "cert/test-cert.pfx";
    private static final String CERT_TESTE_TYPE = "PKCS12";

    /**
     * XML bare NFe não assinado para uso exclusivo nos testes de assinatura XMLDSIG.
     * Dados fictícios — CNPJ/CPF são sequências de teste sem validade fiscal.
     * Não contém bloco <Signature> — exigido como input pelo AssinaturaXmlService.
     * Ambiente homologação (tpAmb=2).
     */
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

    // =========================================================================
    // TESTE 1 — Validação XSD local sem SOAP
    // =========================================================================

    @Test
    @DisplayName("Deve validar mockEnviNFe.xml contra XSD consolidado NF-e 4.00 sem SOAP")
    void deveValidarXsdLocalSemSoap() throws Exception {

        // Carrega fixture do classpath de teste sem conexão de rede
        ClassPathResource resource = new ClassPathResource(FIXTURE_ENVINFE);
        assertTrue(resource.exists(),
                "Fixture '" + FIXTURE_ENVINFE + "' não encontrada no classpath de teste.");

        // Parse com hardening XXE (DocumentBuilderFactory — padrão do projeto)
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

        // Validação sem SOAP, sem SEFAZ, sem rede
        assertDoesNotThrow(
                () -> validator.validate(xmlDoc, SCHEMA_CONSOLIDADO),
                "O XML '" + FIXTURE_ENVINFE + "' deve ser válido conforme o schema NF-e 4.00."
        );
    }

    // =========================================================================
    // TESTE 2 — Assinatura XMLDSIG RSA-SHA256 sem SOAP
    // =========================================================================

    @Test
    @DisplayName("Deve assinar XML NF-e com RSA-SHA256 sem SOAP e sem certificado A1 real")
    void deveAssinarXmlLocalSemSoap() throws Exception {

        // Inicializa CertificadoServiceImpl com o certificado de teste apenas.
        // @Value não é injetado fora de contexto Spring — ReflectionTestUtils injeta nos campos privados.
        CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();
        ReflectionTestUtils.setField(certificadoService, "certPath",     CERT_TESTE_PATH);
        ReflectionTestUtils.setField(certificadoService, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(certificadoService, "certType",     CERT_TESTE_TYPE);
        certificadoService.init(); // equivalente ao @PostConstruct — carrega KeyStore e SSLContext

        AssinaturaXmlService assinaturaService = new AssinaturaXmlService(certificadoService);

        // Assina XML bare NFe que não possui Signature existente
        String xmlAssinado = assinaturaService.assinar(XML_NFE_NAO_ASSINADO);

        // --- Assertivas estruturais ---
        assertNotNull(xmlAssinado,
                "O XML assinado não pode ser nulo.");
        assertFalse(xmlAssinado.isBlank(),
                "O XML assinado não pode estar vazio.");

        // Blocos XMLDSIG obrigatórios pela NF-e 4.00
        assertTrue(xmlAssinado.contains("<Signature"),
                "O XML assinado deve conter o bloco <Signature> (XMLDSIG envelopado).");
        assertTrue(xmlAssinado.contains("SignedInfo"),
                "O XML assinado deve conter o elemento <SignedInfo>.");
        assertTrue(xmlAssinado.contains("DigestValue"),
                "O XML assinado deve conter o elemento <DigestValue>.");
        assertTrue(xmlAssinado.contains("SignatureValue"),
                "O XML assinado deve conter o elemento <SignatureValue>.");

        // Algoritmo obrigatório: RSA-SHA256 (NF-e 4.00 — AssinaturaXmlService.SIGN_RSA_SHA256)
        assertTrue(xmlAssinado.contains("rsa-sha256"),
                "A assinatura deve usar RSA-SHA256 conforme NF-e 4.00.");
        assertTrue(xmlAssinado.contains("xmlenc#sha256"),
                "O digest deve usar SHA-256 conforme NF-e 4.00.");

        // Ausência de algoritmos obsoletos — garantia contra regressão para SHA-1
        assertFalse(xmlAssinado.contains("xmldsig#rsa-sha1"),
                "A assinatura NÃO deve usar RSA-SHA1 (obsoleto para NF-e 4.00).");
        assertFalse(xmlAssinado.contains("xmldsig#sha1"),
                "O digest NÃO deve usar SHA-1 (obsoleto para NF-e 4.00).");
    }

    // =========================================================================
    // TESTE 3 — Cadeia local pré-transmissão completa sem SOAP
    // =========================================================================

    @Test
    @DisplayName("Deve executar a cadeia local pré-transmissão (XSD + assinatura) sem SOAP e sem SEFAZ")
    void deveExecutarCadeiaLocalPreTransmissaoSemSoap() throws Exception {

        /*
         * Este teste valida apenas a cadeia local pré-transmissão.
         * A transmissão SOAP/SEFAZ é omitida intencionalmente.
         *
         * Cadeia validada neste teste:
         *   [1] Parse + Validação XSD  → mockEnviNFe.xml / nfe_v4.00_consolidado.xsd
         *   [2] Assinatura XMLDSIG     → AssinaturaXmlService / RSA-SHA256 / cert de teste
         *   [3] Assertivas de pipeline → estrutura do XML assinado confirmada
         *
         * Ponto de corte intencional:
         *   NfeTransmitService.transmitirXml() NÃO é chamado.
         *   Nenhuma conexão HTTPS ou SOAP é aberta.
         *   O endpoint POST /api/fiscal/nfe/enviar deve ser testado apenas
         *   após aprovação explícita e validação completa do pipeline local.
         */

        // -----------------------------------------------------------------
        // [1] Validação XSD — mockEnviNFe.xml vs nfe_v4.00_consolidado.xsd
        // -----------------------------------------------------------------
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

        // -----------------------------------------------------------------
        // [2] Assinatura XMLDSIG — bare NFe + cert de teste
        // -----------------------------------------------------------------
        CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();
        ReflectionTestUtils.setField(certificadoService, "certPath",     CERT_TESTE_PATH);
        ReflectionTestUtils.setField(certificadoService, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(certificadoService, "certType",     CERT_TESTE_TYPE);
        certificadoService.init();

        AssinaturaXmlService assinaturaService = new AssinaturaXmlService(certificadoService);
        String xmlAssinado = assinaturaService.assinar(XML_NFE_NAO_ASSINADO);

        // -----------------------------------------------------------------
        // [3] Assertivas do pipeline pré-transmissão
        // -----------------------------------------------------------------
        assertNotNull(xmlAssinado,
                "[Etapa 2] XML assinado é nulo — assinatura falhou.");
        assertTrue(xmlAssinado.contains("<Signature"),
                "[Etapa 2] Bloco <Signature> ausente — assinatura não foi aplicada.");
        assertTrue(xmlAssinado.contains("rsa-sha256"),
                "[Etapa 2] Algoritmo RSA-SHA256 não encontrado no XML assinado.");
        assertTrue(xmlAssinado.contains("DigestValue"),
                "[Etapa 2] <DigestValue> ausente no XML assinado.");
        assertTrue(xmlAssinado.contains("SignatureValue"),
                "[Etapa 2] <SignatureValue> ausente no XML assinado.");
        assertFalse(xmlAssinado.contains("xmldsig#rsa-sha1"),
                "[Etapa 2] RSA-SHA1 detectado — algoritmo obsoleto não permitido.");

        // Ponto de corte explícito — NfeTransmitService não é instanciado nem chamado
    }

    // =========================================================================
    // TESTE 4 — Rejeição de <enviNFe> como input (P0 guard)
    // =========================================================================

    @Test
    @DisplayName("Deve rejeitar input <enviNFe> com IllegalArgumentException antes de qualquer transmissão")
    void deveRejeitarEnviNFeComoInput() {

        /*
         * Valida que NfeOrquestradorService.processar() rejeita qualquer XML
         * cujo elemento raiz não seja <NFe>.
         * O guard deve disparar ANTES de XSD, assinatura e transmissão.
         * NfeTransmitService.transmitirXml() NÃO deve ser chamado.
         */

        // Dependências mock — não serão chamadas se o guard funcionar corretamente
        XsdValidator xsdValidator        = new XsdValidator();
        AssinaturaXmlService mockAssina  = Mockito.mock(AssinaturaXmlService.class);
        NfeTransmitService   mockTransmit = Mockito.mock(NfeTransmitService.class);
        EmitenteProperties   emitente    = new EmitenteProperties();

        NfeOrquestradorService orquestrador = new NfeOrquestradorService(
                xsdValidator, mockAssina, mockTransmit, emitente);

        // Input inválido: raiz <enviNFe> com <NFe> interno (shape que NÃO deve ser aceito)
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

        // Deve lançar IllegalArgumentException — NÃO RuntimeException de SEFAZ/SSL
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> orquestrador.processar(xmlEnviNFe, "12345678000195"),
                "NfeOrquestradorService deve rejeitar <enviNFe> com IllegalArgumentException."
        );

        // Mensagem deve documentar o contrato correto sem expor dados sensíveis
        assertTrue(ex.getMessage().contains("NFe"),
                "A mensagem de erro deve indicar que o elemento raiz esperado é <NFe>.");
        assertTrue(ex.getMessage().contains("elemento raiz"),
                "A mensagem de erro deve mencionar 'elemento raiz' para orientar o chamador.");

        // NfeTransmitService nunca deve ser invocado — guard deve ocorrer antes
        Mockito.verify(mockTransmit, Mockito.never())
               .transmitirXml(
                       Mockito.anyString(),
                       Mockito.anyString(),
                       Mockito.anyString(),
                       Mockito.anyInt());
    }

    // =========================================================================
    // TESTE 5 — Confirma posição de <Signature> dentro de <NFe> (não como irmão)
    // =========================================================================

    @Test
    @DisplayName("Deve confirmar que <Signature> é filho direto de <NFe>, não irmão de <NFe>")
    void deveConfirmarAssinaturaDentroDeNFe() throws Exception {

        /*
         * Valida a posição estrutural correta do bloco <Signature> após assinatura.
         *
         * Estrutura CORRETA esperada pela SEFAZ (NF-e 4.00):
         *   <NFe>
         *     <infNFe Id="...">...</infNFe>
         *     <Signature>...</Signature>   ← filho de <NFe> ✓
         *   </NFe>
         *
         * Estrutura INCORRETA que ocorreria se o input fosse <enviNFe>:
         *   <enviNFe>
         *     <NFe>...</NFe>
         *     <Signature>...</Signature>   ← irmão de <NFe>, fora do contexto ✗
         *   </enviNFe>
         *
         * Este teste confirma que o contrato <NFe> bare produz a estrutura correta.
         */

        // Setup certificado de teste — mesma estratégia dos testes anteriores
        CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();
        ReflectionTestUtils.setField(certificadoService, "certPath",     CERT_TESTE_PATH);
        ReflectionTestUtils.setField(certificadoService, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(certificadoService, "certType",     CERT_TESTE_TYPE);
        certificadoService.init();

        AssinaturaXmlService assinaturaService = new AssinaturaXmlService(certificadoService);

        // Assina o XML bare <NFe>
        String xmlAssinado = assinaturaService.assinar(XML_NFE_NAO_ASSINADO);
        assertNotNull(xmlAssinado, "XML assinado não pode ser nulo.");

        // Parse do resultado para verificação estrutural via DOM
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        Document signedDoc;
        try (var stream = new ByteArrayInputStream(xmlAssinado.getBytes(StandardCharsets.UTF_8))) {
            signedDoc = factory.newDocumentBuilder().parse(stream);
        }

        // --- Assertiva 1: raiz do documento assinado deve ser <NFe> ---
        assertEquals("NFe", signedDoc.getDocumentElement().getLocalName(),
                "O elemento raiz do XML assinado deve ser <NFe>.");

        // --- Assertiva 2: <Signature> deve existir exatamente uma vez ---
        NodeList signatures = signedDoc.getElementsByTagNameNS(
                "http://www.w3.org/2000/09/xmldsig#", "Signature");
        assertEquals(1, signatures.getLength(),
                "O XML assinado deve conter exatamente um elemento <Signature>.");

        // --- Assertiva 3: pai de <Signature> deve ser <NFe> ---
        Node signatureNode = signatures.item(0);
        Node parentNode    = signatureNode.getParentNode();

        assertEquals("NFe", parentNode.getLocalName(),
                "<Signature> deve ser filho direto de <NFe>, " +
                "mas está sob <" + parentNode.getLocalName() + ">. " +
                "Isso indicaria que o input foi <enviNFe> em vez de <NFe> bare.");

        // --- Assertiva 4: <infNFe> e <Signature> são irmãos sob <NFe> ---
        NodeList nfeChildren = signedDoc.getDocumentElement().getChildNodes();
        boolean foundInfNFe    = false;
        boolean foundSignature = false;

        for (int i = 0; i < nfeChildren.getLength(); i++) {
            Node child = nfeChildren.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if ("infNFe".equals(child.getLocalName()))    foundInfNFe    = true;
            if ("Signature".equals(child.getLocalName())) foundSignature = true;
        }

        assertTrue(foundInfNFe,
                "Elemento <infNFe> não encontrado como filho direto de <NFe>.");
        assertTrue(foundSignature,
                "Elemento <Signature> não encontrado como filho direto de <NFe>.");
    }

    // =========================================================================
    // TESTE 6 — Validação bare <NFe> contra schema consolidado (contrato P1)
    // =========================================================================

    @Test
    @DisplayName("Deve validar bare <NFe> contra schema consolidado NF-e 4.00 sem SOAP")
    void deveValidarNFeBareContraSchemaLocalSemSoap() throws Exception {

        /*
         * Prova executável do contrato de input do endpoint POST /api/fiscal/nfe/enviar.
         *
         * O schema nfe_v4.00_consolidado.xsd (rev. 3.1.5) declara explicitamente
         * <NFe> como elemento raiz válido para compatibilidade com Xerces (Java).
         * Este teste confirma que bare <NFe> passa a validação sem precisar de
         * envelope <enviNFe> — o envelope é responsabilidade de NfeTransmitServiceImpl.
         *
         * Cadeia validada:
         *   [1] Fixture: nfe-bare-unsigned.xml (extrato de mockEnviNFe.xml, sem enviNFe)
         *   [2] Parse namespace-aware com XXE hardening
         *   [3] Assertiva: raiz == "NFe"
         *   [4] Assertiva: sem <Signature> (não assinado)
         *   [5] XSD validation via XsdValidator — assertDoesNotThrow
         *
         * Restrições:
         *   AssinaturaXmlService NÃO é chamado.
         *   NfeTransmitService NÃO é chamado.
         *   Nenhuma conexão HTTPS/SOAP é aberta.
         */

        // Carrega fixture bare <NFe> do classpath de teste
        ClassPathResource resource = new ClassPathResource(FIXTURE_NFE_BARE);
        assertTrue(resource.exists(),
                "Fixture '" + FIXTURE_NFE_BARE + "' não encontrada no classpath de teste.");

        // Parse com hardening XXE — padrão do projeto
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

        // --- Assertiva 1: raiz deve ser <NFe> — contrato do endpoint ---
        assertEquals("NFe", xmlDoc.getDocumentElement().getLocalName(),
                "O elemento raiz da fixture deve ser <NFe>. " +
                "A fixture não deve conter wrapper <enviNFe>.");

        // --- Assertiva 2: fixture não deve ter <Signature> antes da assinatura ---
        NodeList signatures = xmlDoc.getElementsByTagNameNS(
                "http://www.w3.org/2000/09/xmldsig#", "Signature");
        assertEquals(0, signatures.getLength(),
                "A fixture '" + FIXTURE_NFE_BARE + "' não deve conter <Signature> antes da assinatura.");

        // --- Assertiva 3: bare <NFe> deve passar o schema consolidado ---
        XsdValidator validator = new XsdValidator();
        assertDoesNotThrow(
                () -> validator.validate(xmlDoc, SCHEMA_CONSOLIDADO),
                "Bare <NFe> deve ser válido conforme nfe_v4.00_consolidado.xsd. " +
                "O schema (rev. 3.1.5) declara <NFe> como elemento raiz válido."
        );
    }
}
