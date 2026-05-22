package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.mapper.NfeLogMapper;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    // Mapeamento UF sigla → código numérico IBGE (cUF)
    private static final Map<String, String> UF_PARA_CUF = Map.ofEntries(
            Map.entry("AC", "12"), Map.entry("AL", "27"), Map.entry("AM", "13"),
            Map.entry("AP", "16"), Map.entry("BA", "29"), Map.entry("CE", "23"),
            Map.entry("DF", "53"), Map.entry("ES", "32"), Map.entry("GO", "52"),
            Map.entry("MA", "21"), Map.entry("MG", "31"), Map.entry("MS", "50"),
            Map.entry("MT", "51"), Map.entry("PA", "15"), Map.entry("PB", "25"),
            Map.entry("PE", "26"), Map.entry("PI", "22"), Map.entry("PR", "41"),
            Map.entry("RJ", "33"), Map.entry("RN", "24"), Map.entry("RO", "11"),
            Map.entry("RR", "14"), Map.entry("RS", "43"), Map.entry("SC", "42"),
            Map.entry("SE", "28"), Map.entry("SP", "35"), Map.entry("TO", "17")
    );

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;
    private final Retry sefazRetry;

    @Value("${sefaz.urls.autorizacao}")
    private String urlAutorizacao;

    @Value("${sefaz.urls.status}")
    private String urlStatus;

    @Value("${sefaz.urls.retorno}")
    private String urlRetorno;

    @Value("${sefaz.urls.consulta}")
    private String urlConsulta;

    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService,
            Retry sefazRetry) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
        this.sefazRetry = sefazRetry;
    }

    // =========================
    // ENVIO NF-e
    // =========================

    /** Transmite usando o certificado de empresa específica (Fase 8B). */
    public String transmitirXml(String xmlAssinado, String cnpjEmitente,
                                String uf, int ambiente, SSLContext sslContextEmpresa) {
        return transmitirXmlInterno(xmlAssinado, cnpjEmitente, uf, ambiente, sslContextEmpresa);
    }

    @Override
    public String transmitirXml(String xmlAssinado,
                                String cnpjEmitente,
                                String uf,
                                int ambiente) {
        return transmitirXmlInterno(xmlAssinado, cnpjEmitente, uf, ambiente, null);
    }

    private String transmitirXmlInterno(String xmlAssinado,
                                        String cnpjEmitente,
                                        String uf,
                                        int ambiente,
                                        SSLContext sslOverride) {

        String chaveNfe = extrairChaveNFe(xmlAssinado);

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chaveNfe)
                .tipoEvento("ENVIO_NFE")
                .descricao("Transmissão NF-e UF=" + uf + " Amb=" + ambiente)
                .status("PENDING")
                .dataEvento(LocalDateTime.now())
                .cnpjEmitente(cnpjEmitente)
                .xmlEnvio(xmlAssinado)
                .usuario(resolverUsuario())
                .build();

        try {
            String idLote  = gerarIdLote();
            String envelope = criarEnvelopeEnviNFe(xmlAssinado, idLote, ambiente);

            SSLContext sslUsado = sslOverride != null ? sslOverride : certificadoService.getSslContext();
            String resposta = enviarSoap(urlAutorizacao, envelope, sslUsado);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setDescricao("NF-e transmitida — lote=" + idLote);
            logFiscal.setXmlRetorno(resposta);
            logFiscal.setDataEvento(LocalDateTime.now());

            salvarLogSeguro(logFiscal);

            log.info("[NF-e] Transmissão OK | UF={} | Amb={} | Lote={} | Chave={}",
                    uf, ambiente, idLote, chaveNfe);

            return resposta;

        } catch (Exception e) {
            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Erro: " + e.getMessage());
            logFiscal.setDataEvento(LocalDateTime.now());
            salvarLogSeguro(logFiscal);
            log.error("[NF-e] Erro na transmissão", e);
            throw new RuntimeException("Falha ao transmitir NF-e", e);
        }
    }

    // =========================
    // STATUS SEFAZ
    // =========================
    @Override
    public String consultarStatus(String uf, int ambiente) {

        String cUF = UF_PARA_CUF.getOrDefault(uf.toUpperCase(), "35");

        // Não usar text block: SEFAZ exige XML compacto sem espaços extras
        String envelope =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">" +
                "<soap12:Body>" +
                "<nfeDadosMsg xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeStatusServico4\">" +
                "<consStatServ versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">" +
                "<tpAmb>" + ambiente + "</tpAmb>" +
                "<cUF>" + cUF + "</cUF>" +
                "<xServ>STATUS</xServ>" +
                "</consStatServ>" +
                "</nfeDadosMsg>" +
                "</soap12:Body>" +
                "</soap12:Envelope>";

        try {
            return enviarSoap(urlStatus, envelope);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao consultar status SEFAZ", e);
        }
    }

    // =========================
    // CONSULTA NF-e POR CHAVE (consSitNFe)
    // =========================
    @Override
    public String consultarNfe(String chaveNfe, String uf, int ambiente) {

        if (chaveNfe == null || !chaveNfe.matches("\\d{44}")) {
            throw new IllegalArgumentException("Chave NF-e inválida: deve conter exatamente 44 dígitos numéricos.");
        }

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chaveNfe)
                .tipoEvento("CONSULTA")
                .descricao("Consulta situação NF-e | UF=" + uf + " | Amb=" + ambiente)
                .status("PENDING")
                .dataEvento(LocalDateTime.now())
                .usuario(resolverUsuario())
                .build();

        String envelope =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">" +
                "<soap12:Body>" +
                "<nfeDadosMsg xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeConsultaProtocolo4\">" +
                "<consSitNFe versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">" +
                "<tpAmb>" + ambiente + "</tpAmb>" +
                "<xServ>CONSULTAR</xServ>" +
                "<chNFe>" + chaveNfe + "</chNFe>" +
                "</consSitNFe>" +
                "</nfeDadosMsg>" +
                "</soap12:Body>" +
                "</soap12:Envelope>";

        try {
            String resposta = enviarSoap(urlConsulta, envelope);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setDescricao("Consulta NF-e OK | UF=" + uf + " | Amb=" + ambiente);
            logFiscal.setXmlRetorno(resposta);
            logFiscal.setDataEvento(LocalDateTime.now());
            salvarLogSeguro(logFiscal);

            log.info("[NF-e] Consulta situação OK | chave={} | UF={} | Amb={}", chaveNfe, uf, ambiente);
            return resposta;

        } catch (Exception e) {
            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Erro consulta NF-e: " + e.getMessage());
            logFiscal.setDataEvento(LocalDateTime.now());
            salvarLogSeguro(logFiscal);
            log.error("[NF-e] Erro ao consultar NF-e | chave={}", chaveNfe, e);
            throw new RuntimeException("Falha ao consultar NF-e na SEFAZ", e);
        }
    }

    // =========================
    // CONSULTA RECIBO (consReciNFe)
    // =========================
    @Override
    public String consultarRecibo(String nRec, String uf, int ambiente) {

        // Usado quando a NF-e foi enviada em modo assíncrono (indSinc=0)
        String envelope =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">" +
                "<soap12:Body>" +
                "<nfeDadosMsg xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeRetAutorizacao4\">" +
                "<consReciNFe versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">" +
                "<tpAmb>" + ambiente + "</tpAmb>" +
                "<nRec>" + nRec + "</nRec>" +
                "</consReciNFe>" +
                "</nfeDadosMsg>" +
                "</soap12:Body>" +
                "</soap12:Envelope>";

        try {
            String resposta = enviarSoap(urlRetorno, envelope);
            log.info("[NF-e] Consulta recibo OK | nRec={} | UF={}", nRec, uf);
            return resposta;
        } catch (Exception e) {
            throw new RuntimeException("Falha ao consultar recibo SEFAZ nRec=" + nRec, e);
        }
    }

    // =========================
    // SOAP CORE
    // =========================
    private String enviarSoap(String urlWs, String envelope) throws Exception {
        return enviarSoap(urlWs, envelope, certificadoService.getSslContext());
    }

    private String enviarSoap(String urlWs, String envelope, SSLContext ssl) throws Exception {
        try {
            return sefazRetry.executeCheckedSupplier(() -> executarSoap(urlWs, envelope, ssl));
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private String executarSoap(String urlWs, String envelope, SSLContext ssl) throws Exception {

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

        // SEFAZ retorna rejeições com HTTP 500 (SOAP Fault).
        // getInputStream() lança IOException em 4xx/5xx — usar getErrorStream() nesses casos.
        int httpCode = conn.getResponseCode();
        InputStream stream = httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream();

        return lerResposta(stream);
    }

    private String lerResposta(InputStream stream) throws IOException {
        if (stream == null) {
            return "<erro>Resposta SEFAZ vazia</erro>";
        }
        StringBuilder resp = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                resp.append(line);
            }
        }
        return resp.toString();
    }

    // =========================
    // MONTAGEM enviNFe
    // =========================

    /**
     * Envelopa o XML assinado em enviNFe (indSinc=1 = síncrono, 1 nota por lote).
     *
     * Estrutura SOAP esperada pela SEFAZ para NFeAutorizacao4:
     *
     *   soap12:Envelope
     *     soap12:Body
     *       nfeDadosMsg [wsdl NFeAutorizacao4]
     *         enviNFe versao="4.00" [namespace portalfiscal]
     *           idLote    ← 15 dígitos, único por envio
     *           indSinc   ← 1 = síncrono (retorno imediato sem nRec)
     *           NFe       ← XML assinado completo (com Signature)
     *
     * Não usar text block: SEFAZ é sensível a whitespace no conteúdo de nfeDadosMsg.
     */
    private String criarEnvelopeEnviNFe(String xmlAssinado, String idLote, int ambiente) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">" +
               "<soap12:Body>" +
               "<nfeDadosMsg xmlns=\"http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4\">" +
               "<enviNFe versao=\"4.00\" xmlns=\"http://www.portalfiscal.inf.br/nfe\">" +
               "<idLote>" + idLote + "</idLote>" +
               "<indSinc>1</indSinc>" +
               xmlAssinado +
               "</enviNFe>" +
               "</nfeDadosMsg>" +
               "</soap12:Body>" +
               "</soap12:Envelope>";
    }

    // =========================
    // HELPERS
    // =========================

    // Contador atômico garante unicidade de idLote mesmo em emissões concorrentes no mesmo ms
    private final AtomicLong loteCounter = new AtomicLong(System.currentTimeMillis());

    /** Gera idLote de 15 dígitos único por chamada, sem colisão em chamadas concorrentes. */
    private String gerarIdLote() {
        return String.format("%015d", loteCounter.getAndIncrement() % 1_000_000_000_000_000L);
    }

    private String extrairChaveNFe(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.NodeList list = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getElementsByTagNameNS("*", "infNFe");
            if (list.getLength() > 0) {
                String id = ((org.w3c.dom.Element) list.item(0)).getAttribute("Id");
                return id.startsWith("NFe") ? id.substring(3) : id;
            }
        } catch (Exception e) {
            log.warn("[NF-e] Erro ao extrair chave via DOM: {}", e.getMessage());
        }
        return "SEM-CHAVE";
    }

    private void salvarLogSeguro(NfeLog logFiscal) {
        try {
            nfeLogMapper.insertLog(logFiscal);
        } catch (Exception e) {
            log.error("[NF-e] Falha ao salvar log fiscal", e);
        }
    }

    private String resolverUsuario() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) return auth.getName();
        return "system";
    }
}
