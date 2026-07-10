package br.com.borurio.fiscal.utils;

import br.com.borurio.fiscal.exception.XmlSchemaValidationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.nio.charset.StandardCharsets;

@Component
public class XsdValidator {

    /**
     * Valida um documento XML utilizando um XSD fornecido via InputStream.
     * Permite resolução de dependências locais (includes/imports) via classpath.
     */
    public void validate(Document xmlDocumento, InputStream xsdStream) throws Exception {

        try {

            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

            schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");

            schemaFactory.setResourceResolver(new ClasspathResourceResolver());

            if (xsdStream == null) {
                throw new FileNotFoundException("Stream do XSD não pode ser nulo.");
            }

            Schema schema = schemaFactory.newSchema(new StreamSource(xsdStream));

            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");

            validator.validate(new DOMSource(xmlDocumento));

            System.out.println("XML VALIDADO COM SUCESSO");

        } catch (SAXParseException e) {
            throw new XmlSchemaValidationException(
                    "Erro XSD na linha " + e.getLineNumber() +
                            ", coluna " + e.getColumnNumber() +
                            ": " + e.getMessage(), e
            );
        } catch (SAXException e) {
            throw new XmlSchemaValidationException("Falha de conformidade XML/XSD: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new Exception("Erro ao validar XML da NF-e: " + e.getMessage(), e);
        }
    }

    public void validate(Document xmlDocumento, String xsdPath) throws Exception {

        try (InputStream stream = getResourceAsStream(xsdPath)) {

            if (stream == null) {
                throw new FileNotFoundException("Schema XSD não encontrado: " + xsdPath);
            }

            validate(xmlDocumento, stream);
        }
    }

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

                throw new RuntimeException("Dependência XSD não encontrada: " + systemId);

            } catch (Exception e) {
                throw new RuntimeException("Erro ao resolver recurso XSD: " + systemId, e);
            }
        }
    }

    private static InputStream getResourceAsStream(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        return new BufferedInputStream(resource.getInputStream());
    }
}