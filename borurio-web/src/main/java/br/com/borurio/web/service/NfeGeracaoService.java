package br.com.borurio.web.service;

import br.com.borurio.fiscal.builder.NfeXmlBuilder;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.domain.nfe.*;
import br.com.borurio.fiscal.dto.NfeEmissaoItem;
import br.com.borurio.fiscal.dto.NfeEmissaoRequest;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.NcmService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Bridge entre a camada de negócio e o motor fiscal.
 * Vive em borurio-web: único módulo que depende de borurio-app + borurio-fiscal.
 */
@Service
public class NfeGeracaoService {

    private static final Logger log = LoggerFactory.getLogger(NfeGeracaoService.class);

    private static final Map<String, String> UF_PARA_CUF = Map.ofEntries(
            Map.entry("AC", "12"), Map.entry("AL", "27"), Map.entry("AP", "16"),
            Map.entry("AM", "13"), Map.entry("BA", "29"), Map.entry("CE", "23"),
            Map.entry("DF", "53"), Map.entry("ES", "32"), Map.entry("GO", "52"),
            Map.entry("MA", "21"), Map.entry("MT", "51"), Map.entry("MS", "50"),
            Map.entry("MG", "31"), Map.entry("PA", "15"), Map.entry("PB", "25"),
            Map.entry("PR", "41"), Map.entry("PE", "26"), Map.entry("PI", "22"),
            Map.entry("RJ", "33"), Map.entry("RN", "24"), Map.entry("RS", "43"),
            Map.entry("RO", "11"), Map.entry("RR", "14"), Map.entry("SC", "42"),
            Map.entry("SP", "35"), Map.entry("SE", "28"), Map.entry("TO", "17")
    );

    private final EmitenteProperties emitente;
    private final NfeXmlBuilder nfeXmlBuilder;
    private final NfeOrquestradorService nfeOrquestradorService;
    private final NfeLogService nfeLogService;
    private final NcmService ncmService;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeGeracaoService(EmitenteProperties emitente,
                             NfeXmlBuilder nfeXmlBuilder,
                             NfeOrquestradorService nfeOrquestradorService,
                             NfeLogService nfeLogService,
                             NcmService ncmService) {
        this.emitente = emitente;
        this.nfeXmlBuilder = nfeXmlBuilder;
        this.nfeOrquestradorService = nfeOrquestradorService;
        this.nfeLogService = nfeLogService;
        this.ncmService = ncmService;
    }

    public String gerar(NfeEmissaoRequest req) throws Exception {
        validarRequest(req);

        String cUF    = resolverCUF(emitente.getUf());
        String cnpj   = apenasDigitos(emitente.getCnpj());
        String serie  = padLeft(req.getSerie(), 3);
        String nNF    = padLeft(req.getNumero(), 9);
        String cNF    = gerarCNF();
        String aaaMM  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMM"));
        String tpEmis = "1";

        String chave43 = cUF + aaaMM + cnpj + "55" + serie + nNF + tpEmis + cNF;
        String cDV     = calcularCDV(chave43);
        String chave   = chave43 + cDV;

        NFe nfe = new NFe();

        InfNFe inf = new InfNFe();
        inf.setId("NFe" + chave);
        inf.setIde(montarIde(req, cUF, cNF, nNF, serie, cDV));
        inf.setEmit(montarEmit());
        inf.setDest(montarDest(req));
        inf.setDet(montarDet(req));
        inf.setTotal(montarTotal(req));

        nfe.setInfNFe(inf);

        String xml = nfeXmlBuilder.build(nfe);

        log.info("[NfeGeracao] Iniciando transmissão | chave={} | cnpj={}", chave, cnpj);

        try {
            String resposta = nfeOrquestradorService.processar(xml, cnpj);
            registrarLog(chave, cnpj, "TRANSMISSAO_SEFAZ", "SUCCESS",
                    "NF-e gerada e transmitida via /api/fiscal/nfe/gerar", xml, resposta);
            return resposta;
        } catch (Exception e) {
            registrarLog(chave, cnpj, "ERRO_TRANSMISSAO", "ERROR",
                    e.getMessage(), xml, null);
            throw e;
        }
    }

