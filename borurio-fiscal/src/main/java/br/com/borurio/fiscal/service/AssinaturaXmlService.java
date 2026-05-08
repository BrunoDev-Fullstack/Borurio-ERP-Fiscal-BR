package br.com.borurio.fiscal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.*;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;

@Service
public class AssinaturaXmlService {

    private static final Logger log = LoggerFactory.getLogger(AssinaturaXmlService.class);

    // Algoritmos exigidos pela NF-e 4.00 (NT 2019.001)
    private static final String C14N_INCLUSIVO   = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315";
    private static final String C14N_EXCLUSIVO   = "http://www.w3.org/2001/10/xml-exc-c14n#";
    private static final String DIGEST_SHA256    = "http://www.w3.org/2001/04/xmlenc#sha256";
    private static final String SIGN_RSA_SHA256  = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    private final CertificadoService certificadoService;

    public AssinaturaXmlService(CertificadoService certificadoService) {
        this.certificadoService = certificadoService;
    }

    /**
     * Assina NF-e: localiza infNFe, assina com XMLDSIG e insere Signature em NFe raiz.
     */
    public String assinar(String xmlNfe) throws Exception {
        Document doc = parseXml(xmlNfe);
        Element infNFe = localizarElementoPorTag(doc, "infNFe");
        return assinarElemento(doc, infNFe, doc.getDocumentElement());
    }

    /**
     * Assina evento fiscal (cancelamento, CC-e etc): localiza infEvento, assina
     * e insere Signature dentro do elemento pai evento.
     */
    public String assinarEvento(String xmlEvento) throws Exception {
        Document doc = parseXml(xmlEvento);
        Element infEvento = localizarElementoPorTag(doc, "infEvento");
        Element eventoContainer = (Element) infEvento.getParentNode();
        return assinarElemento(doc, infEvento, eventoContainer);
    }

    /**
     * Assina inutNFe: localiza infInut, assina e insere Signature em inutNFe raiz.
     */
    public String assinarInutilizacao(String xmlInut) throws Exception {
        Document doc = parseXml(xmlInut);
        Element infInut = localizarElementoPorTag(doc, "infInut");
        Element inutContainer = (Element) infInut.getParentNode();
        return assinarElemento(doc, infInut, inutContainer);
    }

    private String assinarElemento(Document doc, Element elementoParaAssinar,
                                   Element containerAssinatura) throws Exception {
        String id = elementoParaAssinar.getAttribute("Id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    elementoParaAssinar.getTagName() + " sem atributo Id");
        }
        elementoParaAssinar.setIdAttribute("Id", true);

        PrivateKey privateKey = certificadoService.getPrivateKey();
        X509Certificate cert = certificadoService.getCertificate();

        XMLSignatureFactory sigFactory = XMLSignatureFactory.getInstance("DOM");

        List<Transform> transforms = new ArrayList<>();
        transforms.add(sigFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null));
        transforms.add(sigFactory.newTransform(C14N_INCLUSIVO, (TransformParameterSpec) null));

        Reference reference = sigFactory.newReference(
                "#" + id,
                sigFactory.newDigestMethod(DIGEST_SHA256, null),
                transforms, null, null);

        SignedInfo signedInfo = sigFactory.newSignedInfo(
                sigFactory.newCanonicalizationMethod(C14N_INCLUSIVO, (C14NMethodParameterSpec) null),
                sigFactory.newSignatureMethod(SIGN_RSA_SHA256, null),
                Collections.singletonList(reference));

        KeyInfoFactory kif = sigFactory.getKeyInfoFactory();
        X509Data x509Data = kif.newX509Data(Collections.singletonList(cert));
        KeyInfo keyInfo = kif.newKeyInfo(Collections.singletonList(x509Data));

        XMLSignature signature = sigFactory.newXMLSignature(signedInfo, keyInfo);
        DOMSignContext context = new DOMSignContext(privateKey, containerAssinatura);
        signature.sign(context);

        return serializar(doc);
    }

    private Element localizarElementoPorTag(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagNameNS("http://www.portalfiscal.inf.br/nfe", tag);
        if (nodes.getLength() == 0) {
            nodes = doc.getElementsByTagName(tag);
        }
        if (nodes.getLength() == 0) {
            throw new IllegalArgumentException(tag + " não encontrado no XML");
        }
        return (Element) nodes.item(0);
    }

    private Document parseXml(String xml) throws Exception {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        // Proteção contra XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String serializar(Document doc) throws Exception {

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();

        // Mantém o XML compacto (sem alteração de whitespace)
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.INDENT, "no");

        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));

        return sw.toString();
    }
}