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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ============================================================================
 * Utilitário de validação de XMLs fiscais (NF-e 4.00) contra os schemas XSD
 * oficiais da SEFAZ. Compatível com includes/imports internos e execução via JAR.
 *
 * Versão: 1.4.0
 * Autor: Bruno Ribeiro — DevSecOps / Fiscal BR
 * ============================================================================
 */
@Component
public class XsdValidator {

    /**
     * Valida um XML contra o XSD fiscal oficial.
     *
     * @param xmlDocumento Documento XML (DOM)
     * @param xsdPath Caminho do schema principal (ex: xsd/enviNFe_v4.00.xsd)
     * @throws Exception Caso o XML não esteja conforme o schema
     */
    public void validate(Document xmlDocumento, String xsdPath) throws Exception {
        try {
            // -----------------------------------------------------------------
            // Ajustes de segurança do parser Xerces para schemas complexos da SEFAZ
            // -----------------------------------------------------------------
            System.setProperty("jdk.xml.maxOccurLimit", "10000");
            System.setProperty("jdk.xml.entityExpansionLimit", "10000");
            System.setProperty("jdk.xml.elementAttributeLimit", "10000");
            System.setProperty("jdk.xml.totalEntitySizeLimit", "10000000");

            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setResourceResolver(new ClasspathResourceResolver());

            try (InputStream schemaStream = getResourceAsStream(xsdPath)) {
                if (schemaStream == null) {
                    throw new IllegalArgumentException("Schema XSD não encontrado: " + xsdPath);
                }

                Schema schema = schemaFactory.newSchema(new StreamSource(schemaStream));
                Validator validator = schema.newValidator();

                // Garante namespace SEFAZ no elemento raiz
                xmlDocumento.getDocumentElement()
                        .setAttribute("xmlns", "http://www.portalfiscal.inf.br/nfe");

                validator.validate(new DOMSource(xmlDocumento));
                System.out.println("[XSD-VALIDATOR] XML validado com sucesso contra " + xsdPath);
            }

        } catch (SAXException e) {
            throw new Exception("Falha de conformidade XML/XSD: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new Exception("Erro ao validar XML da NF-e: " + e.getMessage(), e);
        }
    }

    /**
     * Resolve recursos XSD (includes/imports) diretamente do classpath,
     * garantindo compatibilidade com execução empacotada (JAR Docker).
     */
    private static class ClasspathResourceResolver implements LSResourceResolver {
        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
            try {
                if (systemId == null) return null;

                String cleanPath = systemId.replace("\\", "/");
                if (!cleanPath.startsWith("xsd/")) {
                    cleanPath = "xsd/" + cleanPath;
                }

                InputStream resourceAsStream = getResourceAsStream(cleanPath);
                if (resourceAsStream == null) {
                    System.err.println("[XSD-RESOLVER] Arquivo XSD não encontrado: " + cleanPath);
                    return null;
                }

                var impl = (DOMImplementationLS) DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .getDOMImplementation()
                        .getFeature("LS", "3.0");

                LSInput input = impl.createLSInput();
                input.setSystemId(cleanPath);
                input.setByteStream(resourceAsStream);
                input.setEncoding(StandardCharsets.UTF_8.name());
                return input;

            } catch (Exception e) {
                System.err.println("[XSD-RESOLVER] Falha ao resolver recurso: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Localiza um recurso dentro do classpath (compatível com execução em JAR).
     *
     * @param path Caminho relativo do recurso (ex: xsd/enviNFe_v4.00.xsd)
     * @return InputStream do arquivo, ou null se não encontrado
     */
    private static InputStream getResourceAsStream(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        return resource.exists() ? resource.getInputStream() : null;
    }
}
