package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.mapper.NfeLogMapper;
import br.com.borurio.fiscal.service.AssinaturaXmlService;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import br.com.borurio.fiscal.utils.XsdValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * =============================================================================
 * IMPLEMENTAÇÃO: NfeTransmitServiceImpl
 * -----------------------------------------------------------------------------
 * Fluxo completo:
 * - Parse XML
 * - Validação XSD
 * - Assinatura XML
 * - Extração segura de dados fiscais
 * - Envio SOAP (mTLS)
 * - Persistência de log fiscal
 * =============================================================================
 */
@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;
    private final AssinaturaXmlService assinaturaXmlService;
    private final XsdValidator xsdValidator;

    @Value("${sefaz.urls.autorizacao}")
    private String urlAutorizacao;

    /** XSD consolidado da NF-e 4.00 */
    private static final String XSD_NFE = "xsd/custom/nfe_v4.00_consolidado.xsd";

    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService,
            AssinaturaXmlService assinaturaXmlService,
            XsdValidator xsdValidator
    ) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
        this.assinaturaXmlService = assinaturaXmlService;
        this.xsdValidator = xsdValidator;
    }

    @Override
    public String transmitirXml(String xmlBase) {

        if (xmlBase == null || xmlBase.isBlank()) {
            throw new IllegalArgumentException("XML da NF-e está vazio");
        }

        try {
            // ================================================================
            // 1. Parse seguro do XML base
            // ================================================================
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setNamespaceAware(true);
            dbf.setExpandEntityReferences(false);

            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document xmlDoc = builder.parse(new InputSource(new StringReader(xmlBase)));

            // ================================================================
            // 2. Validação XSD
            // ================================================================
            xsdValidator.validate(xmlDoc, XSD_NFE);

            // ================================================================
            // 3. Assinatura XML
            // ================================================================
            byte[] xmlAssinadoBytes =
                    assinaturaXmlService.assinarXml(xmlBase.getBytes(StandardCharsets.UTF_8));

            String xmlAssinado = new String(xmlAssinadoBytes, StandardCharsets.UTF_8);

            // ================================================================
            // 4. Extração segura via XPath
            // ================================================================
            XPath xpath = XPathFactory.newInstance().newXPath();

            String chaveNfe = xpath
                    .evaluate("//*[local-name()='infNFe']/@Id", xmlDoc)
                    .replace("NFe", "");

            String cnpjEmitente = xpath.evaluate(
                    "//*[local-name()='emit']/*[local-name()='CNPJ']",
                    xmlDoc
            );

            NfeLog logFiscal = NfeLog.builder()
                    .chaveNfe(chaveNfe)
                    .tipoEvento("ENVIO_NFE")
                    .descricao("Envio NF-e para SEFAZ")
                    .status("PENDING")
                    .cnpjEmitente(cnpjEmitente)
                    .xmlEnvio(xmlAssinado)
                    .dataEvento(LocalDateTime.now())
                    .usuario("system")
                    .build();

            // ================================================================
            // 5. Envio SOAP
            // ================================================================
            String envelope = criarEnvelopeSoap(xmlAssinado);
            String resposta = enviarSoap(envelope);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setXmlRetorno(resposta);
            logFiscal.setDataEvento(LocalDateTime.now());
            salvarLogSeguro(logFiscal);

            log.info("[NF-e] Envio concluído | CNPJ={} | Chave={}", cnpjEmitente, chaveNfe);
            return resposta;

        } catch (Exception e) {
            log.error("[NF-e] Erro na transmissão", e);
            throw new RuntimeException("Falha ao transmitir NF-e", e);
        }
    }

    @Override
    public String consultarStatus() {
        return "Serviço SEFAZ-SP disponível.";
    }

    private String criarEnvelopeSoap(String xmlAssinado) {
        return """
            <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <soap12:Body>
                <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
            """ + xmlAssinado + """
                </nfeDadosMsg>
              </soap12:Body>
            </soap12:Envelope>
            """;
    }

    private String enviarSoap(String envelope) {
        try {
            SSLContext ssl = certificadoService.getSslContext();
            if (ssl == null) {
                throw new IllegalStateException("Certificado A1 não carregado");
            }

            URL url = new URL(urlAutorizacao);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

            conn.setSSLSocketFactory(ssl.getSocketFactory());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(envelope.getBytes(StandardCharsets.UTF_8));
            }

            StringBuilder resp = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = br.readLine()) != null) {
                    resp.append(line.trim());
                }
            }

            return resp.toString();

        } catch (Exception e) {
            throw new RuntimeException("Erro no envio SOAP SEFAZ", e);
        }
    }

    private void salvarLogSeguro(NfeLog logFiscal) {
        try {
            nfeLogMapper.insertLog(logFiscal);
        } catch (Exception e) {
            log.error("[NF-e] Falha ao registrar log fiscal", e);
        }
    }
}
