package br.com.borurio.fiscal;

import br.com.borurio.fiscal.builder.NfeXmlBuilder;
import br.com.borurio.fiscal.domain.nfe.*;
import br.com.borurio.fiscal.utils.XsdValidator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NfeXmlBuilderTest {

    private static final String SCHEMA = "xsd/custom/nfe_v4.00_consolidado.xsd";

    @Test
    @DisplayName("Deve gerar XML NF-e CRT=1 (Simples Nacional) válido contra XSD e sem zeros à esquerda em serie/nNF")
    public void deveGerarXmlNFeValidoXsd() throws Exception {

        // ---- IDE ----
        Ide ide = new Ide();
        ide.setCUF("35");
        ide.setCNF("00000001");
        ide.setNatOp("VENDA DE MERCADORIA");
        // mod = "55" default na classe Ide
        ide.setSerie("1");      // TSerie: sem zeros à esquerda
        ide.setNNF("1");        // TNF: sem zeros à esquerda
        ide.setDhEmi("2026-05-04T10:00:00-03:00");
        ide.setTpNF("1");
        ide.setIdDest("1");
        ide.setCMunFG("3550308");
        ide.setTpImp("1");
        ide.setTpEmis("1");
        ide.setCDV("0");
        ide.setTpAmb("2");
        ide.setFinNFe("1");
        ide.setIndFinal("0");
        ide.setIndPres("9");
        ide.setProcEmi("0");
        ide.setVerProc("1.0.0");

        // ---- EMIT ----
        Emit emit = new Emit();
        emit.setCnpj("54393421000159");
        emit.setXNome("BORURIO ERP TESTE LTDA");
        emit.setIe("123456789");
        emit.setCrt("1");

        EnderEmit ender = new EnderEmit();
        ender.setXLgr("Rua das Flores");
        ender.setNro("100");
        ender.setXBairro("Centro");
        ender.setCMun("3550308");
        ender.setXMun("SAO PAULO");
        ender.setUF("SP");
        ender.setCEP("01000000");
        ender.setCPais("1058");
        ender.setXPais("Brasil");
        emit.setEnderEmit(ender);

        // ---- DEST (CPF, não contribuinte) ----
        Dest dest = new Dest();
        dest.setCpfCnpj("12345678901");
        dest.setXNome("CONSUMIDOR FINAL TESTE");
        dest.setIndIEDest("9");
        // sem IE — indIEDest=9 dispensa o elemento

        // ---- DET ----
        Produto prod = new Produto();
        prod.setCProd("0001");
        prod.setXProd("PRODUTO TESTE SIMPLES NACIONAL");
        prod.setNCM("61091000");
        prod.setCFOP("5102");
        prod.setUCom("UN");
        prod.setQCom("1.0000");
        prod.setVUnCom("10.0000000000");
        prod.setVProd("10.00");

        Det det = new Det();
        det.setNItem(1);
        det.setProd(prod);

        // ---- TOTAL ----
        Total total = new Total();
        total.setVProd("10.00");
        total.setVNF("10.00");

        // ---- NFe ----
        InfNFe inf = new InfNFe();
        inf.setId("NFe35260554393421000159550010000000011000000010");
        inf.setIde(ide);
        inf.setEmit(emit);
        inf.setDest(dest);
        inf.setDet(List.of(det));
        inf.setTotal(total);

        NFe nfe = new NFe();
        nfe.setInfNFe(inf);

        // ---- Gerar XML ----
        NfeXmlBuilder builder = new NfeXmlBuilder();
        String xml = builder.build(nfe);

        assertNotNull(xml, "XML gerado não pode ser nulo.");
        assertFalse(xml.isBlank(), "XML gerado não pode estar vazio.");

        // Estrutura SEFAZ: TSerie e TNF não admitem zeros à esquerda (fix/sefaz-xml-structure)
        assertTrue(xml.contains("<serie>1</serie>"),
                "TSerie deve ser '1' sem zeros à esquerda, mas o XML contém: " +
                xml.lines().filter(l -> l.contains("<serie>")).findFirst().orElse("(não encontrado)"));
        assertTrue(xml.contains("<nNF>1</nNF>"),
                "TNF deve ser '1' sem zeros à esquerda, mas o XML contém: " +
                xml.lines().filter(l -> l.contains("<nNF>")).findFirst().orElse("(não encontrado)"));

        // CRT=1 → ICMSSN102 (CSOSN 400); nunca ICMSxx (CST)
        assertTrue(xml.contains("ICMSSN102"), "CRT=1 deve gerar ICMSSN102, não ICMSxx.");
        assertTrue(xml.contains("<CSOSN>400</CSOSN>"), "CSOSN esperado é 400 (não tributado SN).");
        assertFalse(xml.contains("ICMS00"), "CRT=1 não deve gerar ICMS00 (regime normal).");

        // Validação XSD — guarda de regressão estrutural
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder docBuilder = factory.newDocumentBuilder();
        Document doc;
        try (var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            doc = docBuilder.parse(stream);
        }

        XsdValidator validator = new XsdValidator();
        assertDoesNotThrow(
                () -> validator.validate(doc, SCHEMA),
                "XML gerado pelo NfeXmlBuilder deve ser válido conforme nfe_v4.00_consolidado.xsd."
        );
    }
}
