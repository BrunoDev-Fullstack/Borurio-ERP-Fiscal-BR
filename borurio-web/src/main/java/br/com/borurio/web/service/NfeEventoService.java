package br.com.borurio.web.service;

import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.mapper.NfeEventoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Camada transacional pura do gate de cancelamento (evento 110111, 12-08-2026) -- mesmo papel de
 * NfeEmissaoService para o ciclo do nNF: SO fala com nfe_evento/nfe_emissao/pedido/estoque,
 * NUNCA faz chamada de rede. Quem orquestra rede + decisao e o
 * {@link NfeCancelamentoOrquestradorService} (bean separado -- necessario para os metodos
 * @Transactional aqui serem de fato interceptados pelo proxy AOP; auto-invocacao dentro da mesma
 * classe nao abriria transacao real).
 *
 * Identidade fiscal do cancelamento e SEMPRE chaveNfe+110111+nSeqEvento=1 -- nunca incrementada.
 * NfeEmissao.estado=CANCELADO e projecao: nunca sobrescreve cstat/xmotivo/nprot da autorizacao
 * original. nfe_documento nunca e tocado por este servico.
 */
@Service
public class NfeEventoService {

    private static final Logger log = LoggerFactory.getLogger(NfeEventoService.class);

    private final NfeEventoMapper nfeEventoMapper;
    private final NfeEmissaoMapper nfeEmissaoMapper;
    private final PedidoMapper pedidoMapper;
    private final EstoqueService estoqueService;
    private final SefazReconciliacaoProperties reconciliacaoProperties;

    public NfeEventoService(NfeEventoMapper nfeEventoMapper, NfeEmissaoMapper nfeEmissaoMapper,
                             PedidoMapper pedidoMapper, EstoqueService estoqueService,
                             SefazReconciliacaoProperties reconciliacaoProperties) {
        this.nfeEventoMapper = nfeEventoMapper;
        this.nfeEmissaoMapper = nfeEmissaoMapper;
        this.pedidoMapper = pedidoMapper;
        this.estoqueService = estoqueService;
        this.reconciliacaoProperties = reconciliacaoProperties;
    }

    /** Decisao de classificacao ja tomada pelo orquestrador -- esta camada so persiste/aplica efeitos. */
    public record Decisao(String estado, Integer cStat, String xMotivo, String nProt, boolean foraDoPrazo) {}

    /**
     * jaResolvido=true -> evento REGISTRADO anteriormente, retorno idempotente sem nova transmissao.
     * Caso contrario, o chamador olha evento.getEstado(): PREPARADO -> segue para transmissao;
     * TRANSMITIDO/PENDENTE_CONFIRMACAO -> nunca retransmite, delega para reconciliacao (que tem
     * seu proprio claim de backoff, protegendo contra concorrencia sem lancar erro aqui).
     */
    public record Claim(NfeEvento evento, boolean jaResolvido) {}

    // -------------------------------------------------------------------------
    // Claim (TX curta)
    // -------------------------------------------------------------------------

    @Transactional
    public Claim reivindicar(Long pedidoId, Long emissaoId, Long empresaId, String cnpjEmitente,
                              String chaveNfe, String justificativa) {
        NfeEvento existente = nfeEventoMapper.buscarUltimaTentativa(chaveNfe, NfeEvento.TiposEvento.CANCELAMENTO);
        if (existente == null) {
            return inserirNovo(pedidoId, emissaoId, empresaId, cnpjEmitente, chaveNfe, justificativa);
        }
        return decidirComExistente(existente, justificativa);
    }

    private Claim inserirNovo(Long pedidoId, Long emissaoId, Long empresaId, String cnpjEmitente,
                               String chaveNfe, String justificativa) {
        NfeEvento nova = new NfeEvento();
        nova.setPedidoId(pedidoId);
        nova.setEmissaoId(emissaoId);
        nova.setEmpresaId(empresaId);
        nova.setCnpjEmitente(cnpjEmitente);
        nova.setChaveNfe(chaveNfe);
        nova.setTipoEvento(NfeEvento.TiposEvento.CANCELAMENTO);
        nova.setNSeqEvento(1);
        nova.setIdEvento("ID" + NfeEvento.TiposEvento.CANCELAMENTO + chaveNfe + "01");
        nova.setEstado(NfeEvento.Estados.PREPARADO);
        nova.setJustificativa(justificativa);
        try {
            nfeEventoMapper.inserirPreparado(nova);
            return new Claim(nova, false);
        } catch (DuplicateKeyException e) {
            // Corrida estrutural: outra chamada inseriu a MESMA identidade fiscal entre a busca
            // acima e este insert -- a UNIQUE KEY e quem decide, nunca duas linhas para o mesmo
            // chaveNfe+tipoEvento+nSeqEvento.
            NfeEvento concorrente = nfeEventoMapper.buscarUltimaTentativa(chaveNfe, NfeEvento.TiposEvento.CANCELAMENTO);
            return decidirComExistente(concorrente, justificativa);
        }
    }

