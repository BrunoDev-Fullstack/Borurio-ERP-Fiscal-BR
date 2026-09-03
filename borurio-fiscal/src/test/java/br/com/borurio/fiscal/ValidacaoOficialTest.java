package br.com.borurio.fiscal;

import br.com.borurio.fiscal.utils.XsdValidator;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.fail;

public class ValidacaoOficialTest {

    @Test
    public void validarXmlContraXsdOficial() throws Exception {
        File f = new File("target/nfe_curr.txt");
        if (!f.exists()) { System.out.println("[SKIP] target/nfe_curr.txt ausente"); return; }

        String nfeXml = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();

        DocumentBuilderFactory nf = DocumentBuilderFactory.newInstance();
        nf.setNamespaceAware(true);
        nf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        nf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        nf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        // 1. Validar NFe isolada
        Document docNfe = nf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(nfeXml.getBytes(StandardCharsets.UTF_8)));
        XsdValidator validator = new XsdValidator();
        try {
            validator.validate(docNfe, "xsd/oficial/nfe_v4.00.xsd");
            System.out.println("=== NFe: XML VALIDO CONTRA XSD OFICIAL ===");
        } catch (Exception e) {
            System.out.println("=== NFe ERRO XSD: " + e.getMessage() + " ===");
            fail("NFe inválida: " + e.getMessage());
        }

        // 2. Validar enviNFe completo (como o SEFAZ recebe)
        String enviNFeXml =
            "<enviNFe versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">" +
            "<idLote>123456789012345</idLote>" +
            "<indSinc>1</indSinc>" +
            nfeXml +
            "</enviNFe>";

        Document docEnvi = nf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(enviNFeXml.getBytes(StandardCharsets.UTF_8)));
        try {
            validator.validate(docEnvi, "xsd/oficial/enviNFe_v4.00.xsd");
            System.out.println("=== enviNFe: XML VALIDO CONTRA XSD OFICIAL ===");
        } catch (Exception e) {
            System.out.println("=== enviNFe ERRO XSD: " + e.getMessage() + " ===");
            fail("enviNFe inválida: " + e.getMessage());
        }
    }
}
