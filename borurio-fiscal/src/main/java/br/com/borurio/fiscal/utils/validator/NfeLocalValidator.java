package br.com.borurio.fiscal.utils.validator;

import br.com.borurio.fiscal.utils.XsdValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * =============================================================================
 *  VALIDACAO LOCAL DE XML DA NF-e
 * =============================================================================
 *  Objetivo:
 *      - Validar estrutura XML
 *      - Validar contra XSD oficial (4.00)
 *      - Verificar tag raiz obrigatória
 *      - Utilizado antes da transmissão real para SEFAZ-SP
 *
 *  Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 *  Revisão: 17/11/2025
 * =============================================================================
 */
@Slf4j
@Component
public class NfeLocalValidator {

    /**
     * Executa validação completa do XML.
     */
    public void validarXmlNfe(String xml) {

        log.info("[NfeLocalValidator] Iniciando validação local da NF-e.");

        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML da NF-e não informado.");
        }

        // 1) Criar Document válido
        Document document = validarEstruturaXml(xml);

        // 2) Validar contra o XSD
        validarContraXsd(document);

        // 3) Validar tag raiz
        validarTagPrincipal(xml);

        log.info("[NfeLocalValidator] XML validado com sucesso.");
    }

    /**
     * 1) Verifica XML bem formado e retorna um Document válido.
     */
    private Document validarEstruturaXml(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);

            var builder = factory.newDocumentBuilder();
            Document document =
                    builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            log.debug("[NfeLocalValidator] Estrutura XML válida.");
            return document;

        } catch (Exception e) {
            log.error("[NfeLocalValidator] XML malformado: {}", e.getMessage());
            throw new RuntimeException("XML malformado. Verifique a estrutura.", e);
        }
    }

    /**
     * 2) Validação contra XSD oficial (NF-e 4.00).
     */
    private void validarContraXsd(Document document) {
        try {
            final String xsdPath = "xsd/oficial/enviNFe_v4.00.xsd";

            // XsdValidator é instância, não estático
            XsdValidator validator = new XsdValidator();
            validator.validate(document, xsdPath);

            log.debug("[NfeLocalValidator] XML validado com XSD oficial.");

        } catch (Exception e) {
            log.error("[NfeLocalValidator] Erro ao validar XSD: {}", e.getMessage());
            throw new RuntimeException("Falha na validação XSD do XML.", e);
        }
    }

    /**
     * 3) Verificação da tag raiz.
     */
    private void validarTagPrincipal(String xml) {
        if (!(xml.contains("<enviNFe") || xml.contains("<NFe"))) {
            log.error("[NfeLocalValidator] XML não contém tag raiz esperada.");
            throw new RuntimeException("Tag raiz inválida. Esperado <enviNFe> ou <NFe>.");
        }

        log.debug("[NfeLocalValidator] Tag raiz válida.");
    }
}
