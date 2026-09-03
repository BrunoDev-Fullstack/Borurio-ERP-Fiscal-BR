package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.dto.NfeCancelamentoRequest;
import br.com.borurio.fiscal.dto.NfeEventoPreparado;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.service.AssinaturaXmlService;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeCancelamentoService;
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
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Service
public class NfeCancelamentoServiceImpl implements NfeCancelamentoService {

    private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";
    private static final String WSDL_EVENTO_NS =
            "http://www.portalfiscal.inf.br/nfe/wsdl/NFeRecepcaoEvento4";

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

    // yyyy-MM-dd'T'HH:mm:ssXXX -- offset (XXX) sempre calculado pela ZoneId, nunca concatenado
    // manualmente. Achado de banca (12-08-2026): "LocalDateTime.now() + \"-03:00\"" rotulava a
    // hora local da JVM como se fosse Brasília mesmo quando a JVM roda em outro fuso (ex.:
    // container em UTC) -- o offset ficava errado sem nenhum erro visível.
    private static final DateTimeFormatter DH_EVENTO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final AssinaturaXmlService assinaturaXmlService;
    private final CertificadoService certificadoService;
    private final SefazProperties sefazProperties;
    private final NfeLogService nfeLogService;
    private final EmitenteProperties emitente;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    // Fuso fiscal explicitamente configurável -- nunca depende do fuso padrão da JVM/container.
    // Default America/Sao_Paulo (sem DST desde 2019, sempre -03:00) cobre as empresas hoje
    // cadastradas (todas SP); UFs com offset diferente (ex. AC -05:00) exigiriam configuração
    // por empresa, fora do escopo deste gate.
    @Value("${sefaz.timezone-fiscal:America/Sao_Paulo}")
    private String timezoneFiscal;

    public NfeCancelamentoServiceImpl(AssinaturaXmlService assinaturaXmlService,
                                      CertificadoService certificadoService,
                                      SefazProperties sefazProperties,
                                      NfeLogService nfeLogService,
                                      EmitenteProperties emitente) {
        this.assinaturaXmlService = assinaturaXmlService;
        this.certificadoService = certificadoService;
        this.sefazProperties = sefazProperties;
        this.nfeLogService = nfeLogService;
        this.emitente = emitente;
    }

    @Override
    public String cancelar(NfeCancelamentoRequest req) throws Exception {
        return cancelar(req, null, null, null);
    }

    @Override
    public String cancelar(NfeCancelamentoRequest req, String cnpjEmitente, String uf,
                            CertificadoContexto certContexto) throws Exception {
        // Ponto único de log fiscal: registrarLog vive em transmitirEvento (chamado tanto por
        // este facade legado quanto por NfeCancelamentoOrquestradorService) — nunca duplicar
        // aqui (achado de banca, 12-08-2026: o caminho novo não gravava NfeLog nenhum porque
        // transmitirEvento não gravava e cancelar() só logava no seu próprio corpo).
        NfeEventoPreparado preparado = prepararEvento(req, cnpjEmitente, uf, certContexto, 1);
        return transmitirEvento(preparado, certContexto);
    }

    @Override
    public NfeEventoPreparado prepararEvento(NfeCancelamentoRequest req, String cnpjEmitente, String uf,
                                              CertificadoContexto certContexto, int nSeqEvento) throws Exception {
        validar(req);

        String chave     = req.getChaveNfe().replaceAll("\\D", "");
        String cnpj      = (cnpjEmitente != null ? cnpjEmitente : emitente.getCnpj()).replaceAll("\\D", "");
        String cUF       = resolverCUF(uf);
        String nSeq      = String.format("%02d", nSeqEvento);
        String idEvento  = "ID110111" + chave + nSeq;
        String dhEvento  = ZonedDateTime.now(ZoneId.of(timezoneFiscal)).format(DH_EVENTO_FMT);

        String xmlEvento = montarEnvEvento(idEvento, cUF, cnpj, chave,
                dhEvento, nSeq, req.getNProtocolo(), req.getJustificativa());

        log.info("[Cancelamento] Assinando evento | chave={} | tpAmb={} | cnpj={} | nSeq={}", chave, tpAmb, cnpj, nSeq);
        String xmlAssinado = certContexto != null
                ? assinaturaXmlService.assinarEvento(xmlEvento, certContexto)
                : assinaturaXmlService.assinarEvento(xmlEvento);

        return new NfeEventoPreparado(idEvento, dhEvento, xmlAssinado, calcularPayloadHash(xmlAssinado), chave, cnpj);
    }

