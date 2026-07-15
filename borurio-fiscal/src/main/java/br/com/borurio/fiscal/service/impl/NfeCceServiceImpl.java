package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.dto.NfeCceRequest;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.AssinaturaXmlService;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeLogService;
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
import java.util.Map;

@Slf4j
@Service
public class NfeCceServiceImpl implements NfeCceService {

    private static final String NFE_NS    = "http://www.portalfiscal.inf.br/nfe";
    private static final String WSDL_NS   = "http://www.portalfiscal.inf.br/nfe/wsdl/NFeRecepcaoEvento4";
    private static final int    MAX_CCE   = 20;

    // Texto fixo obrigatório pela SEFAZ (NT 2019.001)
    private static final String X_COND_USO =
            "A Carta de Correcao e disciplinada pelo paragrafo 1o-A do art. 7o do Convenio S/N, " +
            "de 15 de dezembro de 1970 e pode ser utilizada para regularizacao de erro ocorrido " +
            "na emissao de documento fiscal, desde que o erro nao esteja relacionado com: " +
            "I - as variaveis que determinam o valor do imposto tais como: base de calculo, " +
            "aliquota, diferenca de preco, quantidade, valor da operacao ou da prestacao; " +
            "II - a correcao de dados cadastrais que implique mudanca do remetente ou do " +
            "destinatario; III - a data de emissao ou de saida.";

    private static final Map<String, String> UF_PARA_CUF = Map.ofEntries(
            Map.entry("AC","12"), Map.entry("AL","27"), Map.entry("AM","13"),
            Map.entry("AP","16"), Map.entry("BA","29"), Map.entry("CE","23"),
            Map.entry("DF","53"), Map.entry("ES","32"), Map.entry("GO","52"),
            Map.entry("MA","21"), Map.entry("MG","31"), Map.entry("MS","50"),
            Map.entry("MT","51"), Map.entry("PA","15"), Map.entry("PB","25"),
            Map.entry("PE","26"), Map.entry("PI","22"), Map.entry("PR","41"),
            Map.entry("RJ","33"), Map.entry("RN","24"), Map.entry("RO","11"),
            Map.entry("RR","14"), Map.entry("RS","43"), Map.entry("SC","42"),
            Map.entry("SE","28"), Map.entry("SP","35"), Map.entry("TO","17")
    );

    private final AssinaturaXmlService assinaturaXmlService;
    private final CertificadoService   certificadoService;
    private final SefazProperties      sefazProperties;
    private final NfeLogService        nfeLogService;
    private final EmitenteProperties   emitente;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeCceServiceImpl(AssinaturaXmlService assinaturaXmlService,
                             CertificadoService certificadoService,
                             SefazProperties sefazProperties,
                             NfeLogService nfeLogService,
                             EmitenteProperties emitente) {
        this.assinaturaXmlService = assinaturaXmlService;
        this.certificadoService   = certificadoService;
        this.sefazProperties      = sefazProperties;
        this.nfeLogService        = nfeLogService;
        this.emitente             = emitente;
    }

    @Override
    public String corrigir(NfeCceRequest req) throws Exception {
        return corrigir(req, null, null, null);
    }

