package br.com.borurio.fiscal.service.debug;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.*;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;
import java.io.FileInputStream;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import java.util.List;
import java.util.Enumeration;

public class AssinaturaXmlDebug {

    private static final String CAMINHO_XML = "C:\\temp\\nfe.xml";
    private static final String CAMINHO_CERT = "C:\\temp\\cert.pfx";
    private static final String SENHA_CERT = "2025@Qz1";

    public static void main(String[] args) {
        try {
            executar();
        } catch (Exception e) {
            System.out.println("ERRO DETALHADO:");
            e.printStackTrace();
        }
    }

    public static void executar() throws Exception {

        // =========================
        // 1. CARREGAR XML
        // =========================
        DocumentBuilderFactory factoryBuilder = DocumentBuilderFactory.newInstance();
        factoryBuilder.setNamespaceAware(true);

        Document doc = factoryBuilder
                .newDocumentBuilder()
                .parse(new File(CAMINHO_XML));

        doc.getDocumentElement().normalize();

        // =========================
        // 2. CAPTURAR infNFe
        // =========================
        Element infNFe = (Element) doc.getElementsByTagName("infNFe").item(0);

        if (infNFe == null) {
            throw new RuntimeException("Tag infNFe não encontrada");
        }

        infNFe.setIdAttribute("Id", true);

        String id = infNFe.getAttribute("Id");

        if (id == null || id.isEmpty()) {
            throw new RuntimeException("ID da NFe não encontrado");
        }

        System.out.println("ID encontrado: " + id);

        // =========================
        // 3. CARREGAR CERTIFICADO
        // =========================
        KeyStore ks = KeyStore.getInstance("PKCS12");

        ks.load(
                new FileInputStream(CAMINHO_CERT),
                SENHA_CERT.toCharArray()
        );

        Enumeration<String> aliases = ks.aliases();

        if (!aliases.hasMoreElements()) {
            throw new RuntimeException("Nenhum alias encontrado no certificado");
        }

        String alias = aliases.nextElement();

        System.out.println("Alias encontrado: " + alias);

        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, SENHA_CERT.toCharArray());

        if (privateKey == null) {
            throw new RuntimeException("Chave privada não encontrada");
        }

        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        // =========================
        // 4. ASSINATURA
        // =========================
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");

        Reference ref = factory.newReference(
                "#" + id,
                factory.newDigestMethod(DigestMethod.SHA1, null),
                List.of(
                        factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        factory.newTransform(CanonicalizationMethod.INCLUSIVE, (TransformParameterSpec) null)
                ),
                null,
                null
        );

        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(
                        CanonicalizationMethod.INCLUSIVE,
                        (C14NMethodParameterSpec) null
                ),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA1, null),
                List.of(ref)
        );

        KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();

        X509Data x509Data = keyInfoFactory.newX509Data(List.of(cert));

        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(x509Data));

        DOMSignContext signContext = new DOMSignContext(privateKey, infNFe.getParentNode());

        XMLSignature signature = factory.newXMLSignature(signedInfo, keyInfo);

        signature.sign(signContext);

        // =========================
        // 5. OUTPUT
        // =========================
        javax.xml.transform.Transformer transformer =
                javax.xml.transform.TransformerFactory.newInstance().newTransformer();

        transformer.transform(
                new javax.xml.transform.dom.DOMSource(doc),
                new javax.xml.transform.stream.StreamResult(new File("C:\\temp\\nfe-assinada.xml"))
        );

        System.out.println("XML ASSINADO GERADO COM SUCESSO");
    }
}