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
 * SERVIÇO: NfeTransmitServiceImpl – Versão Revisada SEFAZ 4.00
 * =============================================================================
 * Ajustes realizados:
 *  - Uso adequado do SSLContext (mTLS)
 *  - SOAPAction corrigida (SEFAZ rejeita headers incorretos)
 *  - Timeout ajustado conforme recomendação SEFAZ
 *  - Tratamento de erros e resposta padronizada
 *  - Extração da chave mais robusta
 *  - Logs fiscais com maior rastreabilidade
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

    /**
     * Envia NF-e assinada para a SEFAZ-SP via SOAP 1.2 + mTLS.
     */
    @Override
    public String transmitirXml(String xmlAssinado, String cnpjEmitente) {

        String chave = extrairChave(xmlAssinado);

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chave)
                .tipoEvento("ENVIO_NFE")
                .descricao("Iniciando transmissão NF-e para SEFAZ-SP")
                .status("PENDING")
                .dataEvento(LocalDateTime.now())
                .cnpjEmitente(cnpjEmitente)
                .xmlEnvio(xmlAssinado)
                .usuario("system")
                .build();

        try {

            String envelope = criarEnvelopeSoap(xmlAssinado);
            String resposta = enviarSoap(envelope);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setDescricao("NF-e transmitida com sucesso.");
            logFiscal.setXmlRetorno(resposta);
            logFiscal.setDataEvento(LocalDateTime.now());

            salvarLogSeguro(logFiscal);

            log.info("[NF-e] Transmissão realizada com sucesso. Chave={} CNPJ={}", chave, cnpjEmitente);
            return resposta;

        } catch (Exception e) {

            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Erro ao transmitir NF-e: " + e.getMessage());
            logFiscal.setDataEvento(LocalDateTime.now());
            salvarLogSeguro(logFiscal);

            log.error("[NF-e] Falha crítica na transmissão da SEFAZ: {}", e.getMessage(), e);
            return "<erro>" + e.getMessage() + "</erro>";
        }
    }

    /**
     * Extração robusta da chave.
     */
    private String extrairChave(String xml) {
        try {
            int idx = xml.indexOf("Id=\"NFe");
            if (idx > 0) {
                int start = xml.indexOf("NFe", idx) + 3;
                int end = xml.indexOf("\"", start);
                return xml.substring(start, end);
            }
        } catch (Exception ignore) {}
        return "SEM-CHAVE";
    }

    /**
     * Envelope SOAP 1.2 obrigatório na SEFAZ.
     */
    private String criarEnvelopeSoap(String xmlAssinado) {
        return """
            <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
              <soap12:Body>
                <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
            """ +
                xmlAssinado +
                """
                </nfeDadosMsg>
              </soap12:Body>
            </soap12:Envelope>
            """;
    }

    /**
     * Envia o SOAP usando mTLS com certificado A1.
     */
    private String enviarSoap(String envelope) {

        try {
            SSLContext ssl = certificadoService.getSslContext();
            if (ssl == null) {
                throw new IllegalStateException("Certificado A1 não carregado no SSLContext.");
            }

            URL url = new URL(urlAutorizacao);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

            // mTLS habilitado
            conn.setSSLSocketFactory(ssl.getSocketFactory());
            conn.setRequestMethod("POST");

            conn.setRequestProperty(
                    "Content-Type",
                    "application/soap+xml; charset=utf-8"
            );

            conn.setRequestProperty(
                    "SOAPAction",
                    "http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4/nfeAutorizacaoLote"
            );

            conn.setConnectTimeout(20000); // recomendado SEFAZ
            conn.setReadTimeout(45000);
            conn.setDoOutput(true);

            // Envio do envelope
            try (OutputStream os = conn.getOutputStream()) {
                os.write(envelope.getBytes(StandardCharsets.UTF_8));
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader br;

            if (conn.getResponseCode() >= 400) {
                br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
            } else {
                br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            }

            String line;
            while ((line = br.readLine()) != null) sb.append(line.trim());
            br.close();

            return sb.toString();

        } catch (Exception e) {
            throw new RuntimeException("Erro na comunicação com a SEFAZ: " + e.getMessage(), e);
        }
    }

    private void salvarLogSeguro(NfeLog logFiscal) {
        try {
            nfeLogMapper.insertLog(logFiscal);
        } catch (Exception e) {
            log.error("[NF-e] Falha ao registrar log fiscal: {}", e.getMessage());
        }
    }

    @Override
    public String consultarStatus() {
        return "Status SEFAZ (stub): serviço disponível.";
    }
}
