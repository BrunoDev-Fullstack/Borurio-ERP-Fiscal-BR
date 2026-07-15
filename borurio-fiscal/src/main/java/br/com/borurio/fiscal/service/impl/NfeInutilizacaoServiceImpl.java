package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.config.SefazProperties;
import br.com.borurio.fiscal.dto.NfeInutilizacaoRequest;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.AssinaturaXmlService;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeInutilizacaoService;
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
import java.util.Map;

@Slf4j
@Service
public class NfeInutilizacaoServiceImpl implements NfeInutilizacaoService {

    private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";
    private static final String WSDL_INUT_NS =
            "http://www.portalfiscal.inf.br/nfe/wsdl/NFeInutilizacao4";
    private static final String MOD = "55";

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
    private final CertificadoService certificadoService;
    private final SefazProperties sefazProperties;
    private final NfeLogService nfeLogService;
    private final EmitenteProperties emitente;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeInutilizacaoServiceImpl(AssinaturaXmlService assinaturaXmlService,
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
    public String inutilizar(NfeInutilizacaoRequest req) throws Exception {
        return inutilizar(req, null, null, null);
    }

    @Override
    public String inutilizar(NfeInutilizacaoRequest req, String cnpjEmitente, String uf,
                              CertificadoContexto certContexto) throws Exception {
        validar(req);

        String cnpj   = apenasDigitos(cnpjEmitente != null ? cnpjEmitente : emitente.getCnpj());
        String cUF    = resolverCUF(uf);
        String ano    = req.getAno().trim();
        String serie  = padLeft(req.getSerie(), 3);
        String nNFIni = padLeft(req.getNNFIni(), 9);
        String nNFFin = padLeft(req.getNNFFin(), 9);
        String anoFull = "20" + ano;

        // Id = ID + cUF(2) + AAAA(4) + CNPJ(14) + mod(2) + serie(3) + nNFIni(9) + nNFFin(9)
        String chave43 = cUF + anoFull + cnpj + MOD + serie + nNFIni + nNFFin;
        String cDV     = calcularCDV(chave43);
        String idInut  = "ID" + chave43 + cDV;

        String xmlInut = montarInutNFe(idInut, cUF, ano, cnpj, serie, nNFIni, nNFFin,
                req.getJustificativa());

        log.info("[Inutilizacao] Assinando inutNFe | id={} | tpAmb={} | cnpj={}", idInut, tpAmb, cnpj);
        String xmlAssinado = certContexto != null
                ? assinaturaXmlService.assinarInutilizacao(xmlInut, certContexto)
                : assinaturaXmlService.assinarInutilizacao(xmlInut);

        String soapEnvelope = montarSoap(xmlAssinado);
        String urlWs        = sefazProperties.getInutilizacao();

        registrarLog(idInut, cnpj, "PENDING", "Inutilização iniciada | serie=" + serie
                + " | nNFIni=" + nNFIni + " | nNFFin=" + nNFFin, xmlAssinado, null);

        log.info("[Inutilizacao] Enviando para SEFAZ | url={}", urlWs);

        try {
            String resposta = enviarSoap(urlWs, soapEnvelope, certContexto);
            registrarLog(idInut, cnpj, "SUCCESS", "Inutilização transmitida com sucesso",
                    xmlAssinado, resposta);
            log.info("[Inutilizacao] Resposta SEFAZ recebida | id={}", idInut);
            return resposta;
        } catch (Exception e) {
            registrarLog(idInut, cnpj, "ERROR", "Erro: " + e.getMessage(), xmlAssinado, null);
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Montagem do XML inutNFe
    // Não usar text block: SEFAZ é sensível a whitespace dentro de nfeDadosMsg.
    // -------------------------------------------------------------------------
    private String montarInutNFe(String idInut, String cUF, String ano, String cnpj,
                                  String serie, String nNFIni, String nNFFin,
                                  String justificativa) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<inutNFe versao=\"4.00\" xmlns=\"" + NFE_NS + "\">" +
               "<infInut Id=\"" + idInut + "\">" +
               "<tpAmb>" + tpAmb + "</tpAmb>" +
               "<xServ>INUTILIZAR</xServ>" +
               "<cUF>" + cUF + "</cUF>" +
               "<ano>" + ano + "</ano>" +
               "<CNPJ>" + cnpj + "</CNPJ>" +
               "<mod>" + MOD + "</mod>" +
               "<serie>" + serie + "</serie>" +
               "<nNFIni>" + nNFIni + "</nNFIni>" +
               "<nNFFin>" + nNFFin + "</nNFFin>" +
               "<xJust>" + justificativa.trim() + "</xJust>" +
               "</infInut>" +
               "</inutNFe>";
    }

    private String montarSoap(String xmlAssinado) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
               "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\">" +
               "<soap12:Body>" +
               "<nfeDadosMsg xmlns=\"" + WSDL_INUT_NS + "\">" +
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
                    .tipoEvento("INUTILIZACAO")
                    .status(status)
                    .descricao(descricao)
                    .cnpjEmitente(cnpj)
                    .xmlEnvio(xmlEnvio)
                    .xmlRetorno(xmlRetorno)
                    .dataEvento(LocalDateTime.now())
                    .usuario("system")
                    .build());
        } catch (Exception e) {
            log.error("[Inutilizacao] Falha ao persistir log | id={} | erro={}", chave, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Validações
    // -------------------------------------------------------------------------

    private void validar(NfeInutilizacaoRequest req) {
        if (req.getAno() == null || !req.getAno().trim().matches("\\d{2}"))
            throw new IllegalArgumentException("Ano inválido: deve conter exatamente 2 dígitos (ex: 26).");

        if (req.getSerie() == null || req.getSerie().isBlank())
            throw new IllegalArgumentException("Série é obrigatória.");
        int serie;
        try { serie = Integer.parseInt(req.getSerie().trim()); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Série deve ser numérica.");
        }
        if (serie < 0 || serie > 999)
            throw new IllegalArgumentException("Série deve estar entre 0 e 999.");

        long ini = parseLongObrigatorio(req.getNNFIni(), "nNFIni");
        long fin = parseLongObrigatorio(req.getNNFFin(), "nNFFin");
        if (ini < 1 || fin < 1)
            throw new IllegalArgumentException("Número de NF-e deve ser maior que zero.");
        if (ini > fin)
            throw new IllegalArgumentException("nNFIni deve ser menor ou igual a nNFFin.");

        if (req.getJustificativa() == null || req.getJustificativa().trim().length() < 15)
            throw new IllegalArgumentException("Justificativa deve ter no mínimo 15 caracteres.");
        if (req.getJustificativa().trim().length() > 255)
            throw new IllegalArgumentException("Justificativa deve ter no máximo 255 caracteres.");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolverCUF(String ufOverride) {
        String uf = ufOverride != null ? ufOverride : emitente.getUf();
        if (uf == null || uf.isBlank()) return "35";
        String cuf = UF_PARA_CUF.get(uf.toUpperCase());
        return cuf != null ? cuf : "35";
    }

    private String apenasDigitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private String padLeft(String s, int length) {
        if (s == null) s = "";
        s = s.trim().replaceAll("\\D", "");
        return String.format("%" + length + "s", s).replace(' ', '0');
    }

    private long parseLongObrigatorio(String valor, String campo) {
        if (valor == null || valor.isBlank())
            throw new IllegalArgumentException(campo + " é obrigatório.");
        try { return Long.parseLong(valor.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(campo + " deve ser numérico.");
        }
    }

    private String calcularCDV(String chave) {
        int soma = 0;
        int peso = 2;
        for (int i = chave.length() - 1; i >= 0; i--) {
            soma += (chave.charAt(i) - '0') * peso;
            peso = (peso == 9) ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return String.valueOf(resto < 2 ? 0 : 11 - resto);
    }
}
