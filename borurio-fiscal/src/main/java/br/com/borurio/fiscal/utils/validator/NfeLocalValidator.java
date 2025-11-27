package br.com.borurio.fiscal.utils.validator;

import br.com.borurio.fiscal.utils.XsdValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * =============================================================================
 * VALIDACAO LOCAL DE XML DA NF-e — BORURIO ERP FISCAL BR
 * =============================================================================
 * Objetivo:
 *   - Garantir que o XML é bem-formado
 *   - Validar contra XSD consolidado (NF-e 4.00)
 *   - Verificar presença da tag raiz obrigatória
 *   - Executa antes do envio real ao WebService SEFAZ-SP
 *
 * Regras:
 *   - Suporte a <enviNFe>, <NFe> e <procNFe>
 *   - Hardening de XML (contra XXE / DTD / entidades externas)
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Slf4j
@Component
public class NfeLocalValidator {

    /**
     * Executa a validação completa:
     *  - Estrutura bem formada
     *  - XSD oficial da NF-e 4.00 (consolidado)
     *  - Tag raiz
     */
    public void validarXmlNfe(String xml) {

        log.info("[NfeLocalValidator] Iniciando validação local da NF-e.");

        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML da NF-e não informado.");
        }

        // 1. XML bem formado → Document
        Document document = validarEstruturaXml(xml);

        // 2. XML compatível com o XSD consolidado
        validarContraXsd(document);

        // 3. Tag raiz obrigatória
        validarTagPrincipal(xml);

        log.info("[NfeLocalValidator] XML validado com sucesso.");
    }

    // =========================================================================
    // ETAPA 1 — PARSE COM HARDENING
    // =========================================================================
    private Document validarEstruturaXml(String xml) {

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);

            // SEGURANÇA OWASP XML (Java 17)
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);

            var builder = factory.newDocumentBuilder();

            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
            );

            log.debug("[NfeLocalValidator] Estrutura XML bem formada.");
            return document;

        } catch (Exception e) {
            log.error("[NfeLocalValidator] XML malformado: {}", e.getMessage());
            throw new RuntimeException(
                    "XML malformado. Verifique estrutura e encoding.",
                    e
            );
        }
    }

    // =========================================================================
    // ETAPA 2 — VALIDAÇÃO CONTRA XSD CONSOLIDADO
    // =========================================================================
    private void validarContraXsd(Document document) {

        try {
            // Usando o XSD consolidado do projeto (inclui leiaute + tiposNFe)
            final String xsdPath = "xsd/custom/nfe_v4.00_consolidado.xsd";

            XsdValidator validator = new XsdValidator();
            validator.validate(document, xsdPath);

            log.debug("[NfeLocalValidator] XML validado com XSD consolidado 4.00.");

        } catch (Exception e) {
            log.error("[NfeLocalValidator] Falha ao validar XSD: {}", e.getMessage());
            throw new RuntimeException(
                    "Falha na validação XSD da NF-e: " + e.getMessage(),
                    e
            );
        }
    }

    // =========================================================================
    // ETAPA 3 — TAG RAIZ OBRIGATÓRIA
    // =========================================================================
    private void validarTagPrincipal(String xml) {

        if (!(xml.contains("<enviNFe") ||
                xml.contains("<NFe") ||
                xml.contains("<procNFe")
        )) {
            log.error("[NfeLocalValidator] Tag raiz inválida.");
            throw new RuntimeException(
                    "Tag raiz inválida. Esperado <enviNFe>, <NFe> ou <procNFe>."
            );
        }

        log.debug("[NfeLocalValidator] Tag raiz válida.");
    }
}
