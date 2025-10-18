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

/**
 * =============================================================================
 * Serviço: NfeStatusServiceImpl
 * -----------------------------------------------------------------------------
 * Função:
 *   Consulta o status operacional da SEFAZ-SP (NFeStatusServico4)
 *   utilizando certificado digital A1 (.pfx) e comunicação segura via TLS 1.2.
 *
 * Contexto:
 *   - Sprint Fiscal 2.7 — Integração SEFAZ-SP / NF-e 4.00
 *   - Endpoint: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx
 *
 * Boas práticas DevSecOps:
 *   - Comunicação HTTPS autenticada (mútua)
 *   - Timeout configurável via application.yml
 *   - Nenhum dado sensível exposto em log
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Java / DevSecOps
 * Data: 17/10/2025
 */
@Service
@Profile({"dev", "homolog", "prd"})
public class NfeStatusServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(NfeStatusServiceImpl.class);

    private final CertificadoService certificadoService;

    // Valores padrão definidos para fallback seguro (permitem execução sem Spring)
    @Value("${fiscal.ws.status.url:https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx}")
    private String sefazUrl = "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx";

    @Value("${fiscal.ws.status.versao:4.00}")
    private String versao = "4.00";

    @Value("${fiscal.ws.status.uf:35}") // 35 = São Paulo
    private String codigoUf = "35";

    @Value("${fiscal.ws.status.tpAmb:2}") // 2 = Homologação
    private String tipoAmbiente = "2";

    @Value("${fiscal.ws.status.timeout.connect:10000}")
    private int connectTimeout = 10000;

    @Value("${fiscal.ws.status.timeout.read:15000}")
    private int readTimeout = 15000;

    public NfeStatusServiceImpl(CertificadoService certificadoService) {
        this.certificadoService = certificadoService;
    }

    /**
     * Realiza a consulta de status do serviço NF-e na SEFAZ-SP (Homologação ou Produção).
     *
     * @return XML completo da resposta SOAP ou mensagem de erro formatada.
     */
    public String consultarStatusServico() {
        logger.info("[NfeStatusServiceImpl] Iniciando consulta de status à SEFAZ-SP (Ambiente={}, UF={}).",
                tipoAmbiente, codigoUf);

        // Garante que o endpoint está definido, mesmo fora do Spring context
        if (sefazUrl == null || sefazUrl.isBlank()) {
            sefazUrl = "https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeStatusServico4.asmx";
            logger.warn("[NfeStatusServiceImpl] sefazUrl não definido via Spring — aplicando valor padrão: {}", sefazUrl);
        }

        SSLContext sslContext = certificadoService.getSslContext();
        if (sslContext == null) {
            logger.error("[NfeStatusServiceImpl] SSLContext indisponível — certificado A1 não foi carregado.");
            return "<erro>Certificado não carregado</erro>";
        }

        try {
            // Corpo da requisição SOAP
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

            URL url = new URL(sefazUrl);
            HttpsURLConnection conexao = (HttpsURLConnection) url.openConnection();
            conexao.setSSLSocketFactory(sslContext.getSocketFactory());
            conexao.setRequestMethod("POST");
            conexao.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            conexao.setRequestProperty("SOAPAction", "http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4/nfeStatusServicoNF");
            conexao.setConnectTimeout(connectTimeout);
            conexao.setReadTimeout(readTimeout);
            conexao.setDoOutput(true);

            // Envio do XML SOAP
            try (OutputStream os = conexao.getOutputStream()) {
                os.write(soapEnvelope.getBytes());
                os.flush();
            }

            // Leitura da resposta SOAP
            StringBuilder resposta = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conexao.getInputStream()))) {
                String linha;
                while ((linha = br.readLine()) != null) {
                    resposta.append(linha);
                }
            }

            int httpCode = conexao.getResponseCode();
            logger.info("[NfeStatusServiceImpl] Resposta HTTP {} recebida da SEFAZ-SP.", httpCode);

            String xmlResposta = resposta.toString();

            // Normaliza namespaces e procura pelo cStat no XML retornado
            String xmlLimpo = xmlResposta.replaceAll("(?i)<(/)?([a-zA-Z0-9_\\-:]+:)", "<$1");

            if (xmlLimpo.contains("<cStat>")) {
                int ini = xmlLimpo.indexOf("<cStat>") + 7;
                int fim = xmlLimpo.indexOf("</cStat>");
                String cStat = xmlLimpo.substring(ini, fim);
                logger.info("[NfeStatusServiceImpl] SEFAZ-SP retornou cStat={} (Status do Serviço).", cStat);
            } else {
                logger.warn("[NfeStatusServiceImpl] Elemento <cStat> não encontrado — verifique o corpo da resposta SOAP.");
            }

            return xmlLimpo;

        } catch (Exception e) {
            logger.error("[NfeStatusServiceImpl] Falha ao consultar status NF-e na SEFAZ-SP: {}", e.getMessage(), e);
            return "<erro>" + e.getMessage() + "</erro>";
        }
    }
}
