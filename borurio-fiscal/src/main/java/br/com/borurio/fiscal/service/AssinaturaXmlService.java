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
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Serviço responsável por assinar digitalmente XMLs fiscais (NF-e)
 * usando certificado A1 (.pfx) conforme o padrão SEFAZ v4.00.
 *
 * Projeto compatível com pipelines DevSecOps e execução segura em ambiente CI/CD.
 */
@Service
public class AssinaturaXmlService {

    private static final String CERT_PATH = "certs/generic-dev-cert.pfx";
    private static final String CERT_PASSWORD = "123456";

    /**
     * Assina o XML fiscal, localizando a tag <infNFe> e aplicando assinatura digital.
     *
     * @param xmlBytes XML em formato byte[]
     * @return XML assinado em formato byte[]
     */
    public byte[] assinarXml(byte[] xmlBytes) {
        try {
            // Carrega o certificado digital A1 (.pfx)
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ClassPathResource(CERT_PATH).getInputStream(), CERT_PASSWORD.toCharArray());

            String alias = keyStore.aliases().nextElement();
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, CERT_PASSWORD.toCharArray());
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate(alias);

            // Prepara o documento XML
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document xml = builder.parse(new java.io.ByteArrayInputStream(xmlBytes));

            // Localiza e marca o elemento <infNFe> como referência de assinatura
            NodeList nodeList = xml.getElementsByTagName("infNFe");
            if (nodeList.getLength() == 0) {
                throw new RuntimeException("Tag <infNFe> não encontrada no XML.");
            }

            Element element = (Element) nodeList.item(0);
            String id = element.getAttribute("Id");
            element.setIdAttribute("Id", true); // Define o atributo Id como identificador

            // Cria a estrutura da assinatura digital
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
            Reference ref = fac.newReference(
                    "#" + id,
                    fac.newDigestMethod(DigestMethod.SHA1, null),
                    Collections.singletonList(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                    null,
                    null
            );

            SignedInfo si = fac.newSignedInfo(
                    fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                    fac.newSignatureMethod(SignatureMethod.RSA_SHA1, null),
                    Collections.singletonList(ref)
            );

            // Adiciona informações do certificado ao XML
            KeyInfoFactory kif = fac.getKeyInfoFactory();
            X509Data x509Data = kif.newX509Data(Collections.singletonList(certificate));
            KeyInfo ki = kif.newKeyInfo(Collections.singletonList(x509Data));

            // Executa a assinatura digital
            DOMSignContext dsc = new DOMSignContext(privateKey, xml.getDocumentElement());
            XMLSignature signature = fac.newXMLSignature(si, ki);
            signature.sign(dsc);

            // Retorna o XML assinado
            java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
            javax.xml.transform.TransformerFactory.newInstance()
                    .newTransformer()
                    .transform(new javax.xml.transform.dom.DOMSource(xml),
                            new javax.xml.transform.stream.StreamResult(os));

            return os.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Erro ao assinar XML fiscal: " + e.getMessage(), e);
        }
    }
}
