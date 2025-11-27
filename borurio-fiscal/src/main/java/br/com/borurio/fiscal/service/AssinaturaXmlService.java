package br.com.borurio.fiscal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * =============================================================================
 * SERVIÇO: AssinaturaXmlService
 * -----------------------------------------------------------------------------
 * Assina digitalmente o XML da NF-e usando certificado A1 .pfx.
 * Compatível com a NF-e 4.00 (SHA256 + RSA).
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Slf4j
@Service
public class AssinaturaXmlService {

    @Value("${fiscal.cert.path}")
    private String certPath;

    @Value("${fiscal.cert.pass}")
    private String certPass;

    /**
     * Assina o XML fiscal com o certificado A1.
     */
    public byte[] assinarXml(byte[] xmlBytes) {
        try {
            // =====================================================================
            // 1) CARREGA O CERTIFICADO A1 DO PFX
            // =====================================================================
            KeyStore ks = KeyStore.getInstance("PKCS12");

            try (FileInputStream fis = new FileInputStream(certPath)) {
                ks.load(fis, certPass.toCharArray());
            }

            String alias = ks.aliases().nextElement();
            PrivateKey privateKey = (PrivateKey) ks.getKey(alias, certPass.toCharArray());
            X509Certificate certificate = (X509Certificate) ks.getCertificate(alias);

            // =====================================================================
            // 2) PARSE DO XML
            // =====================================================================
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);

            Document xml = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xmlBytes));

            NodeList nodeList = xml.getElementsByTagName("infNFe");
            if (nodeList.getLength() == 0) {
                throw new RuntimeException("Tag <infNFe> não encontrada.");
            }

            Element infNFe = (Element) nodeList.item(0);
            String id = infNFe.getAttribute("Id");

            if (id == null || id.isBlank()) {
                throw new RuntimeException("Atributo Id ausente em <infNFe>.");
            }

            infNFe.setIdAttribute("Id", true);

            // =====================================================================
            // 3) CRIA A ASSINATURA XMLDSIG - PADRÃO SEFAZ
            // =====================================================================
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

            Reference ref = fac.newReference(
                    "#" + id,
                    fac.newDigestMethod(DigestMethod.SHA256, null),
                    Collections.singletonList(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
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

            // =====================================================================
            // 4) CONVERTE PARA BYTE[]
            // =====================================================================
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            javax.xml.transform.TransformerFactory.newInstance()
                    .newTransformer()
                    .transform(new javax.xml.transform.dom.DOMSource(xml),
                            new javax.xml.transform.stream.StreamResult(os));

            log.info("[NF-e] XML assinado com sucesso usando certificado A1: {}", certificate.getSubjectDN());
            return os.toByteArray();

        } catch (Exception e) {
            log.error("[NF-e] Erro ao assinar XML: {}", e.getMessage(), e);
            throw new RuntimeException("Erro ao assinar XML fiscal: " + e.getMessage(), e);
        }
    }
}
