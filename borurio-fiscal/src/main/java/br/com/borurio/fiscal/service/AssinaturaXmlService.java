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

    // Algoritmos exigidos pela NF-e 4.00
    private static final String C14N_EXCLUSIVO = "http://www.w3.org/2001/10/xml-exc-c14n#";
    private static final String DIGEST_SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256";
    private static final String SIGN_RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    private final CertificadoService certificadoService;

    public AssinaturaXmlService(CertificadoService certificadoService) {
        this.certificadoService = certificadoService;
    }

    public String assinar(String xmlNfe) throws Exception {

        // Parse do XML com namespace habilitado (obrigatório para XMLDSIG)
        Document doc = parseXml(xmlNfe);

        // Busca o nó infNFe que será assinado
        Element infNFe = localizarInfNFe(doc);
        String id = infNFe.getAttribute("Id");

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("infNFe sem atributo Id");
        }

        // Marca o atributo Id como ID real para referência na assinatura
        infNFe.setIdAttribute("Id", true);

        PrivateKey privateKey = certificadoService.getPrivateKey();
        X509Certificate cert = certificadoService.getCertificate();

        XMLSignatureFactory sigFactory = XMLSignatureFactory.getInstance("DOM");

        // Transformações obrigatórias:
        // - enveloped (remove a própria assinatura do cálculo)
        // - canonicalização exclusiva (padrão SEFAZ)
        List<Transform> transforms = new ArrayList<>();

        transforms.add(sigFactory.newTransform(
                Transform.ENVELOPED,
                (TransformParameterSpec) null));

        transforms.add(sigFactory.newTransform(
                C14N_EXCLUSIVO,
                (TransformParameterSpec) null));

        // Referência ao elemento infNFe
        Reference reference = sigFactory.newReference(
                "#" + id,
                sigFactory.newDigestMethod(DIGEST_SHA256, null),
                transforms,
                null,
                null
        );

        // Estrutura principal da assinatura
        SignedInfo signedInfo = sigFactory.newSignedInfo(
                sigFactory.newCanonicalizationMethod(
                        C14N_EXCLUSIVO,
                        (C14NMethodParameterSpec) null
                ),
                sigFactory.newSignatureMethod(SIGN_RSA_SHA256, null),
                Collections.singletonList(reference)
        );

        // Inclui o certificado na assinatura
        KeyInfoFactory kif = sigFactory.getKeyInfoFactory();
        X509Data x509Data = kif.newX509Data(Collections.singletonList(cert));
        KeyInfo keyInfo = kif.newKeyInfo(Collections.singletonList(x509Data));

        XMLSignature signature = sigFactory.newXMLSignature(signedInfo, keyInfo);

        // A assinatura deve ser adicionada diretamente abaixo de <NFe>
        DOMSignContext context = new DOMSignContext(
                privateKey,
                doc.getDocumentElement()
        );

        signature.sign(context);

        return serializar(doc);
    }

    private Element localizarInfNFe(Document doc) {

        NodeList nodes = doc.getElementsByTagNameNS(
                "http://www.portalfiscal.inf.br/nfe", "infNFe");

        if (nodes.getLength() == 0) {
            nodes = doc.getElementsByTagName("infNFe");
        }

        if (nodes.getLength() == 0) {
            throw new IllegalArgumentException("infNFe não encontrado");
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