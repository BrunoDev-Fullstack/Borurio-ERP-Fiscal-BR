package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.NfeAuthorizeService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * =============================================================================
 * SERVIÇO MOCK — AUTORIZAÇÃO DE NF-e (SEFAZ)
 * =============================================================================
 * Executa o fluxo completo:
 *   1. Validação XSD
 *   2. Geração de protocolo <protNFe> mock
 *   3. Registro em nfe_log
 *   4. Retorno XML retEnviNFe
 *
 * Autor: Bruno Ribeiro – DevSecOps / Fullstack Java
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Service
public class NfeAuthorizeServiceImpl implements NfeAuthorizeService {

    private final NfeLogService nfeLogService;
    private final XsdValidator xsdValidator;

    public NfeAuthorizeServiceImpl(NfeLogService nfeLogService, XsdValidator xsdValidator) {
        this.nfeLogService = nfeLogService;
        this.xsdValidator = xsdValidator;
    }

    // =========================================================================
    // AUTORIZAR NF-E (MOCK)
    // =========================================================================
    @Override
    public Document autorizarNFe(Document xmlDocumento) throws Exception {

        validarXML(xmlDocumento);

        Document xmlAutorizado = gerarProtocolo(xmlDocumento);

        String chave = extrairChave(xmlDocumento);
        String xmlString = documentToString(xmlAutorizado);

        nfeLogService.registrarEvento(
                "AUTORIZACAO_MOCK",
                "NF-e autorizada localmente (mock SEFAZ)",
                chave,
                xmlString
        );

        return xmlAutorizado;
    }

    // =========================================================================
    // VALIDAÇÃO XML
    // =========================================================================
    @Override
    public void validarXML(Document xmlDocumento) throws Exception {
        try {
            xsdValidator.validate(xmlDocumento, "/xsd/leiauteNFe_v4.00.xsd");
        } catch (Exception e) {
            throw new Exception("Falha na validação XSD da NF-e: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // GERAÇÃO DO PROTOCOLO MOCK
    // =========================================================================
    @Override
    public Document gerarProtocolo(Document xmlDocumento) throws Exception {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document retEnviNFe = builder.newDocument();

        // Raiz
        Element root = retEnviNFe.createElementNS("http://www.portalfiscal.inf.br/nfe", "retEnviNFe");
        root.setAttribute("versao", "4.00");
        retEnviNFe.appendChild(root);

        // Cabeçalho
        append(retEnviNFe, root, "tpAmb", "2");
        append(retEnviNFe, root, "verAplic", "Borurio-ERP-MOCK");
        append(retEnviNFe, root, "cStat", "100");
        append(retEnviNFe, root, "xMotivo", "Autorizado o uso da NF-e");
        append(retEnviNFe, root, "cUF", "35");

        // protNFe
        Element protNFe = retEnviNFe.createElementNS("http://www.portalfiscal.inf.br/nfe", "protNFe");
        protNFe.setAttribute("versao", "4.00");
        root.appendChild(protNFe);

        Element infProt = retEnviNFe.createElement("infProt");
        protNFe.appendChild(infProt);

        append(retEnviNFe, infProt, "tpAmb", "2");
        append(retEnviNFe, infProt, "verAplic", "Borurio-ERP-MOCK");

        String chave = extrairChave(xmlDocumento);
        append(retEnviNFe, infProt, "chNFe", chave);

        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
        append(retEnviNFe, infProt, "dhRecbto", timestamp);

        append(retEnviNFe, infProt, "nProt", gerarNumeroProtocolo());
        append(retEnviNFe, infProt, "digVal", gerarHashAssinatura(xmlDocumento));

        append(retEnviNFe, infProt, "cStat", "100");
        append(retEnviNFe, infProt, "xMotivo", "Autorizado o uso da NF-e");

        return retEnviNFe;
    }

    // =========================================================================
    // UTILITÁRIOS XML
    // =========================================================================

    private String extrairChave(Document xml) {
        try {
            Element infNFe = (Element) xml.getElementsByTagName("infNFe").item(0);
            if (infNFe != null && infNFe.hasAttribute("Id")) {
                String id = infNFe.getAttribute("Id");
                return id.replace("NFe", "").trim();
            }
        } catch (Exception ignored) {}

        // fallback seguro
        return UUID.randomUUID().toString().replace("-", "").substring(0, 44);
    }

    private String gerarNumeroProtocolo() {
        long millis = System.currentTimeMillis();
        String base = "135" + millis;
        return base.substring(0, Math.min(base.length(), 15));
    }

    private String gerarHashAssinatura(Document xml) {
        try {
            String xmlString = documentToString(xml);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(xmlString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
        }
    }

    private String documentToString(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));

        return out.toString(StandardCharsets.UTF_8);
    }

    private void append(Document doc, Element parent, String tag, String value) {
        Element element = doc.createElement(tag);
        element.setTextContent(value);
        parent.appendChild(element);
    }
}
