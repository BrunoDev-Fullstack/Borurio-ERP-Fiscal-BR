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

/**
 * =============================================================================
 * IMPLEMENTAÇÃO — NfeTransmitServiceImpl
 * -----------------------------------------------------------------------------
 * Comunicação REAL com SEFAZ-SP:
 *   • SOAP 1.2
 *   • HTTPS + mTLS com certificado A1
 *   • Validação XML / retorno SOAP completo
 *   • Logs persistidos na tabela nfe_log
 *
 * Padrão NF-e 4.00 — NFeAutorizacao4 e NFeStatusServico4
 * =============================================================================
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * Revisão: 03/12/2025
 * =============================================================================
 */
@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;

    @Value("${sefaz.urls.autorizacao}")
    private String urlAutorizacao;

    @Value("${sefaz.urls.status}")
    private String urlStatusServico;

    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService
    ) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
    }

    // =============================================================================
    // CONSULTA STATUS SERVIÇO — NFeStatusServico4
    // =============================================================================
    @Override
    public String consultarStatusServico(String cnpjEmitente) {

        try {
            SSLContext ssl = certificadoService.getSslContext();

            String envelope = criarEnvelopeStatus(cnpjEmitente);

            URL url = new URL(urlStatusServico);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

            conn.setSSLSocketFactory(ssl.getSocketFactory());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(envelope.getBytes(StandardCharsets.UTF_8));
            }

            int http = conn.getResponseCode();

            if (http == 200) return ler(conn.getInputStream());

            return ler(conn.getErrorStream());

        } catch (Exception e) {
            log.error("[NF-e] Erro ao consultar status serviço: {}", e.getMessage());
            return "<erro>Falha ao consultar status SEFAZ: " + e.getMessage() + "</erro>";
        }
    }

    private String criarEnvelopeStatus(String cnpj) {
        return """
                <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope"
                                 xmlns:nfe="http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4">
                    <soap12:Body>
                        <nfe:nfeDadosMsg>
                            <consStatServ versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
                                <tpAmb>2</tpAmb>
                                <cUF>35</cUF>
                                <xServ>STATUS</xServ>
                            </consStatServ>
                        </nfe:nfeDadosMsg>
                    </soap12:Body>
                </soap12:Envelope>
                """;
    }

    // =============================================================================
    // TRANSMISSÃO REAL DA NF-e — NFeAutorizacao4
    // =============================================================================
    @Override
    public String transmitirXml(String xmlAssinado, String cnpjEmitente) {

        validarRequisitos(xmlAssinado);

        String chave = extrairChave(xmlAssinado);

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chave)
                .tipoEvento("ENVIO_NFE")
                .status("PENDING")
                .descricao("Transmissão iniciada")
                .dataEvento(LocalDateTime.now())
                .cnpjEmitente(cnpjEmitente)
                .xmlEnvio(xmlAssinado)
                .usuario("system")
                .build();

        try {
            String envelope = criarEnvelopeAutorizacao(xmlAssinado);
            String resposta = enviarSoap(envelope, urlAutorizacao);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setDescricao("NF-e transmitida com sucesso");
            logFiscal.setXmlRetorno(resposta);
            salvarLog(logFiscal);

            log.info("[NF-e] SUCESSO | CHAVE={} | CNPJ={}", chave, cnpjEmitente);
            return resposta;

        } catch (Exception e) {

            String erro = "Erro SEFAZ: " + e.getMessage();

            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao(erro);
            salvarLog(logFiscal);

            log.error("[NF-e] FALHA | CHAVE={} | ERRO={}", chave, e.getMessage());
            return "<erro>" + erro + "</erro>";
        }
    }

    private String criarEnvelopeAutorizacao(String xmlAssinado) {
        return """
                <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope"
                                 xmlns:nfe="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
                    <soap12:Body>
                        <nfe:nfeDadosMsg>
                """ + xmlAssinado + """
                        </nfe:nfeDadosMsg>
                    </soap12:Body>
                </soap12:Envelope>
                """;
    }

    // =============================================================================
    // VALIDAÇÕES
    // =============================================================================
    private void validarRequisitos(String xml) {

        if (xml == null || xml.isBlank())
            throw new IllegalArgumentException("XML assinado está vazio.");

        if (certificadoService.getSslContext() == null)
            throw new IllegalStateException("Certificado A1 não carregado.");

        if (urlAutorizacao == null || urlAutorizacao.isBlank())
            throw new IllegalStateException("URL SEFAZ - Autorização não configurada.");

        if (urlStatusServico == null || urlStatusServico.isBlank())
            throw new IllegalStateException("URL SEFAZ - Status Serviço não configurada.");
    }

    // =============================================================================
    // EXTRAÇÃO DE CHAVE
    // =============================================================================
    private String extrairChave(String xml) {
        try {
            int i = xml.indexOf("Id=\"NFe");
            if (i > 0) {
                int start = i + 4;
                int end = xml.indexOf("\"", start);
                return xml.substring(start, end).replace("NFe", "").trim();
            }
        } catch (Exception ignored) {}
        return "SEM-CHAVE";
    }

    // =============================================================================
    // ENVIO SOAP VIA HTTPS mTLS
    // =============================================================================
    private String enviarSoap(String envelope, String urlWs) {

        try {
            SSLContext ssl = certificadoService.getSslContext();

            URL url = new URL(urlWs);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

            conn.setSSLSocketFactory(ssl.getSocketFactory());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(envelope.getBytes(StandardCharsets.UTF_8));
            }

            int http = conn.getResponseCode();

            if (http == 200)
                return ler(conn.getInputStream());

            return ler(conn.getErrorStream());

        } catch (Exception e) {
            throw new RuntimeException("Erro SOAP SEFAZ: " + e.getMessage(), e);
        }
    }

    private String ler(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = br.readLine()) != null) sb.append(l);
        return sb.toString();
    }

    // =============================================================================
    // LOG FISCAL
    // =============================================================================
    private void salvarLog(NfeLog logFiscal) {
        try {
            nfeLogMapper.insertLog(logFiscal);
        } catch (Exception e) {
            log.error("[NF-e] ERRO AO SALVAR LOG: {}", e.getMessage());
        }
    }
}
