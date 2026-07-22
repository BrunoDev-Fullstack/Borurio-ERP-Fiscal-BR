package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.domain.nfe.ModalidadeFrete;
import br.com.borurio.fiscal.dto.NfeEmissaoItem;
import br.com.borurio.fiscal.dto.NfeEmissaoRequest;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.fiscal.dto.NfeSefazRetorno;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import br.com.borurio.web.dto.ReservaFiscalResultado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge: Pedido + snapshot fiscal → NfeEmissaoRequest → motor fiscal.
 * Após transmissão:
 *   cStat=100  → AUTORIZADO  + baixa definitiva de estoque
 *   cStat≥200  → REJEITADO   + desfaz reserva de estoque
 *   lote aceito sem infProt → AGUARDANDO (reserva mantida — Opção A)
 *   exceção     → ERRO       + desfaz reserva de estoque
 *
 * Reemissão: pedidos em REJEITADO ou ERRO podem chamar emitir() novamente —
 * cada tentativa gera nNF/chave novos via NfeSequenciaService, sem risco de duplicidade na SEFAZ.
 *
 * Concorrência (P0.1): antes de tocar no sequenciador, emitir() reivindica o pedido com um
 * claim atômico (RASCUNHO/REJEITADO/ERRO → EMITINDO via UPDATE condicional). Só a chamada que
 * vence o claim prossegue; qualquer outra chamada concorrente pro mesmo pedidoId — retry de
 * rede, corrida real — recebe EMISSAO_EM_ANDAMENTO em vez de alocar um segundo nNF. EMITINDO
 * nunca é um status terminal: toda falha depois do claim (validação, estoque, SEFAZ) devolve o
 * pedido para ERRO antes de propagar a exceção, então ele nunca fica preso em EMITINDO.
 *
 * emitir() lança BusinessException (não retorna 200 disfarçado de sucesso) quando:
 *   claim perdido       → EMISSAO_EM_ANDAMENTO (retryable=true — outra emissão já está em curso)
 *   REJEITADO         → SEFAZ_REJECTED (cStat/xMotivo no campo `data`, retryable=false — geralmente é dado incorreto)
 *   timeout de rede    → SEFAZ_TIMEOUT (retryable=true)
 *   SEFAZ inacessível  → SEFAZ_UNAVAILABLE (retryable=true)
 *   schema inválido    → XML_SCHEMA_INVALID (retryable=false)
 */
@Service
public class PedidoEmissaoService {

    private static final List<String> STATUS_EMISSIVEIS = List.of("RASCUNHO", "REJEITADO", "ERRO");

    private static final Logger log = LoggerFactory.getLogger(PedidoEmissaoService.class);

    private final PedidoService pedidoService;
    private final NfeGeracaoService nfeGeracaoService;
    private final NfeSefazRetornoParser retornoParser;
    private final EstoqueService estoqueService;
    private final EmpresaMapper empresaMapper;
    private final ReservaFiscalService reservaFiscalService;
    private final EmitenteProperties emitente;

    public PedidoEmissaoService(PedidoService pedidoService,
                                NfeGeracaoService nfeGeracaoService,
                                NfeSefazRetornoParser retornoParser,
                                EstoqueService estoqueService,
                                EmpresaMapper empresaMapper,
                                ReservaFiscalService reservaFiscalService,
                                EmitenteProperties emitente) {
        this.pedidoService     = pedidoService;
        this.nfeGeracaoService = nfeGeracaoService;
        this.retornoParser     = retornoParser;
        this.estoqueService    = estoqueService;
        this.empresaMapper     = empresaMapper;
        this.reservaFiscalService = reservaFiscalService;
        this.emitente           = emitente;
    }

