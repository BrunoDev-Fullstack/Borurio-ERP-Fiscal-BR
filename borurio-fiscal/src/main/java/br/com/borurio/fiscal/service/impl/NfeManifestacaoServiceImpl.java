package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.dto.NfeManifestacaoRequest;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.AssinaturaXmlService;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.NfeManifestacaoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

@Slf4j
@Service
public class NfeManifestacaoServiceImpl implements NfeManifestacaoService {

    private static final String NFE_NS  = "http://www.portalfiscal.inf.br/nfe";
    private static final String WSDL_NS = "http://www.portalfiscal.inf.br/nfe/wsdl/NFeRecepcaoEvento4";

    private static final Set<String> TIPOS_VALIDOS =
            Set.of("210200", "210210", "210220", "210240");

    private static final String TIPO_OPERACAO_NAO_REALIZADA = "210240";

    // Manifestação do Destinatário sempre vai para o Ambiente Nacional (AN), independente da UF emitente.
    private static final String CORGAO_AN = "91";

    private static final Set<String> CSTAT_SUCESSO_EVENTO = Set.of("135", "136");

    private final AssinaturaXmlService assinaturaXmlService;
    private final CertificadoService   certificadoService;
    private final SefazProperties      sefazProperties;
    private final NfeLogService        nfeLogService;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeManifestacaoServiceImpl(AssinaturaXmlService assinaturaXmlService,
                                      CertificadoService certificadoService,
                                      SefazProperties sefazProperties,
                                      NfeLogService nfeLogService) {
        this.assinaturaXmlService = assinaturaXmlService;
        this.certificadoService   = certificadoService;
        this.sefazProperties      = sefazProperties;
        this.nfeLogService        = nfeLogService;
    }

    @Override
    public String manifestar(NfeManifestacaoRequest req) throws Exception {
        validar(req);

        String chave  = req.getChaveNfe().replaceAll("\\D", "");
        String cnpj   = req.getCnpjDestinatario().replaceAll("\\D", "");
        String tipo   = req.getTipoEvento();

        String dhEvento    = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "-03:00";
        String nSeqPadded  = "01";
        String idEvento    = "ID" + tipo + chave + nSeqPadded;

        String xmlEvento = montarEnvEvento(idEvento, CORGAO_AN, cnpj, chave,
                dhEvento, nSeqPadded, tipo, req.getXJust());

        log.info("[MANIFESTACAO] Assinando | chave={} | tipo={} | tpAmb={}", chave, tipo, tpAmb);
        String xmlAssinado = assinaturaXmlService.assinarEvento(xmlEvento);

        String soapEnvelope = montarSoap(xmlAssinado);
        String urlWs        = sefazProperties.getManifestacaoEvento();

        log.info("[MANIFESTACAO] Enviando para SEFAZ | url={}", urlWs);

        String resposta = null;
        try {
            resposta = enviarSoap(urlWs, soapEnvelope);
            log.debug("[MANIFESTACAO] Resposta bruta SEFAZ | chave={} | resp={}", chave, resposta);
            String resultado = verificarERetornarResultado(resposta);
            registrarLog(chave, cnpj, tipo, "SUCCESS",
                    "Manifestacao transmitida | tipo=" + tipo + " | " + resultado, xmlAssinado, resposta);
            log.info("[MANIFESTACAO] Resposta SEFAZ OK | chave={} | tipo={} | {}", chave, tipo, resultado);
            return resultado;
        } catch (Exception e) {
            registrarLog(chave, cnpj, tipo, "ERROR",
                    "Erro manifestacao tipo=" + tipo + ": " + e.getMessage(), xmlAssinado, resposta);
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Montagem do envEvento — Manifestação do Destinatário
    // Não usar text block: SEFAZ é sensível a whitespace dentro de nfeDadosMsg.
    // -------------------------------------------------------------------------
    private String montarEnvEvento(String idEvento, String cOrgao, String cnpj,
                                   String chave, String dhEvento, String nSeq,
                                   String tipo, String xJust) {
        String detEvento = montarDetEvento(tipo, xJust);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<envEvento versao=\"1.00\" xmlns=\"" + NFE_NS + "\">" +
               "<idLote>1</idLote>" +
               "<evento versao=\"1.00\">" +
               "<infEvento Id=\"" + idEvento + "\">" +
               "<cOrgao>" + cOrgao + "</cOrgao>" +
               "<tpAmb>" + tpAmb + "</tpAmb>" +
               "<CNPJ>" + cnpj + "</CNPJ>" +
               "<chNFe>" + chave + "</chNFe>" +
               "<dhEvento>" + dhEvento + "</dhEvento>" +
               "<tpEvento>" + tipo + "</tpEvento>" +
               "<nSeqEvento>" + nSeq + "</nSeqEvento>" +
               "<verEvento>1.00</verEvento>" +
               detEvento +
               "</infEvento>" +
               "</evento>" +
               "</envEvento>";
    }

    private String montarDetEvento(String tipo, String xJust) {
        String descEvento = switch (tipo) {
            case "210200" -> "Ciencia da Operacao";
            case "210210" -> "Confirmacao da Operacao";
            case "210220" -> "Desconhecimento da Operacao";
            case "210240" -> "Operacao nao Realizada";
            default -> throw new IllegalArgumentException("Tipo de evento inválido: " + tipo);
        };

        String corpo = "<descEvento>" + descEvento + "</descEvento>";
        if (TIPO_OPERACAO_NAO_REALIZADA.equals(tipo)) {
            corpo += "<xJust>" + xJust.trim() + "</xJust>";
        }

        return "<detEvento versao=\"1.00\">" + corpo + "</detEvento>";
    }

    private String montarSoap(String xmlAssinado) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">" +
               "<soap12:Body>" +
               "<nfeDadosMsg xmlns=\"" + WSDL_NS + "\">" +
               xmlAssinado +
               "</nfeDadosMsg>" +
               "</soap12:Body>" +
               "</soap12:Envelope>";
    }

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
        int httpCode = conn.getResponseCode();
        InputStream stream = httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        return lerResposta(stream);
    }