    @Override
    public String transmitirEvento(NfeEventoPreparado preparado, CertificadoContexto certContexto) throws Exception {
        String soapEnvelope = montarSoap(preparado.xmlAssinado());
        String urlWs        = sefazProperties.getRecepcaoEvento();
        log.info("[Cancelamento] Enviando para SEFAZ | url={} | chave={}", urlWs, preparado.chave());
        // Ponto único de log fiscal (NfeLog) do transporte real -- chamado tanto pelo facade
        // legado (cancelar()) quanto por NfeCancelamentoOrquestradorService (gate de
        // cancelamento). Nunca duplicar em nenhum dos dois chamadores.
        try {
            String resposta = enviarSoap(urlWs, soapEnvelope, certContexto);
            registrarLog(preparado.chave(), preparado.cnpj(), "SUCCESS", "Cancelamento transmitido",
                    preparado.xmlAssinado(), resposta);
            log.info("[Cancelamento] Resposta SEFAZ recebida | chave={}", preparado.chave());
            return resposta;
        } catch (Exception e) {
            registrarLog(preparado.chave(), preparado.cnpj(), "ERROR", "Erro: " + e.getMessage(),
                    preparado.xmlAssinado(), null);
            // Fronteira local/rede comprovada pela FASE (esta chamada), nunca por tipo de
            // excecao -- mesmo principio de NfeTransmitServiceImpl.consultarNfe. Qualquer falha
            // aqui significa que o POST pode ou nao ter sido processado pela SEFAZ; o chamador
            // (NfeEventoService) nunca deve tratar isto como sucesso nem como rejeicao.
            throw new SefazTransmissaoIncertaException(e);
        }
    }

    private String calcularPayloadHash(String xmlAssinado) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(xmlAssinado.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("[Cancelamento] Falha ao calcular payloadHash: {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Montagem do XML envEvento (cancelamento 110111)
    // Não usar text block: SEFAZ é sensível a whitespace dentro de nfeDadosMsg.
    // -------------------------------------------------------------------------
    private String montarEnvEvento(String idEvento, String cUF, String cnpj,
                                   String chave, String dhEvento, String nSeq,
                                   String nProtocolo, String justificativa) {
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
               "<tpEvento>110111</tpEvento>" +
               "<nSeqEvento>" + nSeq + "</nSeqEvento>" +
               "<verEvento>1.00</verEvento>" +
               "<detEvento versao=\"1.00\">" +
               "<descEvento>Cancelamento</descEvento>" +
               "<nProt>" + nProtocolo + "</nProt>" +
               "<xJust>" + justificativa + "</xJust>" +
               "</detEvento>" +
               "</infEvento>" +
               "</evento>" +
               "</envEvento>";
    }

    private String montarSoap(String xmlAssinado) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">" +
               "<soap12:Body>" +
               "<nfeDadosMsg xmlns=\"" + WSDL_EVENTO_NS + "\">" +
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
                    .tipoEvento("CANCELAMENTO")
                    .status(status)
                    .descricao(descricao)
                    .cnpjEmitente(cnpj)
                    .xmlEnvio(xmlEnvio)
                    .xmlRetorno(xmlRetorno)
                    .dataEvento(LocalDateTime.now())
                    .usuario("system")
                    .build());
        } catch (Exception e) {
            log.error("[Cancelamento] Falha ao persistir log | chave={} | erro={}", chave, e.getMessage());
        }
    }

    private String resolverCUF(String ufOverride) {
        String uf = ufOverride != null ? ufOverride : emitente.getUf();
        if (uf == null || uf.isBlank()) return "35"; // SP fallback
        String cuf = UF_PARA_CUF.get(uf.toUpperCase());
        return cuf != null ? cuf : "35";
    }

    private void validar(NfeCancelamentoRequest req) {
        if (req.getChaveNfe() == null || req.getChaveNfe().replaceAll("\\D","").length() != 44)
            throw new IllegalArgumentException("Chave NF-e inválida (deve ter 44 dígitos).");
        if (req.getNProtocolo() == null || req.getNProtocolo().isBlank())
            throw new IllegalArgumentException("Número de protocolo de autorização é obrigatório.");
        if (req.getJustificativa() == null || req.getJustificativa().trim().length() < 15)
            throw new IllegalArgumentException("Justificativa deve ter no mínimo 15 caracteres.");
        if (req.getJustificativa().trim().length() > 255)
            throw new IllegalArgumentException("Justificativa deve ter no máximo 255 caracteres.");
    }
}
