package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.NfeAuthorizeService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.utils.XsdValidator;
import jakarta.xml.bind.DatatypeConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Service
public class NfeAuthorizeServiceImpl implements NfeAuthorizeService {

    private final NfeLogService nfeLogService;
    private final XsdValidator xsdValidator;

    @Autowired
    public NfeAuthorizeServiceImpl(NfeLogService nfeLogService, XsdValidator xsdValidator) {
        this.nfeLogService = nfeLogService;
        this.xsdValidator = xsdValidator;
    }

    @Override
    public Document autorizarNFe(Document xmlDocumento) throws Exception {
        validarXML(xmlDocumento);

        Document xmlAutorizado = gerarProtocolo(xmlDocumento);

        String chaveNFe = extrairChave(xmlDocumento);

        nfeLogService.registrarEvento(
                chaveNFe,
                "AUTORIZACAO_MOCK",
                "NF-e autorizada localmente (mock SEFAZ)",
                "system"
        );

        return xmlAutorizado;
    }

    @Override
    public void validarXML(Document xmlDocumento) throws Exception {
        try {
            xsdValidator.validate(xmlDocumento, "xsd/custom/nfe_v4.00_consolidado.xsd");
        } catch (Exception e) {
            throw new Exception("Falha na validação do XML da NF-e: " + e.getMessage(), e);
        }
    }

    @Override
    public Document gerarProtocolo(Document xmlDocumento) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document retEnviNFe = builder.newDocument();

        Element root = retEnviNFe.createElementNS("http://www.portalfiscal.inf.br/nfe", "retEnviNFe");
        root.setAttribute("versao", "4.00");
        retEnviNFe.appendChild(root);

        appendElement(retEnviNFe, root, "tpAmb", "2");
        appendElement(retEnviNFe, root, "verAplic", "Borurio-ERP-DEV");
        appendElement(retEnviNFe, root, "cStat", "100");
        appendElement(retEnviNFe, root, "xMotivo", "Autorizado o uso da NF-e");
        appendElement(retEnviNFe, root, "cUF", "35");

        Element protNFe = retEnviNFe.createElement("protNFe");
        protNFe.setAttribute("versao", "4.00");
        root.appendChild(protNFe);

        Element infProt = retEnviNFe.createElement("infProt");
        protNFe.appendChild(infProt);

        appendElement(retEnviNFe, infProt, "tpAmb", "2");
        appendElement(retEnviNFe, infProt, "verAplic", "Borurio-ERP-DEV");

        String chaveNFe = extrairChave(xmlDocumento);
        appendElement(retEnviNFe, infProt, "chNFe", chaveNFe);

        String dataHora = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
        appendElement(retEnviNFe, infProt, "dhRecbto", dataHora);

        appendElement(retEnviNFe, infProt, "nProt", gerarNumeroProtocolo());
        appendElement(retEnviNFe, infProt, "digVal", gerarHashAssinatura(xmlDocumento));
        appendElement(retEnviNFe, infProt, "cStat", "100");
        appendElement(retEnviNFe, infProt, "xMotivo", "Autorizado o uso da NF-e");

        return retEnviNFe;
    }

    private String extrairChave(Document xmlDocumento) {
        try {
            Element infNFe = (Element) xmlDocumento.getElementsByTagName("infNFe").item(0);
            if (infNFe != null && infNFe.hasAttribute("Id")) {
                return infNFe.getAttribute("Id").replace("NFe", "");
            }
        } catch (Exception ignored) {
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 44);
    }

    private String gerarNumeroProtocolo() {
        String base = "13525" + System.currentTimeMillis();
        return base.substring(0, Math.min(base.length(), 15));
    }

    private String gerarHashAssinatura(Document xmlDocumento) {
        try {
            String xmlString = documentToString(xmlDocumento);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(xmlString.getBytes(StandardCharsets.UTF_8));
            return DatatypeConverter.printBase64Binary(digest);
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

    private void appendElement(Document doc, Element parent, String tag, String value) {
        Element element = doc.createElement(tag);
        element.setTextContent(value);
        parent.appendChild(element);
    }
}