package br.com.borurio.fiscal.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * =============================================================================
 * SERVIÇO: AssinaturaXmlService
 * -----------------------------------------------------------------------------
 * Responsável por assinar XMLs fiscais (NF-e 4.00) conforme padrão SEFAZ.
 *
 * - Referência: infNFe@Id
 * - Canonicalization: INCLUSIVE
 * - Algoritmo: SHA-256 with RSA (OBRIGATÓRIO)
 *
 * OBS:
 * - Certificado A1 carregado do classpath (DEV/HOM)
 * - Em PRD deve vir de keystore segura (HSM / Vault)
 * =============================================================================
 */
@Service
public class AssinaturaXmlService {

    private static final String CERT_PATH = "certs/generic-dev-cert.pfx";
    private static final String CERT_PASSWORD = "123456";

    public byte[] assinarXml(byte[] xmlBytes) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(
                    new ClassPathResource(CERT_PATH).getInputStream(),
                    CERT_PASSWORD.toCharArray()
            );

            String alias = keyStore.aliases().nextElement();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, CERT_PASSWORD.toCharArray());
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate(alias);

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder builder = dbf.newDocumentBuilder();

            Document xml = builder.parse(new ByteArrayInputStream(xmlBytes));

            NodeList nodeList = xml.getElementsByTagName("infNFe");
            if (nodeList.getLength() == 0) {
                throw new IllegalStateException("Tag <infNFe> não encontrada no XML");
            }

            Element infNFe = (Element) nodeList.item(0);
            String id = infNFe.getAttribute("Id");
            infNFe.setIdAttribute("Id", true);

            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

            Reference ref = fac.newReference(
                    "#" + id,
                    fac.newDigestMethod(DigestMethod.SHA256, null),
                    Collections.singletonList(
                            fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)
                    ),
                    null,
                    null
            );

            SignedInfo si = fac.newSignedInfo(
                    fac.newCanonicalizationMethod(
                            CanonicalizationMethod.INCLUSIVE,
                            (C14NMethodParameterSpec) null
                    ),
                    fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                    Collections.singletonList(ref)
            );

            KeyInfoFactory kif = fac.getKeyInfoFactory();
            X509Data x509Data = kif.newX509Data(Collections.singletonList(certificate));
            KeyInfo ki = kif.newKeyInfo(Collections.singletonList(x509Data));

            DOMSignContext dsc = new DOMSignContext(privateKey, xml.getDocumentElement());
            XMLSignature signature = fac.newXMLSignature(si, ki);
            signature.sign(dsc);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.xml.transform.TransformerFactory.newInstance()
                    .newTransformer()
                    .transform(
                            new javax.xml.transform.dom.DOMSource(xml),
                            new javax.xml.transform.stream.StreamResult(out)
                    );

            return out.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Erro ao assinar XML fiscal", e);
        }
    }
}
