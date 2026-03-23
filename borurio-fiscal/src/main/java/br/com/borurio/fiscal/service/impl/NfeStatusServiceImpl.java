package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.service.CertificadoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Service
@Profile({"dev","hom","prd"})
public class NfeStatusServiceImpl {

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

    public String consultarStatusServico() {

        logger.info("Iniciando consulta de status da SEFAZ");

        try {

            SSLContext sslContext = certificadoService.getSslContext();

            if (sslContext == null) {
                logger.error("SSLContext não inicializado. Certificado não carregado.");
                return "<erro>Certificado não carregado</erro>";
            }

            String soapEnvelope =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">"
                            + "<soap12:Body>"
                            + "<nfeStatusServicoNF xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4\">"
                            + "<nfeDadosMsg>"
                            + "<consStatServ xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"4.00\">"
                            + "<tpAmb>2</tpAmb>"
                            + "<cUF>35</cUF>"
                            + "<xServ>STATUS</xServ>"
                            + "</consStatServ>"
                            + "</nfeDadosMsg>"
                            + "</nfeStatusServicoNF>"
                            + "</soap12:Body>"
                            + "</soap12:Envelope>";

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

            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

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

            StringBuilder response = new StringBuilder();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {

                String line;

                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            return response.toString();

        } catch (Exception ex) {

            logger.error("Erro ao consultar status da SEFAZ", ex);

            return "<erro>" + ex.getMessage() + "</erro>";
        }
    }
}