    public NfeGeracaoResult emitir(Long pedidoId) throws Exception {
        Pedido pedido = pedidoService.buscarComItens(pedidoId);

        if (!STATUS_EMISSIVEIS.contains(pedido.getStatus())) {
            throw BusinessException.invalidOrderStatus(
                    "Pedido não pode ser emitido no status atual: " + pedido.getStatus()
                            + ". Permitido apenas para RASCUNHO, REJEITADO ou ERRO.");
        }

        // Claim atômico (P0.1) — o SELECT acima já confirmou status emissível, mas não protege
        // a janela entre leitura e escrita. Esta UPDATE condicional (RASCUNHO/REJEITADO/ERRO →
        // EMITINDO) é o ponto real de exclusão mútua: duas chamadas concorrentes pro mesmo
        // pedido (retry de rede, corrida real) só deixam UMA prosseguir. A outra recebe
        // EMISSAO_EM_ANDAMENTO em vez de alocar um segundo nNF e gerar uma segunda NF-e.
        if (!pedidoService.reivindicarParaEmissao(pedidoId)) {
            throw BusinessException.emissaoEmAndamento(pedidoId);
        }

        String criadoPor;
        Empresa empresa;
        Long empresaId;
        boolean controlaEstoque;
        try {
            if (pedido.getItens() == null || pedido.getItens().isEmpty()) {
                throw new IllegalArgumentException("Pedido sem itens não pode ser emitido.");
            }

            criadoPor = resolverCriadoPor();
            empresa   = resolverEmpresaParaEmissao(pedido);

            // Validação preventiva de coerência fiscal — antes de qualquer reserva de estoque
            // ou de numeração. Achado do Gate 7D: sem isso, um pedido com CFOP incompatível com
            // o destino (ex.: CFOP interestadual para uma operação interna) só é rejeitado pela
            // SEFAZ depois de já ter consumido um número fiscal (nNF) real.
            validarCfopDestino(pedido, empresa);

            // empresa é usado apenas para XML e certificado (CNPJ emitente correto no fluxo multi-CNPJ).
            // Estoque e baixas usam o empresaId do pedido — empresa-âncora onde os produtos foram cadastrados.
            empresaId = pedido.getEmpresaId() != null ? pedido.getEmpresaId()
                    : (EmpresaContextHolder.get() != null ? EmpresaContextHolder.get()
                       : (empresa != null ? empresa.getId() : null));

            // Empresa âncora (dona do estoque) decide se o fluxo controla estoque — não confundir
            // com `empresa`, que pode ser outro CNPJ do mesmo cliente OMS (fluxo multi-CNPJ).
            controlaEstoque = controlaEstoque(empresaId);

            // Reserva ANTES da chamada SEFAZ — lança IllegalStateException (→ 422) se insuficiente
            if (controlaEstoque) {
                estoqueService.reservarItens(pedido.getItens(), empresaId, pedidoId, criadoPor);
            }
        } catch (Exception e) {
            // Falha antes de qualquer chamada à SEFAZ (itens vazios, empresa/certificado não
            // resolvido, estoque insuficiente) — nenhum nNF foi alocado ainda, mas o pedido já
            // está em EMITINDO por causa do claim acima e precisa voltar a um status emissível,
            // senão fica preso pra sempre (EMITINDO não está em STATUS_EMISSIVEIS).
            pedidoService.atualizarStatus(pedidoId, "ERRO", pedido.getChaveNfe());
            throw e;
        }

        // Reserva atômica de série + número + persistência do snapshot no pedido — tudo numa
        // única transação em ReservaFiscalService.reservar() (20-07-2026, revisão pós-review:
        // antes a escrita do snapshot era uma chamada separada depois desta, criando uma janela
        // em que o número já estava consumido sem o snapshot correspondente no pedido).
        // "Emissão iniciada" começa AQUI, não na criação do pedido. cnpjEmitente espelha a mesma
        // resolução usada por NfeGeracaoService.gerar() para manter os dois pontos consistentes.
        String cnpjParaReserva = resolverCnpjParaReserva(empresa);
        ReservaFiscalResultado reserva;
        try {
            reserva = reservaFiscalService.reservar(pedidoId, cnpjParaReserva);
        } catch (Exception e) {
            pedidoService.atualizarStatus(pedidoId, "ERRO", pedido.getChaveNfe());
            if (controlaEstoque) {
                desfazerReservaSeguro(pedido.getItens(), empresaId, pedidoId, criadoPor);
            }
            throw e;
        }

        NfeEmissaoRequest req = montarRequest(pedido, reserva);

        log.info("[PedidoEmissao] Transmitindo | pedidoId={} | dest={} | empresaId={} | itens={}",
                pedidoId, pedido.getDestCnpjCpf(), empresaId, req.getItens().size());

        NfeGeracaoResult result;
        try {
            // Fluxo OMS de marketplaces: transporte contratado/operado pela plataforma, nunca
            // pelo emitente nem pelo destinatário — modFrete=2 (Terceiros).
            result = nfeGeracaoService.gerar(req, empresa, ModalidadeFrete.CONTA_TERCEIROS);
        } catch (Exception e) {
            // Preserva a chaveNfe já persistida (ex.: retry de um pedido REJEITADO que já
            // tinha uma chave real conhecida pela SEFAZ) — nunca zera com um UPDATE incondicional.
            pedidoService.atualizarStatus(pedidoId, "ERRO", pedido.getChaveNfe());
            if (controlaEstoque) {
                desfazerReservaSeguro(pedido.getItens(), empresaId, pedidoId, criadoPor);
            }
            throw traduzirFalhaTransmissao(e);
        }

        NfeSefazRetorno retorno = parseRetornoSeguro(result.getSoapRetorno());
        String novoStatus = resolverStatus(retorno);
        pedidoService.atualizarStatus(pedidoId, novoStatus, result.getChaveNfe());

        if (controlaEstoque) {
            if ("AUTORIZADO".equals(novoStatus)) {
                estoqueService.baixaDefinitivaItens(pedido.getItens(), empresaId, pedidoId, criadoPor);
            } else if ("REJEITADO".equals(novoStatus)) {
                desfazerReservaSeguro(pedido.getItens(), empresaId, pedidoId, criadoPor);
            }
        }
        // AGUARDANDO: reserva mantida (Opção A — liberar manualmente ou no próximo ciclo de consulta)

        log.info("[PedidoEmissao] Concluído | pedidoId={} | status={} | chave={}",
                pedidoId, novoStatus, result.getChaveNfe());

        // A SEFAZ processou a chamada (HTTP 200 internamente), mas rejeitou a NF-e — não é sucesso
        // para quem integra. Expõe cStat/xMotivo estruturados em vez de mascarar como 200 OK.
        if ("REJEITADO".equals(novoStatus) && retorno != null) {
            throw BusinessException.sefazRejected(retorno.getCStat(), retorno.getXMotivo());
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Resolução de status baseada no cStat real da SEFAZ
    // -------------------------------------------------------------------------

    private NfeSefazRetorno parseRetornoSeguro(String soapRetorno) {
        try {
            return retornoParser.parse(soapRetorno);
        } catch (Exception e) {
            log.warn("[PedidoEmissao] Falha ao parsear retorno SEFAZ | erro={}", e.getMessage());
            return null;
        }
    }

    private String resolverStatus(NfeSefazRetorno retorno) {
        if (retorno == null) return "AGUARDANDO";
        if (retorno.isAutorizada()) return "AUTORIZADO";   // cStat=100
        if (retorno.getCStat() >= 200) return "REJEITADO"; // cStat 2xx–9xx
        return "AGUARDANDO"; // cStat=104: lote aceito, aguardando autorização individual
    }

    /**
     * Traduz falhas técnicas de transmissão em códigos padronizados (Requisito 4),
     * distinguindo o que vale a pena retry (timeout/indisponibilidade) do que não
     * (schema inválido — precisa corrigir o dado antes de tentar de novo).
     */
    private Exception traduzirFalhaTransmissao(Exception e) {
        if (e instanceof br.com.borurio.fiscal.exception.XmlSchemaValidationException) {
            return BusinessException.xmlSchemaInvalid(e.getMessage());
        }
        Throwable causa = e;
        while (causa != null) {
            if (causa instanceof java.net.SocketTimeoutException) {
                return BusinessException.sefazTimeout();
            }
            if (causa instanceof java.net.ConnectException || causa instanceof java.net.UnknownHostException) {
                return BusinessException.sefazUnavailable();
            }
            causa = causa.getCause();
        }
        return e;
    }

    // -------------------------------------------------------------------------
    // Desfaz reserva sem mascarar o resultado SEFAZ
    // -------------------------------------------------------------------------

    private void desfazerReservaSeguro(List<PedidoItem> itens, Long empresaId,
                                        Long pedidoId, String criadoPor) {
        try {
            estoqueService.desfazerReservaItens(itens, empresaId, pedidoId, criadoPor);
        } catch (Exception e) {
            log.error("[PedidoEmissao] Falha ao desfazer reserva | pedidoId={} | erro={}",
                    pedidoId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Montagem do NfeEmissaoRequest a partir do snapshot fiscal do pedido
    // -------------------------------------------------------------------------

    /**
     * Resolve a empresa emitente para a emissão do pedido.
     * Fluxo OMS multi-CNPJ: usa cnpjEmitente do pedido para selecionar a empresa correta.
     * Fluxo de usuário: usa empresaId do contexto de segurança.
     */
    private Empresa resolverEmpresaParaEmissao(Pedido pedido) {
        String jtiOms = EmpresaContextHolder.getJtiAuth();
        if (jtiOms != null && pedido.getCnpjEmitente() != null) {
            Empresa e = empresaMapper.buscarPorCnpj(pedido.getCnpjEmitente());
            if (e == null) {
                throw BusinessException.cnpjNotAuthorizedForOmsClient(pedido.getCnpjEmitente());
            }
            return e;
        }
        Long empresaId = EmpresaContextHolder.get() != null
                ? EmpresaContextHolder.get() : pedido.getEmpresaId();
        return resolverEmpresa(empresaId);
    }

    /**
     * Valida que o CFOP de cada item é compatível com o tipo de operação (idDest) calculado
     * a partir da UF do emitente e da UF do destinatário — mesma fórmula usada na montagem do
     * XML ({@link NfeGeracaoService#resolverIdDest}), reaproveitada aqui para nunca divergir.
     *
     * A OMS é responsável por informar o CFOP; esta validação só confere a coerência — nunca
     * corrige ou substitui o valor recebido. idDest="3" (destinatário no exterior) não é
     * alcançável hoje: o pedido não tem campo de país do destinatário, só UF brasileira.
     */
    private void validarCfopDestino(Pedido pedido, Empresa empresa) {
        String ufEmitente = empresa != null ? empresa.getUf() : null;
        String idDest = nfeGeracaoService.resolverIdDest(pedido.getDestUf(), ufEmitente);

        String prefixoEsperado = switch (idDest) {
            case "1" -> "5";
            case "2" -> "6";
            default -> throw new IllegalStateException("idDest inesperado: " + idDest);
        };

        for (PedidoItem item : pedido.getItens()) {
            String cfop = item.getCfop();
            if (cfop == null || cfop.isBlank() || !cfop.startsWith(prefixoEsperado)) {
                throw BusinessException.cfopDestinationMismatch(cfop, idDest, prefixoEsperado);
            }
        }
    }

    private Empresa resolverEmpresa(Long empresaId) {
        if (empresaId == null) return null;
        try {
            return empresaMapper.buscarPorId(empresaId);
        } catch (Exception e) {
            log.warn("[PedidoEmissao] Falha ao resolver empresa | empresaId={} | erro={}", empresaId, e.getMessage());
            return null;
        }
    }

    /**
     * Empresa não encontrada ou flag nula → controla estoque (comportamento atual/seguro).
     * Só desativa quando a empresa âncora existe e tem controleEstoqueAtivo explicitamente false.
     */
    private boolean controlaEstoque(Long empresaId) {
        Empresa e = resolverEmpresa(empresaId);
        return e == null || e.controlaEstoque();
    }

    private String resolverCriadoPor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "sistema";
    }

    /**
     * Mesma lógica de resolução de cnpjEmitente usada em NfeGeracaoService.gerar() — precisa
     * ficar consistente porque a reserva (aqui) e o cnpj usado para montar a chave43/XML (lá)
     * têm que ser exatamente o mesmo CNPJ, senão a reserva trava a sequência errada.
     */
    private String resolverCnpjParaReserva(Empresa empresa) {
        return empresa != null && empresa.getCnpj() != null
                ? empresa.getCnpj().replaceAll("\\D", "")
                : emitente.getCnpj().replaceAll("\\D", "");
    }

    private NfeEmissaoRequest montarRequest(Pedido pedido, ReservaFiscalResultado reserva) {
        NfeEmissaoRequest req = new NfeEmissaoRequest();
        req.setSerie(reserva.serie());
        req.setNumero(String.valueOf(reserva.numero()));
        req.setNaturezaOperacao(pedido.getNaturezaOperacao());
        req.setDestCnpjCpf(pedido.getDestCnpjCpf());
        req.setDestRazaoSocial(pedido.getDestRazaoSocial());
        req.setDestUf(pedido.getDestUf());
        req.setDestLogradouro(pedido.getDestLogradouro());
        req.setDestNumero(pedido.getDestNumero());
        req.setDestBairro(pedido.getDestBairro());
        req.setDestCodigoMunicipio(pedido.getDestCodigoMunicipio());
        req.setDestMunicipio(pedido.getDestMunicipio());
        req.setDestCep(pedido.getDestCep());

        List<NfeEmissaoItem> itensNfe = new ArrayList<>();
        for (PedidoItem item : pedido.getItens()) {
            itensNfe.add(montarItem(item));
        }
        req.setItens(itensNfe);
        return req;
    }

    private NfeEmissaoItem montarItem(PedidoItem item) {
        NfeEmissaoItem nfeItem = new NfeEmissaoItem();
        nfeItem.setCodigoProduto(item.getCodigoProduto());
        nfeItem.setDescricao(item.getDescricao());
        nfeItem.setNcm(item.getNcm());
        nfeItem.setCfop(item.getCfop());
        nfeItem.setUnidade(item.getUnidade());
        nfeItem.setQuantidade(item.getQuantidade());
        nfeItem.setValorUnitario(item.getValorUnitario());
        nfeItem.setOrigem(item.getOrigem() != null ? String.valueOf(item.getOrigem()) : "0");
        nfeItem.setCsosn(item.getCsosn() != null ? item.getCsosn() : "400");
        return nfeItem;
    }
}
