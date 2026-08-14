package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.fiscal.builder.NfeXmlBuilder;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.domain.nfe.*;
import br.com.borurio.fiscal.dto.NfeEmissaoItem;
import br.com.borurio.fiscal.dto.NfeEmissaoRequest;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.fiscal.dto.NfeSefazRetorno;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.NcmService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.fiscal.utils.CpfCnpjValidator;
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
import java.util.Objects;
import java.security.SecureRandom;

/**
 * Bridge entre a camada de negócio e o motor fiscal.
 * Vive em borurio-web: único módulo que depende de borurio-app + borurio-fiscal.
 */
@Service
public class NfeGeracaoService {

    private static final Logger log = LoggerFactory.getLogger(NfeGeracaoService.class);

    /**
     * Texto obrigatório na tag dest/xNome para NF-e emitida em homologação (tpAmb=2).
     * Evita a Rejeição 598 da SEFAZ ("Razão Social do destinatário diferente de..."). Não se
     * aplica em produção (tpAmb=1), onde a razão social real do destinatário é usada.
     */
    private static final String XNOME_DESTINATARIO_HOMOLOGACAO =
            "NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL";

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
    private final NfeSequenciaService sequenciaService;
    private final NfeSefazRetornoParser retornoParser;
    private final NfeDocumentoService documentoService;
    private final EmpresaCertificadoService empresaCertificadoService;
    private final OmsCertificadoService omsCertificadoService;
    private final NfeEmissaoService nfeEmissaoService;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeGeracaoService(EmitenteProperties emitente,
                             NfeXmlBuilder nfeXmlBuilder,
                             NfeOrquestradorService nfeOrquestradorService,
                             NfeLogService nfeLogService,
                             NcmService ncmService,
                             NfeSequenciaService sequenciaService,
                             NfeSefazRetornoParser retornoParser,
                             NfeDocumentoService documentoService,
                             EmpresaCertificadoService empresaCertificadoService,
                             OmsCertificadoService omsCertificadoService,
                             NfeEmissaoService nfeEmissaoService) {
        this.emitente = emitente;
        this.nfeXmlBuilder = nfeXmlBuilder;
        this.nfeOrquestradorService = nfeOrquestradorService;
        this.nfeLogService = nfeLogService;
        this.ncmService = ncmService;
        this.sequenciaService = sequenciaService;
        this.retornoParser = retornoParser;
        this.documentoService = documentoService;
        this.empresaCertificadoService = empresaCertificadoService;
        this.omsCertificadoService = omsCertificadoService;
        this.nfeEmissaoService = nfeEmissaoService;
    }

    /**
     * Assinatura única e explícita: cada chamador declara a modalidade de frete do seu fluxo
     * (ver ModalidadeFrete). Não existe overload que a omita — isso evitaria que um novo
     * chamador esqueça de declarar o fluxo e herde silenciosamente um valor incorreto.
     *
     * Sem emissaoId (Gate 1 do ciclo do nNF) — usado pelo caminho legado (NfeEnvioController,
     * deprecated) e por testes que não passam pelo ciclo de nfe_emissao. O fluxo real
     * (PedidoEmissaoService) usa o overload abaixo.
     */
    public NfeGeracaoResult gerar(NfeEmissaoRequest req, Empresa empresa, ModalidadeFrete modalidadeFrete) throws Exception {
        return gerar(req, empresa, modalidadeFrete, null);
    }

