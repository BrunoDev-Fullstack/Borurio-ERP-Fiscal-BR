package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.builder.NfeXmlBuilder;
import br.com.borurio.fiscal.domain.nfe.*;
import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static br.com.borurio.fiscal.support.TestResourceSupport.assumeTestCertificateAvailable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SVC Fase 2 (18-08-2026), item 9 do plano — validação de &lt;ide&gt; (incluindo dhCont/xJust)
 * contra o XSD OFICIAL da SEFAZ (xsd/oficial/), não o schema custom de produção
 * (xsd/custom/nfe_v4.00_consolidado.xsd), que declara infNFe como xs:anyType e por isso não
 * valida a estrutura interna de &lt;ide&gt; em lugar nenhum.
 *
 * Investigação prévia (plano, item 9): xsd/oficial/nfe_v4.00.xsd já usa xs:import (não
 * xs:include) para o namespace xmldsig, e xsd/oficial/leiauteNFe_v4.00.xsd idem — o conflito de
 * namespace que motivou o leiaute "-ajustado" em xsd/custom/ não se reproduz aqui. Resolução via
 * XsdValidator.ClasspathResourceResolver (mesmo mecanismo já em produção) resolveu os includes/
 * imports oficiais de primeira, sem necessidade de infraestrutura de teste nova — zero rede, zero
 * download, XSD de produção intocado.
 */
class XsdOficialIdeTest {

    private static final String SCHEMA_OFICIAL = "xsd/oficial/nfe_v4.00.xsd";
    private static final String CERT_TESTE_PATH = "cert/test-cert.pfx";
    private static final String CERT_TESTE_TYPE = "PKCS12";

