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
 * Serviço responsável por transmitir NF-e 4.00 para a SEFAZ-SP usando SOAP 1.2
 * e autenticação mútua (mTLS) com certificado A1 do tipo .pfx.
 */
@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;

    @Value("${sefaz.urls.autorizacao}")
    private String urlAutorizacao;

    @Value("${sefaz.urls.retorno}")
    private String urlRetorno;

    @Value("${sefaz.urls.consulta}")
    private String urlConsulta;

    @Value("${sefaz.urls.status}")
    private String urlStatus;

    @Value("${sefaz.urls.inutilizacao}")
    private String urlInutilizacao;

    @Value("${sefaz.urls.recepcao-evento}")
    private String urlRecepcaoEvento;

    @Value("${fiscal.cert.path}")
    private String certificadoPath;

    @Value("${fiscal.cert.pass}")
    private String certificadoSenha;


    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService
    ) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
    }

    /**
     * Envia NF-e (XML assinado) para a SEFAZ-SP.
     */
    @Override
    public String transmitirXml(String xmlAssinado, String cnpjEmitente) {

        String chaveNfe = extrairChaveNFe(xmlAssinado);

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chaveNfe)
                .tipoEvento("ENVIO_NFE")
                .descricao("Iniciando transmissão NF-e")
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
            logFiscal.setDescricao("NF-e transmitida com sucesso");
            logFiscal.setXmlRetorno(resposta);
            logFiscal.setDataEvento(LocalDateTime.now());

            salvarLogSeguro(logFiscal);

            log.info("[NF-e] Transmissão concluída. CNPJ={} | Chave={}", cnpjEmitente, chaveNfe);
            return resposta;

        } catch (Exception e) {

            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Erro ao transmitir NF-e: " + e.getMessage());
            logFiscal.setDataEvento(LocalDateTime.now());

            salvarLogSeguro(logFiscal);

            log.error("[NF-e] Falha geral na transmissão: {}", e.getMessage(), e);
            return "<erro>" + e.getMessage() + "</erro>";
        }
    }

    /**
     * Retorna stub de status da SEFAZ em homologação.
     */
    @Override
    public String consultarStatus() {
        return "Serviço SEFAZ-SP disponível (stub homologação).";
    }


    /**
     * Extrai a chave NF-e a partir da tag Id="NFe...".
     */
    private String extrairChaveNFe(String xml) {
        try {
            if (xml != null && xml.contains("Id=\"NFe")) {
                int start = xml.indexOf("Id=\"NFe") + 4;
                int end = xml.indexOf("\"", start);
                return xml.substring(start, end).replace("NFe", "").trim();
            }
        } catch (Exception e) {
            log.warn("[NF-e] Falha ao extrair chave: {}", e.getMessage());
        }
        return "SEM-CHAVE";
    }


    /**
     * Cria envelope SOAP 1.2 para Autorização NF-e.
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
     * Envia o envelope SOAP via HTTPS + mTLS (certificado A1).
     */
    private String enviarSoap(String envelope) {

        try {
            SSLContext ssl = certificadoService.getSslContext();

            if (ssl == null) {
                throw new IllegalStateException("Certificado A1 não carregado.");
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
                while ((line = br.readLine()) != null) resp.append(line.trim());
            }

            return resp.toString();

        } catch (Exception e) {
            throw new RuntimeException("Erro no envio SOAP SEFAZ: " + e.getMessage(), e);
        }
    }


    /**
     * Persistência protegida do log fiscal.
     */
    private void salvarLogSeguro(NfeLog logFiscal) {
        try {
            nfeLogMapper.insertLog(logFiscal);
        } catch (Exception e) {
            log.error("[NF-e] Falha ao registrar log fiscal: {}", e.getMessage());
        }
    }
}
