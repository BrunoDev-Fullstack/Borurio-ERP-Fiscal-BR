package br.com.borurio.fiscal.service;

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
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

@Service
public class AssinaturaXmlService {

    private final CertificadoService certificadoService;

    public AssinaturaXmlService(CertificadoService certificadoService) {
        this.certificadoService = certificadoService;
    }

    public String assinarXml(String xml) {

        try {

            // =========================
            // 1. PARSE XML
            // =========================
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);

            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            doc.getDocumentElement().normalize();

            // =========================
            // 2. LOCALIZA infNFe
            // =========================
            Element infNFe = (Element) doc.getElementsByTagName("infNFe").item(0);

            if (infNFe == null) {
                throw new RuntimeException("Tag <infNFe> não encontrada");
            }

            String id = infNFe.getAttribute("Id");

            if (id == null || id.isEmpty()) {
                throw new RuntimeException("Id da NF-e inválido");
            }

            infNFe.setIdAttribute("Id", true);

            // =========================
            // 3. CERTIFICADO
            // =========================
            KeyStore keyStore = certificadoService.getKeyStore();
            String alias = certificadoService.getAlias();
            char[] senha = certificadoService.getSenha();

            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, senha);
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);

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

            // CORREÇÃO CRÍTICA AQUI
            DOMSignContext signContext = new DOMSignContext(
                    privateKey,
                    infNFe.getParentNode()
            );

            XMLSignature signature = factory.newXMLSignature(signedInfo, keyInfo);
            signature.sign(signContext);

            // =========================
            // 5. OUTPUT
            // =========================
            Transformer transformer = TransformerFactory.newInstance().newTransformer();

            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));

            return writer.toString();

        } catch (Exception e) {
            throw new RuntimeException("Erro ao assinar XML NF-e", e);
        }
    }
}