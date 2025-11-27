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
 * SERVIÇO: NfeTransmitServiceImpl
 * -----------------------------------------------------------------------------
 * Transmissão real da NF-e 4.00 via:
 *   - SOAP 1.2
 *   - HTTPS + mTLS com certificado A1
 *   - Logs fiscais persistidos (auditoria completa)
 *
 * Ambientes suportados:
 *   DEV/HOM  → SEFAZ Homologação (tpAmb=2)
 *   PRD      → SEFAZ Produção (tpAmb=1)
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * Revisão Final: 26/11/2025
 * =============================================================================
 */
@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;

    @Value("${sefaz.urls.autorizacao}")
    private String urlAutorizacao;

    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService
    ) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
    }

    @Override
    public String consultarStatus() {
        return "Serviço SEFAZ-SP disponível (stub local).";
    }

    // =========================================================================
    // TRANSMISSÃO REAL DA NF-e
    // =========================================================================
    @Override
    public String transmitirXml(String xmlAssinado, String cnpjEmitente) {

        validarRequisitos(xmlAssinado);

        String chaveNfe = extrairChaveNFe(xmlAssinado);

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chaveNfe)
                .tipoEvento("ENVIO_NFE")
                .status("PENDING")
                .descricao("Iniciando transmissão da NF-e")
                .dataEvento(LocalDateTime.now())
                .cnpjEmitente(cnpjEmitente)
                .xmlEnvio(xmlAssinado)
                .usuario("system")
                .build();

        try {

            String envelope = criarEnvelopeSoap(xmlAssinado);
            String resposta = enviarSoap(envelope);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setDescricao("NF-e transmitida com sucesso");
            logFiscal.setXmlRetorno(resposta);
            salvarLogSeguro(logFiscal);

            log.info("[NF-e] SUCESSO | CHAVE={} | CNPJ={}", chaveNfe, cnpjEmitente);
            return resposta;

        } catch (Exception e) {

            String erro = "Erro interno durante a transmissão: " + e.getMessage();

            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao(erro);
            salvarLogSeguro(logFiscal);

            log.error("[NF-e] FALHA | CHAVE={} | Motivo={}", chaveNfe, e.getMessage(), e);
            return "<erro>" + erro + "</erro>";
        }
    }

    // =========================================================================
    // VALIDAÇÕES
    // =========================================================================
    private void validarRequisitos(String xml) {

        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML assinado está vazio.");
        }

        if (certificadoService.getSslContext() == null) {
            throw new IllegalStateException("Certificado A1 não carregado.");
        }

        if (urlAutorizacao == null || urlAutorizacao.isBlank()) {
            throw new IllegalStateException("URL de autorização SEFAZ não configurada.");
        }
    }

    // =========================================================================
    // EXTRAÇÃO DA CHAVE DA NF-e
    // =========================================================================
    private String extrairChaveNFe(String xml) {
        try {
            // Extrai atributo Id="NFeXXXXXXXXXXXXXXXXXXXX"
            int pos = xml.indexOf("Id=\"NFe");
            if (pos > 0) {
                int start = pos + 4;
                int end = xml.indexOf("\"", start);
                String chave = xml.substring(start, end).replace("NFe", "");
                return chave.trim();
            }
        } catch (Exception ignored) {}
        return "SEM-CHAVE";
    }

    // =========================================================================
    // SOAP 1.2 OFICIAL – SEFAZ-SP
    // =========================================================================
    private String criarEnvelopeSoap(String xmlAssinado) {
        return """
                <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope"
                                  xmlns:nfe="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
                  <soap12:Body>
                    <nfe:nfeDadosMsg>
                """ +
                xmlAssinado +
                """
                    </nfe:nfeDadosMsg>
                  </soap12:Body>
                </soap12:Envelope>
                """;
    }

    // =========================================================================
    // ENVIO SOAP VIA mTLS – SEFAZ
    // =========================================================================
    private String enviarSoap(String envelope) {

        try {

            SSLContext ssl = certificadoService.getSslContext();
            if (ssl == null) {
                throw new IllegalStateException("SSLContext do certificado A1 está nulo.");
            }

            URL url = new URL(urlAutorizacao);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

            conn.setSSLSocketFactory(ssl.getSocketFactory());
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);

            // Envio
            try (OutputStream os = conn.getOutputStream()) {
                os.write(envelope.getBytes(StandardCharsets.UTF_8));
            }

            int httpCode = conn.getResponseCode();

            // =============================================================
            // Lê resposta normal (HTTP 200)
            // =============================================================
            if (httpCode == 200) {
                return lerStream(conn.getInputStream());
            }

            // =============================================================
            // Lê erro SEFAZ (HTTP != 200)
            // =============================================================
            String erro = lerStream(conn.getErrorStream());
            throw new RuntimeException("Falha HTTP SEFAZ: " + httpCode + " - " + erro);

        } catch (Exception e) {
            throw new RuntimeException("Erro no envio SOAP SEFAZ: " + e.getMessage(), e);
        }
    }

    private String lerStream(java.io.InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = br.readLine()) != null) {
                sb.append(linha);
            }
        }
        return sb.toString();
    }

    // =========================================================================
    // LOG FISCAL
    // =========================================================================
    private void salvarLogSeguro(NfeLog logFiscal) {
        try {
            nfeLogMapper.insertLog(logFiscal);
        } catch (Exception e) {
            log.error("[NF-e] Falha ao registrar log fiscal no banco: {}", e.getMessage());
        }
    }
}