    /**
     * @param emissaoId id da nfe_emissao (Gate 1) cujo ciclo esta tentativa pertence — se não
     *                  nulo, a chave é persistida (NfeEmissaoService.marcarTransmitido) assim que
     *                  calculada, ANTES da chamada à SEFAZ, para que uma reconciliação futura
     *                  (Gate 3) saiba qual chave consultar em caso de timeout.
     */
    public NfeGeracaoResult gerar(NfeEmissaoRequest req, Empresa empresa, ModalidadeFrete modalidadeFrete,
                                   Long emissaoId) throws Exception {
        Objects.requireNonNull(modalidadeFrete, "modalidadeFrete não pode ser nulo");
        validarRequest(req);
        validarEnderecoEmitente(empresa);

        // Achado de code review 14-08-2026: checagem era só != null, não isBlank — Empresa.uf=""
        // caía silenciosamente no mesmo ramo do "válido". Defesa em profundidade: o caso real já
        // é barrado antes disso por validarEnderecoEmitente (linha acima), mas a expressão em si
        // precisa ser correta por conta própria.
        //
        // Banca 14-08-2026 (3ª rodada): os dois ramos são estruturalmente diferentes, não uma
        // única expressão condicional — misturar os dois no mesmo `? :` foi o que permitiu, antes
        // desta correção, que uma Empresa real com UF em branco caísse no MESMO ramo de fallback
        // "SP" do caminho administrativo legado. Empresa presente NUNCA cai em SP por omissão —
        // se chegou aqui com UF em branco (não deveria, validarEnderecoEmitente já bloqueou acima),
        // o valor canonicalizado continua nulo/em branco e falha adiante em resolverCUF, nunca
        // silenciosamente em SP. SP como último recurso só existe no caminho legado (empresa nula,
        // NfeEnvioController sem Empresa resolvida) — mesmo fallback que o outro endpoint legado
        // (NfeOrquestradorService.processar(xmlNfe, cnpjEmitente), 2 args) já tem.
        //
        // Canonicalização única (Locale.ROOT, trim+upper) aplicada AQUI, uma vez só — o mesmo
        // valor de ufEmitente alimenta cUF/chave (resolverCUF), ide (idDest), o endereço do
        // emitente no XML (montarEmit) e o transporte (NfeOrquestradorService.processar). Antes
        // desta correção, montarEmit lia empresa.getUf()/emitente.getUf() cru de novo, por conta
        // própria — risco real de UF diferente chegar ao XML e ao transporte para o mesmo pedido.
        String ufEmitente = empresa != null
                ? br.com.borurio.fiscal.config.SefazRotaResolver.canonicalizarUf(empresa.getUf())
                : br.com.borurio.fiscal.config.SefazRotaResolver.canonicalizarUf(
                        !isBlank(emitente.getUf()) ? emitente.getUf() : "SP");
        String cnpjEmitente = empresa != null && empresa.getCnpj() != null
                ? empresa.getCnpj().replaceAll("\\D", "") : apenasDigitos(emitente.getCnpj());

        String cUF  = resolverCUF(ufEmitente);
        String cnpj = cnpjEmitente;
        String serie = padLeft(req.getSerie(), 3);

        // Se o número não for informado, o sequenciador atribui o próximo de forma atômica.
        String numeroStr;
        if (req.getNumero() == null || req.getNumero().isBlank()) {
            int proximo = sequenciaService.proximoNumero(cnpj, req.getSerie());
            numeroStr = String.valueOf(proximo);
            log.info("[NfeGeracao] Número auto-atribuído pelo sequenciador | serie={} | numero={}", req.getSerie(), proximo);
        } else {
            numeroStr = req.getNumero();
        }
        String nNF = padLeft(numeroStr, 9);  // zero-padded for chave43 key
        String cNF    = gerarCNF();
        String aaaMM  = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMM"));
        String tpEmis = "1";

        String chave43 = cUF + aaaMM + cnpj + "55" + serie + nNF + tpEmis + cNF;
        String cDV     = calcularCDV(chave43);
        String chave   = chave43 + cDV;

        // Congela a chave desta tentativa ANTES de qualquer chamada de rede — Gate 1 do ciclo do
        // nNF. Sem isso, uma reconciliação futura (Gate 3) não saberia qual chave consultar na
        // SEFAZ em caso de timeout.
        if (emissaoId != null) {
            nfeEmissaoService.marcarTransmitido(emissaoId, chave);
        }

        // SEFAZ XSD TSerie=0|[1-9][0-9]{0,2} and TNF=[1-9][0-9]{0,8}: no leading zeros in XML elements
        String serieXml = stripLeadingZeros(serie);
        String nNFXml   = stripLeadingZeros(nNF);

        NFe nfe = new NFe();

        InfNFe inf = new InfNFe();
        inf.setId("NFe" + chave);
        inf.setIde(montarIde(req, cUF, cNF, nNFXml, serieXml, cDV, ufEmitente, empresa));
        inf.setEmit(montarEmit(empresa, ufEmitente));
        inf.setDest(montarDest(req));
        inf.setDet(montarDet(req));
        inf.setTotal(montarTotal(req));

        nfe.setInfNFe(inf);

        String xml = nfeXmlBuilder.build(nfe, modalidadeFrete);

        log.info("[NfeGeracao] Iniciando transmissão | chave={} | cnpj={}", chave, cnpj);

        // Sessão OMS: cert selecionado por jti + cnpj emitente (multi-CNPJ). Sessão de usuário: cert vem do arquivo.
        String jtiOms = EmpresaContextHolder.getJtiAuth();
        CertificadoContexto certCtx = (jtiOms != null)
                ? omsCertificadoService.resolverPorJtiECnpj(jtiOms, cnpjEmitente)
                : empresaCertificadoService.resolverPorEmpresa(empresa).orElse(null);

        Long empresaId = empresa != null ? empresa.getId() : null;

        try {
            String resposta = nfeOrquestradorService.processar(xml, cnpj, ufEmitente, certCtx);
            registrarLog(chave, cnpj, empresaId, "TRANSMISSAO_SEFAZ", "SUCCESS",
                    "NF-e gerada e transmitida via /api/fiscal/nfe/gerar", xml, resposta);

            // Fase 4: parsear retorno SEFAZ e persistir estado do documento
            NfeSefazRetorno retorno = retornoParser.parse(resposta);
            BigDecimal valorTotal = req.getItens().stream()
                    .map(NfeEmissaoItem::getValorTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            documentoService.salvarComRetorno(
                    chave, nNFXml, serieXml, cnpj,
                    apenasDigitos(req.getDestCnpjCpf()), req.getDestRazaoSocial(),
                    valorTotal, tpAmb, LocalDateTime.now(), xml, retorno);

            log.info("[NfeGeracao] cStat={} | xMotivo={} | nProt={} | chave={}",
                    retorno.getCStat(), retorno.getXMotivo(), retorno.getNProt(), chave);

            return new NfeGeracaoResult(chave, resposta);
        } catch (Exception e) {
            registrarLog(chave, cnpj, empresaId, "ERRO_TRANSMISSAO", "ERROR",
                    e.getMessage(), xml, null);
            throw e;
        }
    }

    private void registrarLog(String chave, String cnpj, Long empresaId,
                              String tipoEvento, String status,
                              String descricao, String xmlEnvio, String xmlRetorno) {
        try {
            nfeLogService.salvar(NfeLog.builder()
                    .chaveNfe(chave)
                    .tipoEvento(tipoEvento)
                    .status(status)
                    .descricao(descricao)
                    .cnpjEmitente(cnpj)
                    .empresaId(empresaId)
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
                          String nNF, String serie, String cDV, String ufEmitente,
                          Empresa empresa) {
        Ide ide = new Ide();
        ide.setCUF(cUF);
        ide.setCNF(cNF);
        ide.setNatOp(req.getNaturezaOperacao() != null ? req.getNaturezaOperacao() : "VENDA DE MERCADORIA");
        ide.setSerie(serie);
        ide.setNNF(nNF);
        ide.setDhEmi(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "-03:00");
        ide.setTpNF("1");
        ide.setIdDest(resolverIdDest(req.getDestUf(), ufEmitente));
        ide.setCMunFG(emitente.getCodigoMunicipio());
        ide.setTpImp("1");
        ide.setTpEmis("1");
        ide.setCDV(cDV);
        ide.setTpAmb(String.valueOf(tpAmb));
        ide.setFinNFe("1");
        ide.setIndFinal(resolverIndFinalPadrao(empresa));
        ide.setIndPres("9");
        ide.setIndIntermed("0"); // 0 = venda direta, sem intermediador/marketplace (emitente vende em nome próprio)
        ide.setProcEmi("0");
        ide.setVerProc("1.0.0");
        return ide;
    }

    /**
     * indFinal é um padrão fiscal configurável por empresa emitente — não é inferido do
     * documento (CPF/CNPJ) do destinatário, pois um CNPJ também pode ser consumidor final.
     * "1" é o fallback de compatibilidade para empresa nula/campo ausente (fluxo legado e
     * dados pré-P0.4); valor fora de "0"/"1" falha explicitamente em vez de normalizar.
     */
    String resolverIndFinalPadrao(Empresa empresa) {
        if (empresa == null) return "1";
        String valor = empresa.getIndFinalPadrao();
        if (valor == null || valor.isBlank()) return "1";
        if (!"0".equals(valor) && !"1".equals(valor)) {
            throw br.com.borurio.app.exception.BusinessException.indFinalPadraoInvalido(empresa.getId(), valor);
        }
        return valor;
    }

    /**
     * @param ufEmitente UF já canonicalizada (ver {@code gerar}) — nunca relida crua de
     *                   {@code empresa.getUf()}/{@code emitente.getUf()} aqui. Banca 14-08-2026
     *                   (3ª rodada): antes desta correção, o endereço do emitente no XML e o
     *                   cUF/chave/transporte liam a UF por caminhos independentes — o mesmo
     *                   pedido podia, em tese, ter valores de UF diferentes em cada lugar.
     */
    private Emit montarEmit(Empresa empresa, String ufEmitente) {
        Emit emit = new Emit();

        if (empresa != null) {
            emit.setCnpj(apenasDigitos(empresa.getCnpj()));
            emit.setXNome(empresa.getRazaoSocial());
            emit.setXFant(empresa.getNomeFantasia());
            emit.setIe(empresa.getIe());
            emit.setCrt(empresa.getCrt() != null ? empresa.getCrt() : "1");

            EnderEmit ender = new EnderEmit();
            ender.setXLgr(empresa.getLogradouro());
            ender.setNro(empresa.getNumero());
            ender.setXBairro(empresa.getBairro());
            ender.setCMun(empresa.getCodigoMunicipio());
            ender.setXMun(empresa.getMunicipio());
            ender.setUF(ufEmitente);
            ender.setCEP(apenasDigitos(empresa.getCep()));
            ender.setCPais("1058");
            ender.setXPais("Brasil");
            emit.setEnderEmit(ender);
        } else {
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
            ender.setUF(ufEmitente);
            ender.setCEP(apenasDigitos(emitente.getCep()));
            ender.setCPais("1058");
            ender.setXPais("Brasil");
            emit.setEnderEmit(ender);
        }

        return emit;
    }

    private Dest montarDest(NfeEmissaoRequest req) {
        Dest dest = new Dest();
        dest.setCpfCnpj(apenasDigitos(req.getDestCnpjCpf()));
        dest.setXNome(resolverNomeDestinatario(req.getDestRazaoSocial()));
        dest.setIndIEDest(resolverIndIEDest(req.getDestIe()));
        dest.setIe(req.getDestIe());

        if (req.getDestLogradouro() != null && !req.getDestLogradouro().isBlank()) {
            EnderDest ender = new EnderDest();
            ender.setXLgr(req.getDestLogradouro());
            ender.setNro(req.getDestNumero() != null ? req.getDestNumero() : "SN");
            ender.setXCpl(req.getDestComplemento());
            ender.setXBairro(req.getDestBairro());
            ender.setCMun(req.getDestCodigoMunicipio());
            ender.setXMun(req.getDestMunicipio());
            ender.setUF(req.getDestUf());
            ender.setCEP(apenasDigitos(req.getDestCep()));
            ender.setCPais("1058");
            ender.setXPais("Brasil");
            dest.setEnderDest(ender);
        }

        return dest;
    }

    /**
     * Em homologação (tpAmb=2), a SEFAZ rejeita (cStat=598) qualquer xNome de destinatário
     * diferente do texto fixo abaixo — a razão social real só pode ir no XML em produção.
     * O nome real do destinatário nunca é alterado no pedido/cliente/banco; a substituição
     * acontece só aqui, no momento de montar o XML.
     */
    String resolverNomeDestinatario(String nomeOriginal) {
        if (tpAmb == 2) {
            return XNOME_DESTINATARIO_HOMOLOGACAO;
        }
        return nomeOriginal;
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
            det.setOrig(item.getOrigem());
            det.setCsosn(item.getCsosn());
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

    String resolverIdDest(String destUf, String ufEmitente) {
        if (destUf == null || destUf.isBlank()) return "1";
        return destUf.equalsIgnoreCase(ufEmitente != null ? ufEmitente : emitente.getUf()) ? "1" : "2";
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

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String gerarCNF() {
        return String.format("%08d", SECURE_RANDOM.nextInt(100_000_000));
    }

    private String apenasDigitos(String s) {
        if (s == null) return "";
        return s.replaceAll("\\D", "");
    }

    private String padLeft(String s, int length) {
        if (s == null) s = "";
        return String.format("%" + length + "s", s).replace(' ', '0');
    }

    private String stripLeadingZeros(String s) {
        if (s == null || s.isBlank()) return "0";
        String stripped = s.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    private String formatDecimal(BigDecimal value, int scale) {
        if (value == null) return "0." + "0".repeat(scale);
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private void validarRequest(NfeEmissaoRequest req) {
        if (req.getSerie() == null || req.getSerie().isBlank())
            throw new IllegalArgumentException("Série da NF-e é obrigatória.");
        // numero é opcional — se ausente, o sequenciador atribui automaticamente
        if (req.getNumero() != null && !req.getNumero().isBlank()) {
            if (!req.getNumero().matches("\\d{1,9}"))
                throw new IllegalArgumentException("Número da NF-e deve conter entre 1 e 9 dígitos numéricos.");
        }
        if (req.getDestCnpjCpf() == null || req.getDestCnpjCpf().isBlank())
            throw new IllegalArgumentException("CNPJ/CPF do destinatário é obrigatório.");
        CpfCnpjValidator.validar(req.getDestCnpjCpf());
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

    /**
     * Intercepta ANTES de montar/transmitir o XML: evita round-trip desnecessário à SEFAZ
     * quando o cadastro do emitente não tem os dados obrigatórios de endereço.
     */
    private void validarEnderecoEmitente(Empresa empresa) {
        if (empresa == null) return;
        boolean incompleto = isBlank(empresa.getLogradouro())
                || isBlank(empresa.getNumero())
                || isBlank(empresa.getBairro())
                || isBlank(empresa.getCodigoMunicipio())
                || isBlank(empresa.getMunicipio())
                || isBlank(empresa.getCep())
                // Achado de code review 14-08-2026: UF em branco (string vazia, não nula) só
                // era pega tarde, dentro do check obrigatório de NfeOrquestradorService, depois
                // de já ter congelado nNF/chave — UF é parte do endereço do emitente, mesma
                // checagem antecipada dos demais campos.
                || isBlank(empresa.getUf());
        if (incompleto) {
            throw br.com.borurio.app.exception.BusinessException.emitterAddressIncomplete();
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
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