    private void registrarLog(String chave, String cnpj, String tipoEvento, String status,
                              String descricao, String xmlEnvio, String xmlRetorno) {
        try {
            nfeLogService.salvar(NfeLog.builder()
                    .chaveNfe(chave)
                    .tipoEvento(tipoEvento)
                    .status(status)
                    .descricao(descricao)
                    .cnpjEmitente(cnpj)
                    .xmlEnvio(xmlEnvio)
                    .xmlRetorno(xmlRetorno)
                    .dataEvento(LocalDateTime.now())
                    .build());
        } catch (Exception logEx) {
            // Falha de log nunca deve interromper o fluxo fiscal principal.
            log.error("[NfeGeracao] Falha ao persistir nfe_log | chave={} | erro={}", chave, logEx.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Montagem dos blocos
    // -------------------------------------------------------------------------

    private Ide montarIde(NfeEmissaoRequest req, String cUF, String cNF,
                          String nNF, String serie, String cDV) {
        Ide ide = new Ide();
        ide.setCUF(cUF);
        ide.setCNF(cNF);
        ide.setNatOp(req.getNaturezaOperacao() != null ? req.getNaturezaOperacao() : "VENDA DE MERCADORIA");
        ide.setSerie(serie);
        ide.setNNF(nNF);
        ide.setDhEmi(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "-03:00");
        ide.setTpNF("1");
        ide.setIdDest(resolverIdDest(req.getDestUf()));
        ide.setCMunFG(emitente.getCodigoMunicipio());
        ide.setTpImp("1");
        ide.setTpEmis("1");
        ide.setCDV(cDV);
        ide.setTpAmb(String.valueOf(tpAmb));
        ide.setFinNFe("1");
        ide.setIndFinal("0");
        ide.setIndPres("9");
        ide.setProcEmi("0");
        ide.setVerProc("1.0.0");
        return ide;
    }

    private Emit montarEmit() {
        Emit emit = new Emit();
        emit.setCnpj(apenasDigitos(emitente.getCnpj()));
        emit.setXNome(emitente.getRazaoSocial());
        emit.setXFant(emitente.getNomeFantasia());
        emit.setIe(emitente.getIe());
        emit.setCrt(emitente.getCrt());

        EnderEmit ender = new EnderEmit();
        ender.setXLgr(emitente.getLogradouro());
        ender.setNro(emitente.getNumero());
        ender.setXBairro(emitente.getBairro());
        ender.setCMun(emitente.getCodigoMunicipio());
        ender.setXMun(emitente.getMunicipio());
        ender.setUF(emitente.getUf());
        ender.setCEP(apenasDigitos(emitente.getCep()));
        ender.setCPais("1058");
        ender.setXPais("Brasil");
        emit.setEnderEmit(ender);

        return emit;
    }

    private Dest montarDest(NfeEmissaoRequest req) {
        Dest dest = new Dest();
        dest.setCpfCnpj(apenasDigitos(req.getDestCnpjCpf()));
        dest.setXNome(req.getDestRazaoSocial());
        dest.setIndIEDest(resolverIndIEDest(req.getDestIe()));
        dest.setIe(req.getDestIe());
        return dest;
    }

    private List<Det> montarDet(NfeEmissaoRequest req) {
        List<Det> lista = new ArrayList<>();
        int nItem = 1;
        for (NfeEmissaoItem item : req.getItens()) {
            Produto prod = new Produto();
            prod.setCProd(item.getCodigoProduto());
            prod.setXProd(item.getDescricao());
            prod.setNCM(item.getNcm());
            prod.setCFOP(item.getCfop());
            prod.setUCom(item.getUnidade());
            prod.setQCom(formatDecimal(item.getQuantidade(), 4));
            prod.setVUnCom(formatDecimal(item.getValorUnitario(), 10));
            prod.setVProd(formatDecimal(item.getValorTotal(), 2));

            Det det = new Det();
            det.setNItem(nItem++);
            det.setProd(prod);
            lista.add(det);
        }
        return lista;
    }

    private Total montarTotal(NfeEmissaoRequest req) {
        BigDecimal total = req.getItens().stream()
                .map(NfeEmissaoItem::getValorTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Total t = new Total();
        t.setVProd(formatDecimal(total, 2));
        t.setVNF(formatDecimal(total, 2));
        return t;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolverCUF(String uf) {
        String cuf = UF_PARA_CUF.get(uf != null ? uf.toUpperCase() : "");
        if (cuf == null) throw new IllegalArgumentException("UF do emitente inválida: " + uf);
        return cuf;
    }

    private String resolverIdDest(String destUf) {
        if (destUf == null || destUf.isBlank()) return "1";
        return destUf.equalsIgnoreCase(emitente.getUf()) ? "1" : "2";
    }

    private String resolverIndIEDest(String ie) {
        if (ie == null || ie.isBlank()) return "9";
        if ("ISENTO".equalsIgnoreCase(ie.trim())) return "2";
        return "1";
    }

    private String calcularCDV(String chave43) {
        int soma = 0;
        int peso = 2;
        for (int i = chave43.length() - 1; i >= 0; i--) {
            soma += (chave43.charAt(i) - '0') * peso;
            peso = (peso == 9) ? 2 : peso + 1;
        }
        int resto = soma % 11;
        return String.valueOf(resto < 2 ? 0 : 11 - resto);
    }

    private String gerarCNF() {
        return String.format("%08d", new Random().nextInt(100_000_000));
    }

    private String apenasDigitos(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D", "");
    }

    private String padLeft(String s, int length) {
        if (s == null) s = "";
        return String.format("%" + length + "s", s).replace(' ', '0');
    }

    private String formatDecimal(BigDecimal value, int scale) {
        if (value == null) return "0." + "0".repeat(scale);
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private void validarRequest(NfeEmissaoRequest req) {
        if (req.getSerie() == null || req.getSerie().isBlank())
            throw new IllegalArgumentException("Série da NF-e é obrigatória.");
        if (req.getNumero() == null || req.getNumero().isBlank())
            throw new IllegalArgumentException("Número da NF-e é obrigatório.");
        if (req.getDestCnpjCpf() == null || req.getDestCnpjCpf().isBlank())
            throw new IllegalArgumentException("CNPJ/CPF do destinatário é obrigatório.");
        if (req.getDestRazaoSocial() == null || req.getDestRazaoSocial().isBlank())
            throw new IllegalArgumentException("Razão social do destinatário é obrigatória.");
        if (req.getItens() == null || req.getItens().isEmpty())
            throw new IllegalArgumentException("A NF-e deve ter ao menos um item.");
        for (NfeEmissaoItem item : req.getItens()) {
            if (item.getQuantidade() == null || item.getQuantidade().compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("Quantidade inválida no item: " + item.getCodigoProduto());
            if (item.getValorUnitario() == null || item.getValorUnitario().compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("Valor unitário inválido no item: " + item.getCodigoProduto());
            validarNcmNaTabela(item.getNcm(), item.getCodigoProduto());
        }
    }

    private void validarNcmNaTabela(String ncm, String codigoProduto) {
        if (ncm == null || !ncm.matches("\\d{8}")) {
            throw new IllegalArgumentException(
                    "NCM inválido no item " + codigoProduto + ": deve conter exatamente 8 dígitos numéricos.");
        }
        try {
            if (ncmService.buscarPorCodigo(ncm) == null) {
                throw new IllegalArgumentException(
                        "NCM '" + ncm + "' do item " + codigoProduto + " não encontrado na tabela NCM oficial.");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[NfeGeracao] Falha ao consultar tabela NCM | ncm={} | erro={} — validação ignorada", ncm, e.getMessage());
        }
    }
}