    private Claim decidirComExistente(NfeEvento existente, String justificativa) {
        return switch (existente.getEstado()) {
            case NfeEvento.Estados.REGISTRADO -> new Claim(existente, true);
            case NfeEvento.Estados.REJEITADO -> {
                int reaberta = nfeEventoMapper.retomarAposRejeicao(existente.getId(), justificativa);
                if (reaberta == 0) {
                    // Outra chamada reabriu/avancou a linha entre a leitura e este UPDATE --
                    // reconsulta o estado real em vez de assumir, mesmo tratamento da corrida de
                    // insert (nunca decide com dado potencialmente obsoleto).
                    yield decidirComExistente(nfeEventoMapper.buscarPorId(existente.getId()), justificativa);
                }
                // Reflete em memoria exatamente o que a UPDATE acabou de aplicar -- nunca deixa o
                // objeto retornado sugerir dados da tentativa rejeitada anterior.
                existente.setEstado(NfeEvento.Estados.PREPARADO);
                existente.setJustificativa(justificativa);
                existente.setCstat(null);
                existente.setXmotivo(null);
                existente.setNprot(null);
                existente.setForaDoPrazo(false);
                existente.setResolucaoOrigem(null);
                existente.setResolvidoEm(null);
                existente.setDhEvento(null);
                existente.setPayloadHash(null);
                existente.setTransmitidoEm(null);
                existente.setUltimaConsultaEm(null);
                existente.setTentativasConsulta(0);
                yield new Claim(existente, false);
            }
            default ->
                // PREPARADO (retomada segura, nenhum SOAP saiu ainda) ou TRANSMITIDO/
                // PENDENTE_CONFIRMACAO (evento em voo/incerto -- o chamador nunca retransmite,
                // decide entre reconciliar ou bloquear a partir do estado real aqui devolvido).
                    new Claim(existente, false);
        };
    }

    // -------------------------------------------------------------------------
    // Transmissao (claim curto -- so a UPDATE, sem rede)
    // -------------------------------------------------------------------------

    @Transactional
    public boolean marcarTransmitido(Long eventoId, String dhEvento, String payloadHash) {
        return nfeEventoMapper.marcarTransmitido(eventoId, dhEvento, payloadHash) == 1;
    }

    // -------------------------------------------------------------------------
    // Reconciliacao -- claim de janela de backoff (mesmo desenho de Gate 3)
    // -------------------------------------------------------------------------

    @Transactional
    public boolean tentarAdquirirJanelaConsulta(Long eventoId) {
        int affected = nfeEventoMapper.tentarAdquirirJanelaConsulta(
                eventoId,
                LocalDateTime.now(),
                reconciliacaoProperties.getBackoffInicialSegundos(),
                reconciliacaoProperties.getBackoffMultiplicador(),
                reconciliacaoProperties.getBackoffMaximoSegundos());
        return affected == 1;
    }

    public NfeEvento buscarUltimaTentativa(String chaveNfe) {
        return nfeEventoMapper.buscarUltimaTentativa(chaveNfe, NfeEvento.TiposEvento.CANCELAMENTO);
    }

    public NfeEvento buscarAtual(Long eventoId) {
        return nfeEventoMapper.buscarPorId(eventoId);
    }

    // -------------------------------------------------------------------------
    // Finalizacao exactly-once (TX curta) -- evento + NfeEmissao(CANCELADO) + Pedido + estoque
    // -------------------------------------------------------------------------

    /** Retorna false se o evento ja estava terminal (no-op idempotente) -- chamador reconsulta o estado real. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public boolean finalizar(Long eventoId, Decisao decisao, String resolucaoOrigem, Long emissaoId,
                              Long pedidoId, boolean controlaEstoque, List<PedidoItem> itens,
                              Long empresaId, String criadoPor) {
        NfeEvento travado = nfeEventoMapper.buscarPorIdParaAtualizar(eventoId);
        if (travado == null) {
            throw new IllegalStateException("nfe_evento id=" + eventoId + " nao encontrado ao finalizar.");
        }
        if (NfeEvento.Estados.isTerminal(travado.getEstado())) {
            return false;
        }

        boolean terminal = NfeEvento.Estados.isTerminal(decisao.estado());
        travado.setEstado(decisao.estado());
        travado.setCstat(decisao.cStat());
        travado.setXmotivo(decisao.xMotivo());
        travado.setNprot(decisao.nProt());
        travado.setForaDoPrazo(decisao.foraDoPrazo());
        travado.setResolucaoOrigem(terminal ? resolucaoOrigem : null);
        travado.setResolvidoEm(terminal ? LocalDateTime.now() : null);
        nfeEventoMapper.atualizarResultado(travado);

        if (NfeEvento.Estados.REGISTRADO.equals(decisao.estado())) {
            if (emissaoId != null) {
                nfeEmissaoMapper.marcarCancelado(emissaoId);
            }
            pedidoMapper.atualizarStatus(pedidoId, "CANCELADO", travado.getChaveNfe());
            if (controlaEstoque && itens != null && !itens.isEmpty()) {
                estoqueService.estornarBaixaItens(itens, empresaId, pedidoId, criadoPor);
            }
            log.info("[NfeEvento] Cancelamento homologado e aplicado | pedidoId={} | eventoId={} | cStat={}",
                    pedidoId, eventoId, decisao.cStat());
        }
        // REJEITADO/PENDENTE_CONFIRMACAO: nenhum efeito em Pedido/NfeEmissao/estoque.
        return true;
    }
}
