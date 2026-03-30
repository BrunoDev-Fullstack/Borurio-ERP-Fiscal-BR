package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.mapper.NfeLogMapper;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;

    @Value("${sefaz.urls.autorizacao}")
    private String urlAutorizacao;

    @Value("${sefaz.urls.status}")
    private String urlStatus;

    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService
    ) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
    }

    // =========================
    // ENVIO NF-e
    // =========================
    @Override
    public String transmitirXml(String xmlAssinado,
                                String cnpjEmitente,
                                String uf,
                                int ambiente) {

        String chaveNfe = extrairChaveNFe(xmlAssinado);

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chaveNfe)
                .tipoEvento("ENVIO_NFE")
                .descricao("Transmissão NF-e UF=" + uf + " Amb=" + ambiente)
                .status("PENDING")
                .dataEvento(LocalDateTime.now())
                .cnpjEmitente(cnpjEmitente)
                .xmlEnvio(xmlAssinado)
                .usuario("system")
                .build();

        try {

            String envelope = criarEnvelopeSoap(xmlAssinado);
            String resposta = enviarSoap(urlAutorizacao, envelope);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setDescricao("NF-e transmitida com sucesso");
            logFiscal.setXmlRetorno(resposta);
            logFiscal.setDataEvento(LocalDateTime.now());

            salvarLogSeguro(logFiscal);

            log.info("[NF-e] Transmissão OK | UF={} | Amb={} | Chave={}", uf, ambiente, chaveNfe);

            return resposta;

        } catch (Exception e) {

            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Erro: " + e.getMessage());
            logFiscal.setDataEvento(LocalDateTime.now());

            salvarLogSeguro(logFiscal);

            log.error("[NF-e] Erro transmissão", e);

            return "<erro>" + e.getMessage() + "</erro>";
        }
    }

    // =========================
    // STATUS SEFAZ
    // =========================
    @Override
    public String consultarStatus(String uf, int ambiente) {

        try {

            String envelope = """
                <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
                    <soap12:Body>
                        <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4">
                            <consStatServ versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
                                <tpAmb>%d</tpAmb>
                                <cUF>35</cUF>
                                <xServ>STATUS</xServ>
                            </consStatServ>
                        </nfeDadosMsg>
                    </soap12:Body>
                </soap12:Envelope>
                """.formatted(ambiente);

            return enviarSoap(urlStatus, envelope);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao consultar status SEFAZ", e);
        }
    }

    // =========================
    // SOAP CORE
    // =========================
    private String enviarSoap(String urlWs, String envelope) throws Exception {

        SSLContext ssl = certificadoService.getSslContext();

        URL url = new URL(urlWs);
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
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                resp.append(line.trim());
            }
        }

        return resp.toString();
    }

    // =========================
    // HELPERS
    // =========================
    private String extrairChaveNFe(String xml) {
        try {
            if (xml.contains("Id=\"NFe")) {
                int start = xml.indexOf("Id=\"NFe") + 4;
                int end = xml.indexOf("\"", start);
                return xml.substring(start, end).replace("NFe", "");
            }
        } catch (Exception e) {
            log.warn("Erro ao extrair chave", e);
        }
        return "SEM-CHAVE";
    }

    private String criarEnvelopeSoap(String xmlAssinado) {
        return """
            <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
                <soap12:Body>
                    <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
            """ + xmlAssinado + """
                    </nfeDadosMsg>
                </soap12:Body>
            </soap12:Envelope>
            """;
    }

    private void salvarLogSeguro(NfeLog logFiscal) {
        try {
            nfeLogMapper.insertLog(logFiscal);
        } catch (Exception e) {
            log.error("[NF-e] Falha ao salvar log", e);
        }
    }
}