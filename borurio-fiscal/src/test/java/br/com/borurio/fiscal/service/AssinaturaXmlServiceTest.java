package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AssinaturaXmlServiceTest {

    @Test
    void deveAssinarXmlNFe() {

        try {
            // CONFIGURA CERTIFICADO (TESTE)
            System.setProperty("fiscal.certificate.path", "cert/test-cert.pfx");
            System.setProperty("fiscal.certificate.password", "2025@Qz1");
            System.setProperty("fiscal.certificate.type", "PKCS12");

            CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();
            certificadoService.init();

            AssinaturaXmlService service = new AssinaturaXmlService(certificadoService);

            // XML MÍNIMO VÁLIDO
            String xml = """
                    <NFe xmlns="http://www.portalfiscal.inf.br/nfe">
                        <infNFe Id="NFe12345678901234567890123456789012345678901234" versao="4.00">
                            <ide>
                                <cUF>35</cUF>
                            </ide>
                        </infNFe>
                    </NFe>
                    """;

            // EXECUTA ASSINATURA
            String xmlAssinado = service.assinarXml(xml);

            // VALIDAÇÕES
            Assertions.assertNotNull(xmlAssinado);
            Assertions.assertTrue(xmlAssinado.contains("<Signature"));
            Assertions.assertTrue(xmlAssinado.contains("DigestValue"));
            Assertions.assertTrue(xmlAssinado.contains("SignatureValue"));

            System.out.println(xmlAssinado);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}