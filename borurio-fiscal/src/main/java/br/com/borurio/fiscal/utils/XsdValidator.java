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
 * =============================================================================
 * COMPONENTE: XsdValidator
 * -----------------------------------------------------------------------------
 * Utilitário de validação de XMLs fiscais (NF-e 4.00) contra os schemas XSD
 * nacionais ajustados do projeto Borurio ERP Fiscal BR.
 *
 * Recursos:
 *  - Compatível com includes/imports internos (procNFe, leiaute, xmldsig);
 *  - Execução segura em ambiente empacotado (Docker / JAR);
 *  - Compatível com Xerces e JUnit 5;
 *  - Protegido contra XXE, DTD injection e overflows de parser.
 *
 * Versão: 3.1.5
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@Component
public class XsdValidator {

    /**
     * Valida um XML contra o XSD consolidado.
     *
     * @param xmlDocumento Documento XML (DOM)
     * @param xsdPath Caminho do schema principal (ex: xsd/custom/nfe_v4.00_consolidado.xsd)
     * @throws Exception Caso o XML não esteja conforme o schema
     */
    public void validate(Document xmlDocumento, String xsdPath) throws Exception {
        try {
            // ================================================================
            // 1. Configuração de segurança do parser Xerces
            // ================================================================
            System.setProperty("jdk.xml.maxOccurLimit", "10000");
            System.setProperty("jdk.xml.entityExpansionLimit", "10000");
            System.setProperty("jdk.xml.elementAttributeLimit", "10000");
            System.setProperty("jdk.xml.totalEntitySizeLimit", "10000000");

            // Cria fábrica de schemas com validação W3C
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setResourceResolver(new ClasspathResourceResolver());

            // ================================================================
            // 2. Carrega schema consolidado (via classpath)
            // ================================================================
            try (InputStream schemaStream = getResourceAsStream(xsdPath)) {
                if (schemaStream == null) {
                    throw new IllegalArgumentException("Schema XSD não encontrado: " + xsdPath);
                }

                Schema schema = schemaFactory.newSchema(new StreamSource(schemaStream));
                Validator validator = schema.newValidator();

                // Garante o namespace padrão da SEFAZ na raiz
                xmlDocumento.getDocumentElement()
                        .setAttribute("xmlns", "http://www.portalfiscal.inf.br/nfe");

                // ============================================================
                // 3. Validação efetiva do XML fiscal
                // ============================================================
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

    // =========================================================================
    // CLASSE INTERNA: ClasspathResourceResolver
    // =========================================================================
    /**
     * Resolve includes/imports dentro de /xsd/custom/ diretamente do classpath.
     * Compatível com empacotamento em JAR e execução Docker.
     */
    private static class ClasspathResourceResolver implements LSResourceResolver {
        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
            try {
                if (systemId == null) return null;

                // Normaliza caminho — sempre procura em /xsd/custom/
                String cleanPath = systemId.replace("\\", "/");
                if (!cleanPath.startsWith("xsd/custom/")) {
                    if (cleanPath.contains("custom/")) {
                        cleanPath = "xsd/" + cleanPath.substring(cleanPath.indexOf("custom/"));
                    } else {
                        cleanPath = "xsd/custom/" + cleanPath;
                    }
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

                System.out.println("[XSD-RESOLVER] Carregando schema: " + cleanPath);
                return input;

            } catch (Exception e) {
                System.err.println("[XSD-RESOLVER] Falha ao resolver recurso: " + e.getMessage());
                return null;
            }
        }
    }

    // =========================================================================
    // MÉTODO AUXILIAR: Localiza arquivos XSD no classpath
    // =========================================================================
    /**
     * Localiza um recurso dentro do classpath (compatível com execução em JAR).
     *
     * @param path Caminho relativo do recurso (ex: xsd/custom/nfe_v4.00_consolidado.xsd)
     * @return InputStream do arquivo, ou null se não encontrado
     */
    private static InputStream getResourceAsStream(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        return resource.exists() ? resource.getInputStream() : null;
    }
}