    private NFe nfeCompleta(String tpEmis, String dhCont, String xJust) {
        Ide ide = new Ide();
        ide.setCUF("35");
        ide.setCNF("12345678");
        ide.setNatOp("VENDA DE MERCADORIA");
        ide.setSerie("1");
        ide.setNNF("1");
        ide.setDhEmi("2026-08-18T10:00:00-03:00");
        ide.setTpNF("1");
        ide.setIdDest("1");
        ide.setCMunFG("3550308");
        ide.setTpImp("1");
        ide.setTpEmis(tpEmis);
        ide.setCDV("0");
        ide.setTpAmb("2");
        ide.setFinNFe("1");
        ide.setIndFinal("1");
        ide.setIndPres("9");
        ide.setIndIntermed("0");
        ide.setProcEmi("0");
        ide.setVerProc("1.0.0");
        ide.setDhCont(dhCont);
        ide.setXJust(xJust);

        Emit emit = new Emit();
        emit.setCnpj("22418179000134");
        emit.setXNome("EMITENTE TESTE XSD OFICIAL");
        emit.setIe("123456789");
        emit.setCrt("1");
        EnderEmit enderEmit = new EnderEmit();
        enderEmit.setXLgr("Rua Teste");
        enderEmit.setNro("100");
        enderEmit.setXBairro("Centro");
        enderEmit.setCMun("3550308");
        enderEmit.setXMun("São Paulo");
        enderEmit.setUF("SP");
        enderEmit.setCEP("01000000");
        enderEmit.setCPais("1058");
        enderEmit.setXPais("Brasil");
        emit.setEnderEmit(enderEmit);

        Dest dest = new Dest();
        dest.setCpfCnpj("12345678000195");
        dest.setXNome("NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL");
        dest.setIndIEDest("9");

        Produto prod = new Produto();
        prod.setCProd("0001");
        prod.setXProd("PRODUTO TESTE");
        prod.setNCM("61091000");
        prod.setCFOP("5102");
        prod.setUCom("UN");
        prod.setQCom("1.0000");
        prod.setVUnCom("10.0000000000");
        prod.setVProd("10.00");

        Det det = new Det();
        det.setNItem(1);
        det.setProd(prod);

        Total total = new Total();
        total.setVProd("10.00");
        total.setVNF("10.00");

        InfNFe inf = new InfNFe();
        inf.setId("NFe35260422418179000134550010000000011000000010");
        inf.setIde(ide);
        inf.setEmit(emit);
        inf.setDest(dest);
        inf.setDet(List.of(det));
        inf.setTotal(total);

        NFe nfe = new NFe();
        nfe.setInfNFe(inf);
        return nfe;
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (var input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private String assinar(String xmlNaoAssinado) throws Exception {
        CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();
        ReflectionTestUtils.setField(certificadoService, "certPath", CERT_TESTE_PATH);
        ReflectionTestUtils.setField(certificadoService, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(certificadoService, "certType", CERT_TESTE_TYPE);
        certificadoService.init();

        AssinaturaXmlService assinaturaService = new AssinaturaXmlService(certificadoService);
        return assinaturaService.assinar(xmlNaoAssinado);
    }

    @Test
    @DisplayName("XSD oficial: NORMAL (tpEmis=1, sem dhCont/xJust) assinado é válido contra leiauteNFe_v4.00.xsd oficial")
    void normal_assinado_validoContraXsdOficial() throws Exception {
        assumeTestCertificateAvailable();

        String xmlNaoAssinado = new NfeXmlBuilder().build(nfeCompleta("1", null, null), ModalidadeFrete.CONTA_TERCEIROS);
        String xmlAssinado = assinar(xmlNaoAssinado);

        Document doc = parseXml(xmlAssinado);
        XsdValidator validator = new XsdValidator();
        assertDoesNotThrow(() -> validator.validate(doc, SCHEMA_OFICIAL),
                "NF-e NORMAL assinada deve ser válida contra o XSD oficial da SEFAZ.");
    }

    @Test
    @DisplayName("XSD oficial: SVC-AN (tpEmis=6, com dhCont/xJust) assinado é válido contra leiauteNFe_v4.00.xsd oficial")
    void svcAn_assinado_validoContraXsdOficial() throws Exception {
        assumeTestCertificateAvailable();

        String xmlNaoAssinado = new NfeXmlBuilder().build(
                nfeCompleta("6", "2026-08-18T10:05:00-03:00", "Justificativa de contingencia SVC-AN para teste de XSD oficial."),
                ModalidadeFrete.CONTA_TERCEIROS);
        String xmlAssinado = assinar(xmlNaoAssinado);

        Document doc = parseXml(xmlAssinado);
        XsdValidator validator = new XsdValidator();
        assertDoesNotThrow(() -> validator.validate(doc, SCHEMA_OFICIAL),
                "NF-e SVC-AN assinada (com dhCont/xJust) deve ser válida contra o XSD oficial da SEFAZ.");
    }

    @Test
    @DisplayName("XSD oficial: SVC-RS (tpEmis=7, com dhCont/xJust) assinado é válido contra leiauteNFe_v4.00.xsd oficial")
    void svcRs_assinado_validoContraXsdOficial() throws Exception {
        assumeTestCertificateAvailable();

        String xmlNaoAssinado = new NfeXmlBuilder().build(
                nfeCompleta("7", "2026-08-18T10:05:00-03:00", "Justificativa de contingencia SVC-RS para teste de XSD oficial."),
                ModalidadeFrete.CONTA_TERCEIROS);
        String xmlAssinado = assinar(xmlNaoAssinado);

        Document doc = parseXml(xmlAssinado);
        XsdValidator validator = new XsdValidator();
        assertDoesNotThrow(() -> validator.validate(doc, SCHEMA_OFICIAL),
                "NF-e SVC-RS assinada (com dhCont/xJust) deve ser válida contra o XSD oficial da SEFAZ.");
    }

    @Test
    @DisplayName("XSD oficial: XML sem <Signature> é rejeitado (ds:Signature é elemento obrigatório em TNFe no schema oficial)")
    void semAssinatura_rejeitadoPeloXsdOficial() throws Exception {
        String xmlNaoAssinado = new NfeXmlBuilder().build(nfeCompleta("1", null, null), ModalidadeFrete.CONTA_TERCEIROS);

        Document doc = parseXml(xmlNaoAssinado);
        XsdValidator validator = new XsdValidator();
        assertThrows(Exception.class, () -> validator.validate(doc, SCHEMA_OFICIAL),
                "XML sem <Signature> deve falhar no XSD oficial (diferente do schema custom de produção, "
                        + "que não exige assinatura para validar).");
    }
}
