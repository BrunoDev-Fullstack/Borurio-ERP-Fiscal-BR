package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * =============================================================================
 * SERVIÇO FISCAL — CONSULTA STATUS SEFAZ-SP (NF-e 4.00)
 * =============================================================================
 * Implementação real do WebService NFeStatusServico4.
 *
 * Comunicação:
 *   • HTTPS (TLS 1.2+)
 *   • mTLS com Certificado Digital A1 (ICP-Brasil)
 *
 * Ambientes:
 *   • Homologação
 *   • Produção
 *
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-fiscal
 * =============================================================================
 */
@Service
@Profile({"dev", "hom", "prd"})
public class NfeStatusServiceImpl implements NfeStatusService {

    private static final Logger log = LoggerFactory.getLogger(NfeStatusServiceImpl.class);

    private final CertificadoService certificadoService;

    // -------------------------------------------------------------------------
    // CONFIGURAÇÕES — SEFAZ / NF-e
    // -------------------------------------------------------------------------
    @Value("${sefaz.urls.status:https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx}")
    private String sefazUrl;

    @Value("${fiscal.ws.status.versao:4.00}")
    private String versao;

    @Value("${fiscal.ws.status.uf:35}")
    private String codigoUf;

    @Value("${fiscal.ws.status.tpAmb:2}")
    private String tipoAmbiente;

    @Value("${fiscal.ws.status.timeout.connect:10000}")
    private int connectTimeout;

    @Value("${fiscal.ws.status.timeout.read:15000}")
    private int readTimeout;

    public NfeStatusServiceImpl(CertificadoService certificadoService) {
        this.certificadoService = certificadoService;
    }

    /**
     * Consulta o Status do Serviço da NF-e na SEFAZ-SP.
     *
     * @return XML bruto retornado pela SEFAZ, com namespaces normalizados.
     */
    @Override
    public String consultarStatusServico() {

        log.info("[NF-e][STATUS] Iniciando consulta | UF={} | tpAmb={}", codigoUf, tipoAmbiente);

        try {
            // -----------------------------------------------------------------
            // URL DE SERVIÇO
            // -----------------------------------------------------------------
            if (sefazUrl == null || sefazUrl.isBlank()) {
                sefazUrl = "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx";
                log.warn("[NF-e][STATUS] URL SEFAZ não configurada. Aplicando padrão: {}", sefazUrl);
            }

            // -----------------------------------------------------------------
            // SSL CONTEXT (CERTIFICADO A1)
            // -----------------------------------------------------------------
            SSLContext sslContext = certificadoService.getSslContext();
            if (sslContext == null) {
                log.error("[NF-e][STATUS] SSLContext indisponível. Certificado fiscal não carregado.");
                return "<erro>ssl_context_indisponivel</erro>";
            }

            // -----------------------------------------------------------------
            // SOAP ENVELOPE — NF-e 4.00
            // -----------------------------------------------------------------
            String soapEnvelope =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">"
                            + "  <soap12:Body>"
                            + "    <nfeStatusServicoNF xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4\">"
                            + "      <nfeDadosMsg>"
                            + "        <consStatServ xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"" + versao + "\">"
                            + "          <tpAmb>" + tipoAmbiente + "</tpAmb>"
                            + "          <cUF>" + codigoUf + "</cUF>"
                            + "          <xServ>STATUS</xServ>"
                            + "        </consStatServ>"
                            + "      </nfeDadosMsg>"
                            + "    </nfeStatusServicoNF>"
                            + "  </soap12:Body>"
                            + "</soap12:Envelope>";

            // -----------------------------------------------------------------
            // CONEXÃO HTTPS
            // -----------------------------------------------------------------
            URL url = new URL(sefazUrl);
            HttpsURLConnection conexao = (HttpsURLConnection) url.openConnection();

            conexao.setSSLSocketFactory(sslContext.getSocketFactory());
            conexao.setRequestMethod("POST");
            conexao.setDoOutput(true);
            conexao.setConnectTimeout(connectTimeout);
            conexao.setReadTimeout(readTimeout);

            conexao.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conexao.setRequestProperty(
                    "SOAPAction",
                    "http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4/nfeStatusServicoNF"
            );

            // -----------------------------------------------------------------
            // ENVIO DO SOAP
            // -----------------------------------------------------------------
            try (OutputStream os = conexao.getOutputStream()) {
                os.write(soapEnvelope.getBytes(StandardCharsets.UTF_8));
            }

            int httpCode = conexao.getResponseCode();
            log.info("[NF-e][STATUS] HTTP {} recebido da SEFAZ-SP", httpCode);

            InputStream inputStream = httpCode >= 400
                    ? conexao.getErrorStream()
                    : conexao.getInputStream();

            if (inputStream == null) {
                log.error("[NF-e][STATUS] Resposta nula da SEFAZ.");
                return "<erro>resposta_nula</erro>";
            }

            // -----------------------------------------------------------------
            // LEITURA DA RESPOSTA
            // -----------------------------------------------------------------
            StringBuilder resposta = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

                String linha;
                while ((linha = br.readLine()) != null) {
                    resposta.append(linha);
                }
            }

            String xml = resposta.toString();
            if (xml.isBlank()) {
                log.warn("[NF-e][STATUS] XML vazio retornado pela SEFAZ.");
                return "<erro>xml_vazio</erro>";
            }

            // -----------------------------------------------------------------
            // NORMALIZAÇÃO DE NAMESPACES
            // -----------------------------------------------------------------
            String xmlLimpo = xml.replaceAll("(?i)<(/)?([a-zA-Z0-9_-]+:)", "<$1");

            // -----------------------------------------------------------------
            // LOG DO cStat (SE DISPONÍVEL)
            // -----------------------------------------------------------------
            if (xmlLimpo.contains("<cStat>")) {
                try {
                    String cStat = xmlLimpo.split("<cStat>")[1].split("</cStat>")[0];
                    log.info("[NF-e][STATUS] cStat recebido: {}", cStat);
                } catch (Exception ex) {
                    log.warn("[NF-e][STATUS] Falha ao extrair cStat do XML.");
                }
            }

            return xmlLimpo;

        } catch (Exception e) {
            log.error("[NF-e][STATUS] Erro na consulta à SEFAZ-SP", e);
            return "<erro>" + e.getClass().getSimpleName() + ": " + e.getMessage() + "</erro>";
        }
    }
}
