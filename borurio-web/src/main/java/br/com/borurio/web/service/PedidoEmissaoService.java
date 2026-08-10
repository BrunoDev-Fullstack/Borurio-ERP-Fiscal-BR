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
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.exception.XmlSchemaValidationException;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import br.com.borurio.web.dto.AberturaCicloResultado;
import br.com.borurio.web.dto.TipoAberturaCiclo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge: Pedido + snapshot fiscal → NfeEmissaoRequest → motor fiscal.
 *
 * Ciclo do nNF (Gate 1 — NfeEmissaoService, 07-08-2026): o número fiscal fica "em voo" em
 * nfe_emissao até ter destino definitivo. Após transmissão, o estado do CICLO (não o status do
 * Pedido, que mantém o vocabulário de sempre) é:
 *   cStat=100                → AUTORIZADO            + baixa definitiva de estoque
 *   cStat≥200                → AGUARDANDO_CORRECAO   + desfaz reserva de estoque (Pedido=REJEITADO)
 *   demais casos (110, 104   → PENDENTE_CONFIRMACAO  + estoque intocado (Pedido=AGUARDANDO) —
 *   sem infProt, retorno nulo)                          classificação fina (denegação vs.
 *                                                        processamento) é Gate 2, ainda não
 *                                                        implementada; por ora tudo que não é
 *                                                        100/≥200 cai neste balde conservador.
 *   falha local pré-rede (XSD/assinatura) → reverte para RESERVADO, estoque intocado
 *   falha de rede (timeout/indisponível)  → PENDENTE_CONFIRMACAO, estoque intocado
 *
 * AGUARDANDO_CORRECAO e PENDENTE_CONFIRMACAO NÃO liberam o gate da série (nfe_sequencia
 * .emissao_ativa_id) — o número continua pertencendo a este pedido até ser autorizado ou
 * denegado. Reconciliação ativa de PENDENTE_CONFIRMACAO/TRANSMITIDO via consulta à SEFAZ é
 * Gate 3, ainda não implementada: por ora, uma nova chamada de emitir() nesse estado só recebe
 * EMISSAO_AGUARDANDO_RECONCILIACAO (bloqueado, sem retransmitir e sem gerar chave nova).
 *
 * Reemissão: pedidos em REJEITADO ou ERRO podem chamar emitir() novamente. Diferente de antes,
 * nem toda reemissão gera nNF novo — retry de AGUARDANDO_CORRECAO reaproveita o mesmo número
 * (NfeEmissaoService.abrirCiclo decide isso, não mais um sequenciador simples).
 *
 * Concorrência (P0.1 + Gate 1): antes de tocar no ciclo do nNF, emitir() reivindica o pedido com
 * um claim atômico (RASCUNHO/REJEITADO/ERRO → EMITINDO via UPDATE condicional). Só a chamada que
 * vence o claim prossegue. Pedido já em EMITINDO (retomada após interrupção do processo, não uma
 * corrida nova) é tratado num caminho separado: busca o ciclo de nNF mais recente e retoma dali —
 * se não existir ciclo nenhum, a interrupção ocorreu antes até da reserva de estoque, e não há
 * como retomar com segurança (PEDIDO_EMISSAO_INCONSISTENTE, exige verificação manual).
 *
 * emitir() lança BusinessException (não retorna 200 disfarçado de sucesso) quando:
 *   claim perdido            → EMISSAO_EM_ANDAMENTO (retryable=true)
 *   outro pedido dono do gate → EMISSAO_EM_ANDAMENTO_NA_SERIE (retryable=true)
 *   ciclo aguardando reconciliação → EMISSAO_AGUARDANDO_RECONCILIACAO (retryable=true)
 *   pedido EMITINDO sem ciclo → PEDIDO_EMISSAO_INCONSISTENTE (retryable=false)
 *   AGUARDANDO_CORRECAO       → SEFAZ_REJECTED (cStat/xMotivo em `data`, retryable=false)
 *   timeout de rede           → SEFAZ_TIMEOUT (retryable=true)
 *   SEFAZ inacessível         → SEFAZ_UNAVAILABLE (retryable=true)
 *   schema inválido           → XML_SCHEMA_INVALID (retryable=false)
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
    private final NfeEmissaoService nfeEmissaoService;
    private final EmitenteProperties emitente;

    public PedidoEmissaoService(PedidoService pedidoService,
                                NfeGeracaoService nfeGeracaoService,
                                NfeSefazRetornoParser retornoParser,
                                EstoqueService estoqueService,
                                EmpresaMapper empresaMapper,
                                NfeEmissaoService nfeEmissaoService,
                                EmitenteProperties emitente) {
        this.pedidoService     = pedidoService;
        this.nfeGeracaoService = nfeGeracaoService;
        this.retornoParser     = retornoParser;
        this.estoqueService    = estoqueService;
        this.empresaMapper     = empresaMapper;
        this.nfeEmissaoService = nfeEmissaoService;
        this.emitente           = emitente;
    }

    public NfeGeracaoResult emitir(Long pedidoId) throws Exception {
        // P0-2 (07-08-2026, hardening pós-banca): fronteira de isolamento multiempresa — falha
        // (404/NoSuchElementException) ANTES de qualquer efeito (claim, gate, estoque, SEFAZ)
        // se o pedido não pertencer ao tenant autenticado. Nunca confia em pedidoId sozinho.
        Pedido pedido = pedidoService.buscarComItensDoTenanteAtual(pedidoId);

        boolean retomadaEmitindo = "EMITINDO".equals(pedido.getStatus());

        if (!retomadaEmitindo && !STATUS_EMISSIVEIS.contains(pedido.getStatus())) {
            throw BusinessException.invalidOrderStatus(
                    "Pedido não pode ser emitido no status atual: " + pedido.getStatus()
                            + ". Permitido apenas para RASCUNHO, REJEITADO ou ERRO.");
        }

        if (retomadaEmitindo) {
            // Pedido já reivindicado (EMITINDO) numa chamada anterior interrompida antes de
            // concluir — não repete o claim atômico, só decide o que fazer com base no ciclo de
            // nNF mais recente.
            NfeEmissao ultimaEmissao = nfeEmissaoService.buscarUltimaEmissaoDoPedido(pedidoId);
            if (ultimaEmissao == null) {
                // Sem nenhum ciclo registrado: a interrupção ocorreu antes até da reserva de
                // estoque/número. Não há como saber com segurança o que já aconteceu, não tenta
                // adivinhar.
                throw BusinessException.pedidoEmissaoInconsistente(pedidoId);
            }
            if (NfeEmissao.Estados.isTerminal(ultimaEmissao.getEstado())) {
                // O ciclo já chegou a um resultado definitivo (o gate já foi liberado em
                // resolverCiclo) — a interrupção aconteceu na janela estreita entre essa
                // transação e a atualização de Pedido.status, não antes dela. Corrige o status e
                // para: NUNCA reabrir ciclo aqui, ou o gate livre seria lido como "abertura nova"
                // e alocaria um número seguinte para um pedido que já está resolvido.
                String statusResolvido = mapearStatusPedido(ultimaEmissao.getEstado());
                pedidoService.atualizarStatus(pedidoId, statusResolvido, pedido.getChaveNfe());
                throw BusinessException.pedidoJaResolvido(pedidoId, statusResolvido);
            }
        } else if (!pedidoService.reivindicarParaEmissao(pedidoId)) {
            // Claim atômico (P0.1) — o SELECT acima já confirmou status emissível, mas não
            // protege a janela entre leitura e escrita. Esta UPDATE condicional
            // (RASCUNHO/REJEITADO/ERRO → EMITINDO) é o ponto real de exclusão mútua: duas
            // chamadas concorrentes pro mesmo pedido (retry de rede, corrida real) só deixam UMA
            // prosseguir. A outra recebe EMISSAO_EM_ANDAMENTO em vez de alocar um segundo nNF.
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
        } catch (Exception e) {
            // Falha antes de qualquer chamada à SEFAZ ou ao ciclo do nNF (itens vazios,
            // empresa/certificado não resolvido) — nenhum nNF foi tocado ainda, mas o pedido já
            // está em EMITINDO por causa do claim/retomada acima e precisa voltar a um status
            // emissível, senão fica preso pra sempre.
            pedidoService.atualizarStatus(pedidoId, "ERRO", pedido.getChaveNfe());
            throw e;
        }

        // Gate 1 do ciclo do nNF — abre (ou retoma) o ciclo ANTES de decidir se reserva estoque:
        // a matriz de efeitos de estoque depende do tipo de transição (ver javadoc da classe).
        // cnpjEmitente espelha a mesma resolução usada por NfeGeracaoService.gerar() para manter
        // os dois pontos consistentes.
        String cnpjParaReserva = resolverCnpjParaReserva(empresa);
        AberturaCicloResultado abertura;
        try {
            abertura = nfeEmissaoService.abrirCiclo(pedidoId, cnpjParaReserva);
        } catch (Exception e) {
            pedidoService.atualizarStatus(pedidoId, "ERRO", pedido.getChaveNfe());
            throw e;
        }
        NfeEmissao emissao = abertura.emissao();

        // Matriz de efeitos de estoque por tipo de transição: NOVA_ABERTURA e retomada de
        // AGUARDANDO_CORRECAO reservam de novo (a reserva anterior, se houve, já foi desfeita ao
        // entrar em AGUARDANDO_CORRECAO); RETOMADA_RESERVADO nunca reserva de novo — a reserva da
        // tentativa original continua de pé, e a operação de reserva não é idempotente (chamar de
        // novo duplicaria o efeito).
        if (controlaEstoque && abertura.tipo() != TipoAberturaCiclo.RETOMADA_RESERVADO) {
            try {
                estoqueService.reservarItens(pedido.getItens(), empresaId, pedidoId, criadoPor);
            } catch (Exception e) {
                pedidoService.atualizarStatus(pedidoId, "ERRO", pedido.getChaveNfe());
                throw e;
            }
        }

        NfeEmissaoRequest req = montarRequest(pedido, emissao);

        log.info("[PedidoEmissao] Transmitindo | pedidoId={} | emissaoId={} | nNF={} | dest={} | empresaId={} | itens={}",
                pedidoId, emissao.getId(), emissao.getNumeroNfe(), pedido.getDestCnpjCpf(), empresaId, req.getItens().size());

        NfeGeracaoResult result;
        try {
            // Fluxo OMS de marketplaces: transporte contratado/operado pela plataforma, nunca
            // pelo emitente nem pelo destinatário — modFrete=2 (Terceiros).
            result = nfeGeracaoService.gerar(req, empresa, ModalidadeFrete.CONTA_TERCEIROS, emissao.getId());
        } catch (Exception e) {
            // Preserva a chaveNfe já persistida (ex.: retry de um pedido REJEITADO que já
            // tinha uma chave real conhecida pela SEFAZ) — nunca zera com um UPDATE incondicional.
            pedidoService.atualizarStatus(pedidoId, "ERRO", pedido.getChaveNfe());
            // Estoque NUNCA é desfeito aqui (diferente de antes do Gate 1): o número continua
            // pertencendo a este pedido em ambos os sub-casos abaixo, e a matriz de estoque
            // (RESERVADO/PENDENTE_CONFIRMACAO) diz para manter a reserva intocada até um destino
            // definitivo.
            if (falhaOcorreuAntesDaTransmissao(e)) {
                // XSD/assinatura: certeza local e síncrona de que nada foi enviado à SEFAZ —
                // seguro reverter a chave "congelada" e deixar o ciclo pronto pra retomar em
                // RESERVADO na próxima chamada, sem esperar reconciliação (Gate 3).
                nfeEmissaoService.reverterParaReservadoPorFalhaLocal(emissao.getId());
            } else {
                // Timeout/indisponibilidade/qualquer exceção não classificada: não há certeza de
                // que a SEFAZ não recebeu a transmissão — fail-safe pra PENDENTE_CONFIRMACAO, que
                // mantém o gate ocupado e bloqueia nova tentativa até reconciliar (Gate 3).
                nfeEmissaoService.resolverCiclo(emissao.getId(), NfeEmissao.Estados.PENDENTE_CONFIRMACAO,
                        null, null, null);
            }
            throw traduzirFalhaTransmissao(e);
        }

        NfeSefazRetorno retorno = parseRetornoSeguro(result.getSoapRetorno());
        String novoEstadoEmissao = resolverEstadoEmissao(retorno);
        nfeEmissaoService.resolverCiclo(emissao.getId(), novoEstadoEmissao,
                retorno != null ? retorno.getCStat() : null,
                retorno != null ? retorno.getXMotivo() : null,
                retorno != null ? retorno.getNProt() : null);

        String novoStatus = mapearStatusPedido(novoEstadoEmissao);
        pedidoService.atualizarStatus(pedidoId, novoStatus, result.getChaveNfe());

        if (controlaEstoque) {
            if (NfeEmissao.Estados.AUTORIZADO.equals(novoEstadoEmissao)) {
                estoqueService.baixaDefinitivaItens(pedido.getItens(), empresaId, pedidoId, criadoPor);
            } else if (NfeEmissao.Estados.AGUARDANDO_CORRECAO.equals(novoEstadoEmissao)) {
                desfazerReservaSeguro(pedido.getItens(), empresaId, pedidoId, criadoPor);
            }
            // PENDENTE_CONFIRMACAO: estoque intocado, de propósito — ver javadoc da classe.
        }

        log.info("[PedidoEmissao] Concluído | pedidoId={} | status={} | estadoEmissao={} | chave={}",
                pedidoId, novoStatus, novoEstadoEmissao, result.getChaveNfe());

        // A SEFAZ processou a chamada (HTTP 200 internamente), mas rejeitou a NF-e — não é sucesso
        // para quem integra. Expõe cStat/xMotivo estruturados em vez de mascarar como 200 OK.
        if (NfeEmissao.Estados.AGUARDANDO_CORRECAO.equals(novoEstadoEmissao) && retorno != null) {
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

    /**
     * Classificação MÍNIMA do retorno SEFAZ para o ciclo de nfe_emissao — mesma lógica binária
     * que existia antes (só reconhece cStat=100 e cStat>=200), agora mapeada pros nomes de estado
     * do Gate 1. A classificação semântica fina (distinguir denegação real, ex. cStat=110, de
     * "ainda em processamento", ex. cStat=104 sem infProt) é Gate 2 — ainda não implementada, por
     * isso ambos caem hoje em PENDENTE_CONFIRMACAO, o balde conservador que nunca libera o gate
     * nem toca estoque.
     */
    private String resolverEstadoEmissao(NfeSefazRetorno retorno) {
        if (retorno == null) return NfeEmissao.Estados.PENDENTE_CONFIRMACAO;
        if (retorno.isAutorizada()) return NfeEmissao.Estados.AUTORIZADO;      // cStat=100
        if (retorno.getCStat() >= 200) return NfeEmissao.Estados.AGUARDANDO_CORRECAO; // cStat 2xx–9xx
        return NfeEmissao.Estados.PENDENTE_CONFIRMACAO; // cStat=104/110/etc — ver nota acima
    }

    /**
     * Traduz o estado do ciclo do nNF (vocabulário novo, interno) para o status do Pedido
     * (vocabulário OMS, inalterado neste Gate — formalizar PENDENTE_CONFIRMACAO no contrato é
     * Gate 5). AGUARDANDO_CORRECAO mantém o nome "REJEITADO" que a OMS já conhece; qualquer coisa
     * que não seja AUTORIZADO/AGUARDANDO_CORRECAO (hoje só PENDENTE_CONFIRMACAO) vira "AGUARDANDO",
     * igual ao comportamento anterior ao Gate 1.
     */
    private String mapearStatusPedido(String estadoEmissao) {
        if (NfeEmissao.Estados.AUTORIZADO.equals(estadoEmissao)) return "AUTORIZADO";
        if (NfeEmissao.Estados.AGUARDANDO_CORRECAO.equals(estadoEmissao)) return "REJEITADO";
        return "AGUARDANDO";
    }

    /**
     * Falha ocorrida ANTES de qualquer I/O de rede com a SEFAZ. Determinada pela FASE real de
     * execução, não por uma lista de tipos de exceção reconhecidos (P1 corrigido em 10-08-2026 —
     * a lista anterior só reconhecia XmlSchemaValidationException, deixando assinatura digital e
     * colisão de chave local classificadas incorretamente como "resultado incerto").
     *
     * NfeOrquestradorService.processar() envolve SOMENTE a chamada real de transmissão
     * (NfeTransmitService.transmitirXml) e relança qualquer falha dali como
     * SefazTransmissaoIncertaException — é o único ponto de todo o pipeline (conversão XML,
     * validação XSD, assinatura digital, marcarTransmitido) capaz de produzir esse tipo. Local é
     * portanto o comportamento padrão: só deixa de ser local se essa marca (ou algo que a
     * envolva na cadeia de causas) estiver comprovadamente presente.
     */
    private boolean falhaOcorreuAntesDaTransmissao(Exception e) {
        return !contemTransmissaoIncerta(e);
    }

    private boolean contemTransmissaoIncerta(Throwable e) {
        Throwable causa = e;
        while (causa != null) {
            if (causa instanceof SefazTransmissaoIncertaException) {
                return true;
            }
            causa = causa.getCause();
        }
        return false;
    }

    /**
     * Traduz falhas técnicas de transmissão em códigos padronizados (Requisito 4), distinguindo
     * o que vale a pena retry (timeout/indisponibilidade — rede comprovadamente tocada, resultado
     * incerto) do que não (schema inválido, ou qualquer outra falha local comprovada — precisa
     * corrigir o dado/config antes de tentar de novo, nunca reconciliação SEFAZ).
     */
    private Exception traduzirFalhaTransmissao(Exception e) {
        if (e instanceof XmlSchemaValidationException) {
            return BusinessException.xmlSchemaInvalid(e.getMessage());
        }
        if (contemTransmissaoIncerta(e)) {
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
            // Rede comprovadamente tocada (SefazTransmissaoIncertaException presente), mas a
            // causa raiz não é um dos tipos de transporte reconhecidos — mesmo fail-safe
            // conservador de antes: trata como indisponibilidade, retryable.
            return BusinessException.sefazUnavailable();
        }
        // Falha local comprovada pela fase (nunca tocou a rede) — nunca era traduzida antes
        // desta correção, vazava como exceção crua (500 genérico sem errorCode/retryable).
        return BusinessException.localProcessingFailure(e.getMessage());
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

    private NfeEmissaoRequest montarRequest(Pedido pedido, NfeEmissao emissao) {
        NfeEmissaoRequest req = new NfeEmissaoRequest();
        req.setSerie(emissao.getSerie());
        req.setNumero(String.valueOf(emissao.getNumeroNfe()));
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