    private String lerResposta(InputStream stream) throws IOException {
        if (stream == null) return "<erro>Resposta SEFAZ vazia</erro>";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private void registrarLog(String chave, String cnpj, String tipo,
                               String status, String descricao,
                               String xmlEnvio, String xmlRetorno) {
        try {
            nfeLogService.salvar(NfeLog.builder()
                    .chaveNfe(chave)
                    .tipoEvento("MANIFESTACAO_" + tipo)
                    .status(status)
                    .descricao(descricao)
                    .cnpjEmitente(cnpj)
                    .xmlEnvio(xmlEnvio)
                    .xmlRetorno(xmlRetorno)
                    .dataEvento(LocalDateTime.now())
                    .usuario("system")
                    .build());
        } catch (Exception e) {
            log.error("[MANIFESTACAO] Falha ao persistir log | chave={} | erro={}", chave, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Parsing da resposta SEFAZ
    // -------------------------------------------------------------------------
    private String verificarERetornarResultado(String resposta) {
        String cStatLote = extrairTag(resposta, "cStat");
        if (cStatLote == null)
            throw new RuntimeException("Resposta SEFAZ inválida: cStat não encontrado.");
        if (!"128".equals(cStatLote)) {
            String xMotivo = extrairTag(resposta, "xMotivo");
            throw new RuntimeException("SEFAZ rejeitou lote: cStat=" + cStatLote + " - " + xMotivo);
        }
        String cStatEvento = extrairUltimaTag(resposta, "cStat");
        String xMotivoEvento = extrairUltimaTag(resposta, "xMotivo");
        if (!CSTAT_SUCESSO_EVENTO.contains(cStatEvento)) {
            throw new RuntimeException("SEFAZ rejeitou evento: cStat=" + cStatEvento + " - " + xMotivoEvento);
        }
        return cStatEvento + " - " + xMotivoEvento;
    }

    private String extrairTag(String xml, String tag) {
        String open = "<" + tag + ">";
        int idx = xml.indexOf(open);
        if (idx < 0) return null;
        int fim = xml.indexOf("</" + tag + ">", idx);
        if (fim < 0) return null;
        return xml.substring(idx + open.length(), fim).trim();
    }

    private String extrairUltimaTag(String xml, String tag) {
        String open = "<" + tag + ">";
        int idx = xml.lastIndexOf(open);
        if (idx < 0) return null;
        int fim = xml.indexOf("</" + tag + ">", idx);
        if (fim < 0) return null;
        return xml.substring(idx + open.length(), fim).trim();
    }

    // -------------------------------------------------------------------------
    // Validação
    // -------------------------------------------------------------------------
    private void validar(NfeManifestacaoRequest req) {
        if (req.getChaveNfe() == null || req.getChaveNfe().replaceAll("\\D", "").length() != 44)
            throw new IllegalArgumentException("Chave NF-e inválida (deve ter 44 dígitos numéricos).");

        if (req.getTipoEvento() == null || !TIPOS_VALIDOS.contains(req.getTipoEvento()))
            throw new IllegalArgumentException(
                    "Tipo de evento inválido. Valores aceitos: 210200, 210210, 210220, 210240.");

        if (req.getCnpjDestinatario() == null || req.getCnpjDestinatario().replaceAll("\\D", "").length() != 14)
            throw new IllegalArgumentException("CNPJ do destinatário inválido (deve ter 14 dígitos).");

        if (TIPO_OPERACAO_NAO_REALIZADA.equals(req.getTipoEvento())) {
            String just = req.getXJust() != null ? req.getXJust().trim() : "";
            if (just.length() < 15)
                throw new IllegalArgumentException("xJust obrigatório para 210240 e deve ter no mínimo 15 caracteres.");
            if (just.length() > 255)
                throw new IllegalArgumentException("xJust deve ter no máximo 255 caracteres.");
        }
    }
}
