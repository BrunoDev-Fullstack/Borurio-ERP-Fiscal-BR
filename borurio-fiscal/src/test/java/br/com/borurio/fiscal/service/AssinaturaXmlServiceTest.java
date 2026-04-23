package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.service.impl.CertificadoServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AssinaturaXmlServiceTest {

    @Test
    void deveAssinarXmlNFe() throws Exception {

        CertificadoServiceImpl certificadoService = new CertificadoServiceImpl();

        ReflectionTestUtils.setField(certificadoService, "certPath",     "cert/test-cert.pfx");
        ReflectionTestUtils.setField(certificadoService, "certPassword", "2025@Qz1");
        ReflectionTestUtils.setField(certificadoService, "certType",     "PKCS12");

        certificadoService.init();

        AssinaturaXmlService service = new AssinaturaXmlService(certificadoService);

        String xml = """
            <NFe xmlns="http://www.portalfiscal.inf.br/nfe">
                <infNFe Id="NFe12345678901234567890123456789012345678901234" versao="4.00">
                    <ide>
                        <cUF>35</cUF>
                        <natOp>VENDA</natOp>
                        <mod>55</mod>
                        <serie>1</serie>
                        <nNF>1</nNF>
                        <dhEmi>2026-01-01T10:00:00-03:00</dhEmi>
                        <tpNF>1</tpNF>
                        <idDest>1</idDest>
                        <cMunFG>3550308</cMunFG>
                        <tpImp>1</tpImp>
                        <tpEmis>1</tpEmis>
                        <cDV>0</cDV>
                        <tpAmb>2</tpAmb>
                        <finNFe>1</finNFe>
                        <indFinal>1</indFinal>
                        <indPres>1</indPres>
                        <procEmi>0</procEmi>
                        <verProc>1.0</verProc>
                    </ide>
                </infNFe>
            </NFe>
            """;

        String xmlAssinado = service.assinar(xml);

        assertNotNull(xmlAssinado, "XML assinado não pode ser nulo");
        assertTrue(xmlAssinado.contains("<Signature"),
                "XML assinado deve conter o bloco <Signature>");
        assertTrue(xmlAssinado.contains("DigestValue"),
                "XML assinado deve conter DigestValue");
        assertTrue(xmlAssinado.contains("SignatureValue"),
                "XML assinado deve conter SignatureValue");

        System.out.println("XML assinado gerado com sucesso.");
    }
}
