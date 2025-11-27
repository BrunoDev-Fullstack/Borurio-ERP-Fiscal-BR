package br.com.borurio.fiscal.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * =============================================================================
 * COMPONENTE: XsdValidator
 * =============================================================================
 * Validação fiscal NF-e 4.00 contra schemas XSD oficiais/consolidados.
 *
 * Características:
 *  - Seguro contra XXE, DTD Injection e Path Traversal;
 *  - Suporte completo a imports/includes internos;
 *  - Compatível com execução local, JAR empacotado e Docker.
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Component
public class XsdValidator {

    /**
     * Valida XML contra XSD consolidado.
     *
     * @param xmlDocumento documento DOM
     * @param xsdPath ex: "xsd/custom/nfe_v4.00_consolidado.xsd"
     */
    public void validate(Document xmlDocumento, String xsdPath) throws Exception {

        try {
            // ==============================================================
            // HARDENING — Recomendações OWASP XML Security
            // ==============================================================
            System.setProperty("jdk.xml.maxOccurLimit", "10000");
            System.setProperty("jdk.xml.entityExpansionLimit", "10000");
            System.setProperty("jdk.xml.elementAttributeLimit", "10000");
            System.setProperty("jdk.xml.totalEntitySizeLimit", "10000000");

            SchemaFactory factory =
                    SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setResourceResolver(new ClasspathResolver());

            // ==============================================================
            // Carrega o schema
            // ==============================================================
            try (InputStream xsdStream = getResourceAsStream(xsdPath)) {

                if (xsdStream == null) {
                    throw new FileNotFoundException("Schema XSD não encontrado: " + xsdPath);
                }

                Schema schema = factory.newSchema(new StreamSource(xsdStream));
                Validator validator = schema.newValidator();

                // Namespace obrigatório para NF-e
                if (xmlDocumento.getDocumentElement().getNamespaceURI() == null) {
                    xmlDocumento.getDocumentElement()
                            .setAttribute("xmlns", "http://www.portalfiscal.inf.br/nfe");
                }

                validator.validate(new DOMSource(xmlDocumento));

                System.out.println("[XSD-VALIDATOR] XML validado com sucesso → " + xsdPath);
            }

        } catch (SAXException e) {
            throw new Exception("XML inválido segundo o schema: " + e.getMessage(), e);

        } catch (FileNotFoundException e) {
            throw e;

        } catch (Exception e) {
            throw new Exception("Erro interno ao validar XML: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // RESOLVER SEGURO — Localiza includes/imports dentro do classpath
    // =========================================================================
    private static class ClasspathResolver implements LSResourceResolver {

        @Override
        public LSInput resolveResource(
                String type,
                String namespaceURI,
                String publicId,
                String systemId,
                String baseURI
        ) {
            try {
                if (systemId == null || systemId.contains("..")) {
                    return null;
                }

                String fileName = new File(systemId).getName();

                String[] folders = {
                        "xsd/custom/",
                        "xsd/oficial/"
                };

                for (String folder : folders) {
                    String path = folder + fileName;
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

                        System.out.println("[XSD-RESOLVER] Incluído schema: " + path);
                        return input;
                    }
                }

                System.err.println("[XSD-RESOLVER] Não encontrado: " + systemId);
                return null;

            } catch (Exception ex) {
                System.err.println("[XSD-RESOLVER] Erro resolver: " + ex.getMessage());
                return null;
            }
        }
    }

    // =========================================================================
    // Busca arquivos no classpath
    // =========================================================================
    private static InputStream getResourceAsStream(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) return null;
        return new BufferedInputStream(resource.getInputStream());
    }
}
