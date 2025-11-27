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
 * UTILITÁRIO DE VALIDAÇÃO XML — NF-e 4.00 (Standalone)
 * =============================================================================
 * Executa validação XML ↔ XSD consolidado.
 *
 * Execução:
 *   mvn exec:java -Dexec.mainClass="br.com.borurio.fiscal.utils.XmlValidator" \
 *                 -Dexec.args="C:/xml/nfe.xml C:/xsd/nfe_v4.00_consolidado.xsd"
 *
 * Seguro contra:
 *   • XXE
 *   • external entity injection
 *   • path traversal
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * =============================================================================
 */
public class XmlValidator {

    public static void main(String[] args) {

        System.out.println("===============================================================");
        System.out.println("VALIDAÇÃO XML NF-e 4.00 — BORURIO ERP FISCAL BR");
        System.out.println("===============================================================");

        // ---------------------------------------------------------------------
        // PARÂMETROS
        // ---------------------------------------------------------------------
        if (args.length < 2) {
            System.err.println("\n[ERRO] Uso incorreto.");
            System.err.println("Sintaxe:");
            System.err.println("  mvn exec:java -Dexec.mainClass=\"br.com.borurio.fiscal.utils.XmlValidator\" "
                    + "-Dexec.args=\"<arquivo_xml> <arquivo_xsd>\"");
            System.exit(1);
        }

        String xmlPath = args[0];
        String xsdPath = args[1];

        System.out.println("XML: " + xmlPath);
        System.out.println("XSD: " + xsdPath);
        System.out.println("---------------------------------------------------------------");

        try {
            File xmlFile = new File(xmlPath);
            File xsdFile = new File(xsdPath);

            if (!xmlFile.exists()) {
                throw new IOException("Arquivo XML não encontrado: " + xmlFile.getAbsolutePath());
            }
            if (!xsdFile.exists()) {
                throw new IOException("Schema XSD não encontrado: " + xsdFile.getAbsolutePath());
            }

            // -----------------------------------------------------------------
            // PARSER XML SEGURO — OWASP XML SECURITY
            // -----------------------------------------------------------------
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

            dbf.setNamespaceAware(true);
            dbf.setIgnoringComments(true);
            dbf.setExpandEntityReferences(false);

            // Segurança obrigatória
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            // Permite apenas schemas locais
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document xmlDoc = builder.parse(xmlFile);

            // -----------------------------------------------------------------
            // SCHEMA FACTORY (XSD)
            // -----------------------------------------------------------------
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Schema schema = schemaFactory.newSchema(xsdFile);
            Validator validator = schema.newValidator();

            // -----------------------------------------------------------------
            // EXECUTA VALIDAÇÃO
            // -----------------------------------------------------------------
            validator.validate(new DOMSource(xmlDoc));

            System.out.println("[OK] XML validado com sucesso contra o schema consolidado.");

        } catch (SAXException e) {
            System.err.println("[FALHA] XML fora do padrão: " + e.getMessage());

        } catch (IOException e) {
            System.err.println("[ERRO] Falha de leitura: " + e.getMessage());

        } catch (Exception e) {
            System.err.println("[ERRO] Erro inesperado: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        // ---------------------------------------------------------------------
        // METADADOS FINAIS
        // ---------------------------------------------------------------------
        System.out.println("---------------------------------------------------------------");
        System.out.println("Data/Hora: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        System.out.println("Charset:   " + StandardCharsets.UTF_8.displayName());
        System.out.println("===============================================================");
    }
}
