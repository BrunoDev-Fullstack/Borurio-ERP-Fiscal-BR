package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.utils.XsdValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * =============================================================================
 * SERVIÇO: NfeOrquestradorService
 * =============================================================================
 * Fluxo:
 * XML → Converter → Validar XSD → Assinar XMLDSIG → Transmitir → Retornar
 * =============================================================================
 */
@Service
public class NfeOrquestradorService {

    private final XsdValidator xsdValidator;
    private final AssinaturaXmlService assinaturaXmlService;
    private final NfeTransmitService nfeTransmitService;

    @Autowired
    public NfeOrquestradorService(XsdValidator xsdValidator,
                                  AssinaturaXmlService assinaturaXmlService,
                                  NfeTransmitService nfeTransmitService) {
        this.xsdValidator = xsdValidator;
        this.assinaturaXmlService = assinaturaXmlService;
        this.nfeTransmitService = nfeTransmitService;
    }

    public String processar(String xmlNfe, String cnpjEmitente) throws Exception {

        // 1. Converter XML para Document
        Document document = converterParaDocument(xmlNfe);

        // 2. Validar XSD antes de assinar
        xsdValidator.validate(document, "xsd/custom/nfe_v4.00_consolidado.xsd");

        // 3. Assinar XML (XMLDSIG RSA-SHA256 — obrigatório NF-e 4.00)
        String xmlAssinado = assinaturaXmlService.assinar(xmlNfe);

        // 4. Transmitir para SEFAZ com XML assinado
        return nfeTransmitService.transmitirXml(
                xmlAssinado,
                cnpjEmitente,
                "SP",
                2
        );
    }

    private Document converterParaDocument(String xml) throws Exception {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        return factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
        );
    }
}