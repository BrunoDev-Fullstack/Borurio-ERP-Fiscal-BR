package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.CertificadoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * =============================================================================
 * SERVIÇO: NfeStatusServiceImpl
 * -----------------------------------------------------------------------------
 * Função:
 *   Realiza a consulta de status operacional da SEFAZ-SP (NFeStatusServico4)
 *   utilizando certificado digital A1 (.pfx) e comunicação HTTPS mútua (TLS 1.2+).
 *
 * Contexto:
 *   - Sprint Fiscal 3.4 — Integração Real SEFAZ-SP / NF-e 4.00
 *   - Endpoint: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx
 *
 * Boas práticas DevSecOps:
 *   • Comunicação segura HTTPS com autenticação mútua
 *   • Timeouts configuráveis via application.yml
 *   • Nenhum dado sensível exposto em log
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Java / DevSecOps
 * Data: 29/10/2025
 */
@Service
@Profile({"dev", "hom", "prd"})
public class NfeStatusServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(NfeStatusServiceImpl.class);

    private final CertificadoService certificadoService;

    @Value("${fiscal.ws.status.url:https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx}")
    private String sefazUrl;

    @Value("${fiscal.ws.status.versao:4.00}")
    private String versao;

    @Value("${fiscal.ws.status.uf:35}") // 35 = SP
    private String codigoUf;

    @Value("${fiscal.ws.status.tpAmb:2}") // 2 = Homologação
    private String tipoAmbiente;

    @Value("${fiscal.ws.status.timeout.connect:10000}")
    private int connectTimeout;

    @Value("${fiscal.ws.status.timeout.read:15000}")
    private int readTimeout;

    public NfeStatusServiceImpl(CertificadoService certificadoService) {
        this.certificadoService = certificadoService;
    }

    /**
     * Consulta o status do serviço NF-e na SEFAZ-SP (Homologação ou Produção).
     *
     * @return XML limpo da resposta SOAP ou XML de erro formatado.
     */
    public String consultarStatusServico() {
        logger.info("Iniciando consulta de status à SEFAZ-SP [UF={}, Ambiente={}]", codigoUf, tipoAmbiente);

        try {
            // Garante o endpoint padrão
            if (sefazUrl == null || sefazUrl.isBlank()) {
                sefazUrl = "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx";
                logger.warn("sefazUrl não definido via Spring — aplicando valor padrão: {}", sefazUrl);
            }

            // Obtém contexto SSL do certificado digital
            SSLContext sslContext = certificadoService.getSslContext();
            if (sslContext == null) {
                logger.error("SSLContext indisponível — certificado A1 não carregado.");
                return "<erro>Certificado não carregado</erro>";
            }

            // Corpo SOAP (NF-e 4.00)
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

            // Configuração da conexão HTTPS
            URL url = new URL(sefazUrl);
            HttpsURLConnection conexao = (HttpsURLConnection) url.openConnection();
            conexao.setSSLSocketFactory(sslContext.getSocketFactory());
            conexao.setRequestMethod("POST");
            conexao.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conexao.setRequestProperty("SOAPAction",
                    "http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4/nfeStatusServicoNF");
            conexao.setConnectTimeout(connectTimeout);
            conexao.setReadTimeout(readTimeout);
            conexao.setDoOutput(true);

            // Envio da requisição SOAP
            try (OutputStream os = conexao.getOutputStream()) {
                os.write(soapEnvelope.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // Leitura da resposta SOAP
            int httpCode = conexao.getResponseCode();
            logger.info("Resposta HTTP {} recebida da SEFAZ-SP", httpCode);

            StringBuilder resposta = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conexao.getInputStream(), StandardCharsets.UTF_8))) {
                String linha;
                while ((linha = br.readLine()) != null) {
                    resposta.append(linha);
                }
            }

            String xmlResposta = resposta.toString();
            if (xmlResposta.isEmpty()) {
                logger.warn("Resposta vazia recebida da SEFAZ-SP.");
                return "<erro>Resposta vazia</erro>";
            }

            // Limpeza de namespaces e extração de código de status
            String xmlLimpo = xmlResposta.replaceAll("(?i)<(/)?([a-zA-Z0-9_\\-:]+:)", "<$1");

            if (xmlLimpo.contains("<cStat>")) {
                int ini = xmlLimpo.indexOf("<cStat>") + 7;
                int fim = xmlLimpo.indexOf("</cStat>");
                if (fim > ini) {
                    String cStat = xmlLimpo.substring(ini, fim);
                    logger.info("SEFAZ-SP retornou cStat={} (Status do Serviço)", cStat);
                } else {
                    logger.warn("Elemento <cStat> encontrado, mas sem valor válido.");
                }
            } else {
                logger.warn("Elemento <cStat> não encontrado no XML retornado.");
            }

            return xmlLimpo;

        } catch (Exception e) {
            logger.error("Falha ao consultar status NF-e na SEFAZ-SP: {}", e.getMessage(), e);
            return "<erro>" + e.getClass().getSimpleName() + ": " + e.getMessage() + "</erro>";
        }
    }
}
