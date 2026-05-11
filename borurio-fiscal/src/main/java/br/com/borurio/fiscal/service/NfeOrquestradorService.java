package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private final EmitenteProperties emitente;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    @Autowired
    public NfeOrquestradorService(XsdValidator xsdValidator,
                                  AssinaturaXmlService assinaturaXmlService,
                                  NfeTransmitService nfeTransmitService,
                                  EmitenteProperties emitente) {
        this.xsdValidator = xsdValidator;
        this.assinaturaXmlService = assinaturaXmlService;
        this.nfeTransmitService = nfeTransmitService;
        this.emitente = emitente;
    }

    public String processar(String xmlNfe, String cnpjEmitente) throws Exception {
        return processar(xmlNfe, cnpjEmitente, null);
    }

    /**
     * Processa NF-e usando o certificado de uma empresa específica.
     * Se ctx for null, usa o certificado global (CertificadoService).
     */
    public String processar(String xmlNfe, String cnpjEmitente,
                            CertificadoContexto ctx) throws Exception {

        // 1. Converter XML para Document
        Document document = converterParaDocument(xmlNfe);

        // Contrato de input: apenas <NFe> bare é aceito como corpo da requisição.
        // O lote <enviNFe> é responsabilidade exclusiva de NfeTransmitServiceImpl.
        String rootElement = document.getDocumentElement().getLocalName();
        if (!"NFe".equals(rootElement)) {
            throw new IllegalArgumentException(
                    "XML inválido: o elemento raiz deve ser <NFe>. " +
                    "O lote <enviNFe> é montado internamente pelo sistema."
            );
        }

        // 2. Validar XSD antes de assinar
        xsdValidator.validate(document, "xsd/custom/nfe_v4.00_consolidado.xsd");

        // 3. Assinar XML (XMLDSIG RSA-SHA256 — obrigatório NF-e 4.00)
        String xmlAssinado = ctx != null
                ? assinaturaXmlService.assinar(xmlNfe, ctx)
                : assinaturaXmlService.assinar(xmlNfe);

        // 4. Transmitir para SEFAZ com XML assinado
        String uf = (emitente.getUf() != null && !emitente.getUf().isBlank())
                ? emitente.getUf() : "SP";

        return ctx != null
                ? nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente, uf, tpAmb, ctx.sslContext())
                : nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente, uf, tpAmb);
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