    @Override
    public String corrigir(NfeCceRequest req, String cnpjEmitente, String uf,
                            CertificadoContexto certContexto) throws Exception {
        validar(req);

        String chave = req.getChaveNfe().replaceAll("\\D", "");
        String cnpj  = (cnpjEmitente != null ? cnpjEmitente : emitente.getCnpj()).replaceAll("\\D", "");
        String cUF   = resolverCUF(uf);

        int nSeq = resolverSequencia(chave, req.getSequencia());

        String dhEvento = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "-03:00";
        String nSeqPadded = String.format("%02d", nSeq);
        String idEvento   = "ID110110" + chave + nSeqPadded;

        String xmlEvento = montarEnvEvento(idEvento, cUF, cnpj, chave,
                dhEvento, nSeqPadded, req.getCorrecao().trim());

        log.info("[CC-e] Assinando | chave={} | seq={} | tpAmb={} | cnpj={}", chave, nSeq, tpAmb, cnpj);
        String xmlAssinado = certContexto != null
                ? assinaturaXmlService.assinarEvento(xmlEvento, certContexto)
                : assinaturaXmlService.assinarEvento(xmlEvento);

        String soapEnvelope = montarSoap(xmlAssinado);
        String urlWs        = sefazProperties.getRecepcaoEvento();

        log.info("[CC-e] Enviando para SEFAZ | url={}", urlWs);

        try {
            String resposta = enviarSoap(urlWs, soapEnvelope, certContexto);
            registrarLog(chave, cnpj, "SUCCESS",
                    "CC-e transmitida | seq=" + nSeq, xmlAssinado, resposta);
            log.info("[CC-e] Resposta SEFAZ OK | chave={} | seq={}", chave, nSeq);
            return resposta;
        } catch (Exception e) {
            registrarLog(chave, cnpj, "ERROR",
                    "Erro CC-e seq=" + nSeq + ": " + e.getMessage(), xmlAssinado, null);
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Derivação de sequência — banco é a fonte de verdade
    // -------------------------------------------------------------------------
    private int resolverSequencia(String chave, Integer seqSolicitada) {
        int contadorBanco = 0;
        try {
            contadorBanco = nfeLogService.contarEventos(chave, "CCE");
        } catch (Exception e) {
            log.warn("[CC-e] Não foi possível consultar histórico de CC-e | chave={} | erro={}",
                    chave, e.getMessage());
        }

        int nSeq = (seqSolicitada != null && seqSolicitada > 0)
                ? seqSolicitada
                : contadorBanco + 1;

        if (nSeq > MAX_CCE) {
            throw new IllegalArgumentException(
                    "Limite de " + MAX_CCE + " CC-e por NF-e atingido. " +
                    "Chave: " + chave + " já possui " + contadorBanco + " evento(s) registrado(s).");
        }
        return nSeq;
    }

    // -------------------------------------------------------------------------
    // Montagem do envEvento (CC-e 110110)
    // Não usar text block: SEFAZ é sensível a whitespace dentro de nfeDadosMsg.
    // -------------------------------------------------------------------------
    private String montarEnvEvento(String idEvento, String cUF, String cnpj,
                                   String chave, String dhEvento, String nSeq,
                                   String correcao) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<envEvento versao=\"1.00\" xmlns=\"" + NFE_NS + "\">" +
               "<idLote>1</idLote>" +
               "<evento versao=\"1.00\">" +
               "<infEvento Id=\"" + idEvento + "\">" +
               "<cOrgao>" + cUF + "</cOrgao>" +
               "<tpAmb>" + tpAmb + "</tpAmb>" +
               "<CNPJ>" + cnpj + "</CNPJ>" +
               "<chNFe>" + chave + "</chNFe>" +
               "<dhEvento>" + dhEvento + "</dhEvento>" +
               "<tpEvento>110110</tpEvento>" +
               "<nSeqEvento>" + nSeq + "</nSeqEvento>" +
               "<verEvento>1.00</verEvento>" +
               "<detEvento versao=\"1.00\">" +
               "<descEvento>Carta de Correcao</descEvento>" +
               "<xCorrecao>" + correcao + "</xCorrecao>" +
               "<xCondUso>" + X_COND_USO + "</xCondUso>" +
               "</detEvento>" +
               "</infEvento>" +
               "</evento>" +
               "</envEvento>";
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

    private String enviarSoap(String urlWs, String envelope, CertificadoContexto certContexto) throws Exception {
        SSLContext ssl = certContexto != null ? certContexto.sslContext() : certificadoService.getSslContext();
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

    private void registrarLog(String chave, String cnpj, String status,
                               String descricao, String xmlEnvio, String xmlRetorno) {
        try {
            nfeLogService.salvar(NfeLog.builder()
                    .chaveNfe(chave)
                    .tipoEvento("CCE")
                    .status(status)
                    .descricao(descricao)
                    .cnpjEmitente(cnpj)
                    .xmlEnvio(xmlEnvio)
                    .xmlRetorno(xmlRetorno)
                    .dataEvento(LocalDateTime.now())
                    .usuario("system")
                    .build());
        } catch (Exception e) {
            log.error("[CC-e] Falha ao persistir log | chave={} | erro={}", chave, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Validação
    // -------------------------------------------------------------------------
    private void validar(NfeCceRequest req) {
        if (req.getChaveNfe() == null || req.getChaveNfe().replaceAll("\\D","").length() != 44)
            throw new IllegalArgumentException("Chave NF-e inválida (deve ter 44 dígitos numéricos).");

        String correcao = req.getCorrecao() != null ? req.getCorrecao().trim() : "";
        if (correcao.length() < 15)
            throw new IllegalArgumentException("xCorrecao deve ter no mínimo 15 caracteres.");
        if (correcao.length() > 1000)
            throw new IllegalArgumentException("xCorrecao deve ter no máximo 1000 caracteres.");

        if (req.getSequencia() != null && (req.getSequencia() < 1 || req.getSequencia() > MAX_CCE))
            throw new IllegalArgumentException("Sequência deve estar entre 1 e " + MAX_CCE + ".");
    }

    private String resolverCUF(String ufOverride) {
        String uf = ufOverride != null ? ufOverride : emitente.getUf();
        if (uf == null || uf.isBlank()) return "35";
        String cuf = UF_PARA_CUF.get(uf.toUpperCase());
        return cuf != null ? cuf : "35";
    }
}
