package br.com.borurio.fiscal.utils;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * =============================================================================
 * UTILITÁRIO DE VALIDAÇÃO XML — NF-e 4.00
 * -----------------------------------------------------------------------------
 * Verifica conformidade do XML com o XSD consolidado (leiauteNFe + tiposBasico).
 * Permite execução via linha de comando (Maven exec:java).
 * -----------------------------------------------------------------------------
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Projeto: Borurio ERP Fiscal BR
 * =============================================================================
 */
public class XmlValidator {

    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("VALIDAÇÃO XML NF-e 4.00 — BORURIO ERP FISCAL BR");
        System.out.println("_______________________________________________________________");

        if (args.length < 2) {
            System.err.println("[ERRO] Uso incorreto. Sintaxe esperada:");
            System.err.println("mvn exec:java -Dexec.mainClass=\"br.com.borurio.fiscal.utils.XmlValidator\" "
                    + "-Dexec.args=\"<caminho_xml> <caminho_xsd>\"");
            System.exit(1);
        }

        String xmlPath = args[0];
        String xsdPath = args[1];

        System.out.println("XML: " + xmlPath);
        System.out.println("XSD: " + xsdPath);
        System.out.println("===============================================================");

        try {
            File xmlFile = new File(xmlPath);
            File xsdFile = new File(xsdPath);

            // Parser seguro
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            // Libera acesso a schemas locais (bloqueado por padrão no Java 17+)
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "all");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "all");

            DocumentBuilder db = dbf.newDocumentBuilder();
            Document xmlDoc = db.parse(xmlFile);

            // Cria SchemaFactory
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "all");
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "all");

            Schema schema = schemaFactory.newSchema(xsdFile);
            Validator validator = schema.newValidator();

            validator.validate(new DOMSource(xmlDoc));

            System.out.println("[OK] XML validado com sucesso contra o schema consolidado.");
        } catch (SAXException e) {
            System.err.println("[ERRO] Falha de conformidade XML/XSD: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[ERRO] Falha de leitura: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[ERRO] Erro inesperado: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        System.out.println("---------------------------------------------------------------");
        System.out.println("Data/Hora: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        System.out.println("Charset: " + StandardCharsets.UTF_8.displayName());
        System.out.println("===============================================================");
    }
}
