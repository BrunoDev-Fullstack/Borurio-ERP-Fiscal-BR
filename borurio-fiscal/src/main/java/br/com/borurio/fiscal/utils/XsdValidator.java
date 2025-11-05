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
 * Utilitário de validação de XMLs fiscais (NF-e 4.00) contra os schemas XSD
 * nacionais ajustados do projeto Borurio ERP Fiscal BR.
 *
 * Recursos:
 *  - Compatível com includes/imports internos (procNFe, leiaute, xmldsig);
 *  - Execução segura em ambiente empacotado (Docker / JAR);
 *  - Compatível com Xerces, JUnit 5 e OWASP XML Security;
 *  - Protegido contra XXE, DTD injection e overflows de parser.
 *
 * Versão: 3.2.2
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@Component
public class XsdValidator {

    /**
     * Valida um documento XML contra o schema XSD informado.
     *
     * @param xmlDocumento Documento XML (DOM)
     * @param xsdPath Caminho do schema consolidado (ex: xsd/custom/nfe_v4.00_consolidado.xsd)
     * @throws Exception Se o XML estiver fora de conformidade ou ocorrer erro de parser
     */
    public void validate(Document xmlDocumento, String xsdPath) throws Exception {
        try {
            // ================================================================
            // 1. Hardening do Parser — OWASP XML Security Recommendations
            // ================================================================
            System.setProperty("jdk.xml.maxOccurLimit", "10000");
            System.setProperty("jdk.xml.entityExpansionLimit", "10000");
            System.setProperty("jdk.xml.elementAttributeLimit", "10000");
            System.setProperty("jdk.xml.totalEntitySizeLimit", "10000000");

            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            schemaFactory.setResourceResolver(new ClasspathResourceResolver());

            // ================================================================
            // 2. Carrega o schema consolidado
            // ================================================================
            try (InputStream schemaStream = getResourceAsStream(xsdPath)) {
                if (schemaStream == null) {
                    throw new FileNotFoundException("Schema XSD não encontrado: " + xsdPath);
                }

                Schema schema = schemaFactory.newSchema(new StreamSource(schemaStream));
                Validator validator = schema.newValidator();

                // Garante o namespace padrão da SEFAZ na raiz
                if (xmlDocumento.getDocumentElement().getNamespaceURI() == null) {
                    xmlDocumento.getDocumentElement()
                            .setAttribute("xmlns", "http://www.portalfiscal.inf.br/nfe");
                }

                // ============================================================
                // 3. Execução da validação
                // ============================================================
                validator.validate(new DOMSource(xmlDocumento));
                System.out.println("[XSD-VALIDATOR] XML validado com sucesso contra " + xsdPath);
            }

        } catch (SAXException e) {
            throw new Exception("Falha de conformidade XML/XSD: " + e.getMessage(), e);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new Exception("Erro interno ao validar XML da NF-e: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // CLASSE INTERNA: Resource Resolver Seguro
    // =========================================================================
    /**
     * Resolve includes/imports dentro de /xsd/custom/ e /xsd/oficial/
     * diretamente do classpath, protegendo contra Path Traversal.
     */
    private static class ClasspathResourceResolver implements LSResourceResolver {
        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId,
                                       String systemId, String baseURI) {
            try {
                if (systemId == null || systemId.contains("..")) {
                    return null;
                }

                String cleanName = new File(systemId).getName();
                String[] searchPaths = {
                        "xsd/custom/" + cleanName,
                        "xsd/oficial/" + cleanName
                };

                for (String path : searchPaths) {
                    InputStream resourceAsStream = getResourceAsStream(path);
                    if (resourceAsStream != null) {
                        var impl = (DOMImplementationLS) DocumentBuilderFactory
                                .newInstance()
                                .newDocumentBuilder()
                                .getDOMImplementation()
                                .getFeature("LS", "3.0");

                        LSInput input = impl.createLSInput();
                        input.setSystemId(path);
                        input.setByteStream(resourceAsStream);
                        input.setEncoding(StandardCharsets.UTF_8.name());

                        System.out.println("[XSD-RESOLVER] Schema localizado: " + path);
                        return input;
                    }
                }

                System.err.println("[XSD-RESOLVER] Schema não encontrado: " + systemId);
                return null;

            } catch (Exception e) {
                System.err.println("[XSD-RESOLVER] Erro ao resolver schema: " + e.getMessage());
                return null;
            }
        }
    }

    // =========================================================================
    // MÉTODO AUXILIAR: Busca arquivos no classpath com segurança
    // =========================================================================
    private static InputStream getResourceAsStream(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) return null;
        return new BufferedInputStream(resource.getInputStream());
    }

    // =========================================================================
    // MÉTODO MAIN — Execução via Maven / Linha de Comando
    // =========================================================================
    /**
     * Ponto de entrada para execução standalone via:
     *     mvn exec:java
     *
     * Gera logs detalhados de sucesso e erro em logs/xsd-validation.log
     */
    public static void main(String[] args) {
        // Caminhos padrão — alterar conforme ambiente
        String xmlPath = "C:/Projetos/borurio-erp-br/docs/xml/nfe-assinada-real.xml";
        String xsdPath = "xsd/custom/nfe_v4.00_consolidado.xsd";

        System.out.println("===============================================================");
        System.out.println("VALIDAÇÃO XML NF-e 4.00 — BORURIO ERP FISCAL BR");
        System.out.println("_______________________________________________________________");
        System.out.println("XML: " + xmlPath);
        System.out.println("XSD: " + xsdPath);
        System.out.println("===============================================================");

        File logFile = new File("logs/xsd-validation.log");
        logFile.getParentFile().mkdirs();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try (OutputStreamWriter log = new OutputStreamWriter(
                new FileOutputStream(logFile, false), StandardCharsets.UTF_8)) {

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setNamespaceAware(true);
            dbf.setExpandEntityReferences(false);
            dbf.setXIncludeAware(false);
            dbf.setIgnoringComments(true);

            // Proteção adicional contra XXE
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {}

            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document xmlDoc = builder.parse(new File(xmlPath));

            XsdValidator validator = new XsdValidator();
            validator.validate(xmlDoc, xsdPath);

            String successMsg = "[SUCESSO] " + LocalDateTime.now().format(formatter)
                    + " — XML validado com sucesso.\n";
            log.write(successMsg);
            System.out.println(successMsg);

        } catch (Exception e) {
            try (OutputStreamWriter log = new OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {

                String errorMsg = "[ERRO] " + LocalDateTime.now().format(formatter)
                        + " — " + e.getMessage() + "\n";
                log.write(errorMsg);
            } catch (IOException ignored) {
            }

            System.err.println("[ERRO] Falha na validação: " + e.getMessage());
        }
    }
}
