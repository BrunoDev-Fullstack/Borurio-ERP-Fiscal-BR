package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Service
@Profile({"dev","hom","prd"})
public class NfeStatusServiceImpl implements NfeStatusService {

    private static final Logger logger =
            LoggerFactory.getLogger(NfeStatusServiceImpl.class);

    private final CertificadoService certificadoService;
    private final SefazProperties sefazProperties;

    public NfeStatusServiceImpl(
            CertificadoService certificadoService,
            SefazProperties sefazProperties) {

        this.certificadoService = certificadoService;
        this.sefazProperties = sefazProperties;
    }

    @Override
    public String consultarStatusServico() {

        logger.info("Iniciando consulta de status da SEFAZ");

        try {

            SSLContext sslContext = certificadoService.getSslContext();

            String soapEnvelope = montarSoapStatus();

            logger.debug("SOAP ENVIADO:\n{}", soapEnvelope);

            String urlSefaz = sefazProperties.getStatus();

            logger.info("Endpoint SEFAZ: {}", urlSefaz);

            URL url = new URL(urlSefaz);

            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

            conn.setSSLSocketFactory(sslContext.getSocketFactory());
            conn.setRequestMethod("POST");

            conn.setRequestProperty(
                    "Content-Type",
                    "application/soap+xml; charset=utf-8"
            );

            conn.setRequestProperty("Accept", "application/soap+xml");
            conn.setRequestProperty("User-Agent", "Borurio-ERP/1.0");

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);

            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(soapEnvelope.getBytes(StandardCharsets.UTF_8));
            }

            int httpCode = conn.getResponseCode();

            logger.info("HTTP response SEFAZ: {}", httpCode);

            InputStream stream =
                    httpCode >= 400
                            ? conn.getErrorStream()
                            : conn.getInputStream();

            String response = lerResposta(stream);

            logger.debug("SOAP RETORNO:\n{}", response);

            validarResposta(response);

            return response;

        } catch (Exception ex) {

            logger.error("Erro ao consultar status da SEFAZ", ex);

            throw new RuntimeException("Falha ao consultar SEFAZ", ex);
        }
    }

    /**
     * IMPORTANTE:
     * - NÃO usar text block ("""")
     * - NÃO usar indentação
     * - NÃO usar quebra de linha
     * - XML deve iniciar exatamente em <?xml
     */
    private String montarSoapStatus() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">" +
                "<soap12:Body>" +
                "<nfeDadosMsg xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4\">" +
                "<consStatServ xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\">" +
                "<tpAmb>2</tpAmb>" +
                "<cUF>35</cUF>" +
                "<xServ>STATUS</xServ>" +
                "</consStatServ>" +
                "</nfeDadosMsg>" +
                "</soap12:Body>" +
                "</soap12:Envelope>";
    }

    private String lerResposta(InputStream stream) throws IOException {

        if (stream == null) {
            return "<erro>Resposta vazia</erro>";
        }

        StringBuilder response = new StringBuilder();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {

            String line;

            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        }

        return response.toString();
    }

    private void validarResposta(String xml) {

        if (xml == null || xml.isEmpty()) {
            throw new RuntimeException("Resposta SEFAZ vazia");
        }

        if (xml.contains("<Fault")) {
            logger.error("SOAP Fault retornado pela SEFAZ:\n{}", xml);
            throw new RuntimeException("Erro SOAP na SEFAZ");
        }

        if (xml.contains("<cStat>107</cStat>")) {
            logger.info("SEFAZ operacional (cStat 107)");
        } else {
            logger.warn("Resposta inesperada da SEFAZ:\n{}", xml);
        }
    }
}