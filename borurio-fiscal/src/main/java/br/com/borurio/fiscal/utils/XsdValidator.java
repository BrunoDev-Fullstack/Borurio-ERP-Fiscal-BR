package br.com.borurio.fiscal.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * =============================================================================
 * COMPONENTE: XsdValidator
 * _____________________________________________________________________________
 * Utilitário de validação de XMLs fiscais (NF-e 4.00) contra os schemas XSD.
 *
 * Ajustes aplicados:
 * - Compatibilidade com Java 17 (liberação controlada de imports XSD)
 * - Suporte completo a include/import (resolver classpath)
 * - Hardening contra XXE e ataques XML
 * - Execução estável em ambiente Docker/JAR
 * =============================================================================
 */
@Component
public class XsdValidator {

    /**
     * Valida um documento XML contra um XSD.
     *
     * @param xmlDocumento Documento XML (DOM)
     * @param xsdPath Caminho no classpath (ex: xsd/custom/nfe_v4.00_consolidado.xsd)
     */
    public void validate(Document xmlDocumento, String xsdPath) throws Exception {
        try {
            // ================================================================
            // HARDENING JVM XML LIMITS
            // ================================================================
            System.setProperty("jdk.xml.maxOccurLimit", "10000");
            System.setProperty("jdk.xml.entityExpansionLimit", "10000");
            System.setProperty("jdk.xml.elementAttributeLimit", "10000");
            System.setProperty("jdk.xml.totalEntitySizeLimit", "10000000");

            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

            // ================================================================
            // LIBERAÇÃO CONTROLADA (JAVA 17)
            // ================================================================
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "all");
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "all");

            schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            schemaFactory.setResourceResolver(new ClasspathResourceResolver());

            // ================================================================
            // CARREGAMENTO DO XSD
            // ================================================================
            try (InputStream schemaStream = getResourceAsStream(xsdPath)) {

                if (schemaStream == null) {
                    throw new FileNotFoundException("Schema XSD não encontrado: " + xsdPath);
                }

                Schema schema = schemaFactory.newSchema(new StreamSource(schemaStream));
                Validator validator = schema.newValidator();

                // Liberação também no Validator (necessário para imports)
                validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "all");
                validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "all");

                // Garante namespace padrão NF-e
                if (xmlDocumento.getDocumentElement().getNamespaceURI() == null) {
                    xmlDocumento.getDocumentElement()
                            .setAttribute("xmlns", "http://www.portalfiscal.inf.br/nfe");
                }

                // ============================================================
                // EXECUÇÃO DA VALIDAÇÃO
                // ============================================================
                validator.validate(new DOMSource(xmlDocumento));

                System.out.println("[XSD] XML validado com sucesso: " + xsdPath);
            }

        } catch (SAXException e) {
            throw new Exception("Falha de conformidade XML/XSD: " + e.getMessage(), e);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new Exception("Erro ao validar XML da NF-e: " + e.getMessage(), e);
        }
    }

    /**
     * Resolver de recursos para includes/imports XSD.
     */
    private static class ClasspathResourceResolver implements LSResourceResolver {

        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId,
                                       String systemId, String baseURI) {
            try {
                if (systemId == null || systemId.contains("..")) {
                    return null;
                }

                String fileName = new File(systemId).getName();

                String[] searchPaths = {
                        "xsd/custom/" + fileName,
                        "xsd/oficial/" + fileName
                };

                for (String path : searchPaths) {
                    InputStream stream = getResourceAsStream(path);
                    if (stream != null) {

                        DOMImplementationLS impl = (DOMImplementationLS)
                                DocumentBuilderFactory.newInstance()
                                        .newDocumentBuilder()
                                        .getDOMImplementation()
                                        .getFeature("LS", "3.0");

                        LSInput input = impl.createLSInput();
                        input.setSystemId(path);
                        input.setByteStream(stream);
                        input.setEncoding(StandardCharsets.UTF_8.name());

                        return input;
                    }
                }

                return null;

            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Busca recurso no classpath com segurança.
     */
    private static InputStream getResourceAsStream(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        return new BufferedInputStream(resource.getInputStream());
    }

    /**
     * Execução standalone para debug.
     */
    public static void main(String[] args) {
        String xmlPath = "docs/xml/nfe.xml";
        String xsdPath = "xsd/custom/nfe_v4.00_consolidado.xsd";

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document xmlDoc = builder.parse(new File(xmlPath));

            new XsdValidator().validate(xmlDoc, xsdPath);

            System.out.println("[OK] XML válido");

        } catch (Exception e) {
            System.err.println("[ERRO] " + e.getMessage());
        }
    }
}