package br.com.borurio.web.service;

import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.dto.NfeCancelamentoRequest;
import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import br.com.borurio.fiscal.dto.NfeEventoPreparado;
import br.com.borurio.fiscal.dto.NfeEventoRetorno;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeCancelamentoService;
import br.com.borurio.fiscal.service.NfeConsultaSituacaoService;
import br.com.borurio.fiscal.service.NfeEventoClassificador;
import br.com.borurio.fiscal.service.NfeEventoRetornoParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orquestra o gate de cancelamento (evento 110111, 12-08-2026): chama a rede (SEFAZ) e decide a
 * classificacao, delegando toda persistencia para {@link NfeEventoService} (bean separado --
 * necessario para os @Transactional daquela classe serem interceptados pelo proxy AOP). Mesmo
 * papel que {@link NfeReconciliacaoService} tem para o Gate 3 de emissao.
 *
 * Nenhuma chamada de rede dentro de transacao de banco -- claim/marcarTransmitido/finalizar sao
 * transacoes curtas e disjuntas no tempo da chamada SOAP real.
 */
@Service
public class NfeCancelamentoOrquestradorService {

    private static final Logger log = LoggerFactory.getLogger(NfeCancelamentoOrquestradorService.class);

    private final NfeEventoService nfeEventoService;
    private final NfeCancelamentoService cancelamentoService;
    private final NfeEventoRetornoParser eventoRetornoParser;
    private final NfeEventoClassificador classificador;
    private final NfeConsultaSituacaoService consultaSituacaoService;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeCancelamentoOrquestradorService(NfeEventoService nfeEventoService,
                                               NfeCancelamentoService cancelamentoService,
                                               NfeEventoRetornoParser eventoRetornoParser,
                                               NfeEventoClassificador classificador,
                                               NfeConsultaSituacaoService consultaSituacaoService) {
        this.nfeEventoService = nfeEventoService;
        this.cancelamentoService = cancelamentoService;
        this.eventoRetornoParser = eventoRetornoParser;
        this.classificador = classificador;
        this.consultaSituacaoService = consultaSituacaoService;
    }

    /**
     * @return mensagem de sucesso quando REGISTRADO (cancelamento efetivo, direto ou idempotente).
     * Lanca BusinessException para CANCELAMENTO_REJEITADO, CANCELAMENTO_AGUARDANDO_RECONCILIACAO
     * ou CANCELAMENTO_EM_ANDAMENTO -- nunca retorna silenciosamente um desfecho que nao seja sucesso.
     */
    public String cancelar(Long pedidoId, Long emissaoId, Long empresaId, String cnpjEmitente, String uf,
                            String chaveNfe, String nProtocolo, String justificativa,
                            CertificadoContexto certContexto, boolean controlaEstoque, List<PedidoItem> itens,
                            String criadoPor) throws Exception {

        NfeEventoService.Claim claim = nfeEventoService.reivindicar(pedidoId, emissaoId, empresaId, cnpjEmitente,
                chaveNfe, justificativa);

        if (claim.jaResolvido()) {
            NfeEvento evento = claim.evento();
            log.info("[NfeCancelamentoOrquestrador] Cancelamento ja confirmado anteriormente -- retorno "
                    + "idempotente | pedidoId={} | eventoId={}", pedidoId, evento.getId());
            return "Cancelamento já confirmado anteriormente (nProt=" + evento.getNprot() + ")";
        }

        NfeEvento evento = claim.evento();

        if (NfeEvento.Estados.TRANSMITIDO.equals(evento.getEstado())
                || NfeEvento.Estados.PENDENTE_CONFIRMACAO.equals(evento.getEstado())) {
            // Evento ja em voo ou com resultado incerto de uma tentativa anterior -- NUNCA
            // retransmite as cegas. Delega para reconciliacao, que tem seu proprio claim de
            // backoff (tentarAdquirirJanelaConsulta): se o backoff nao venceu ou outra chamada
            // concorrente ja esta reconciliando, reconciliar() lanca AGUARDANDO_RECONCILIACAO
            // sem tocar a rede -- nunca duas consultas simultaneas para o mesmo evento.
            log.info("[NfeCancelamentoOrquestrador] Evento existente em {} -- delegando para "
                    + "reconciliacao em vez de transmitir | pedidoId={} | eventoId={}",
                    evento.getEstado(), pedidoId, evento.getId());
            return reconciliar(pedidoId, emissaoId, empresaId, uf, chaveNfe, certContexto,
                    controlaEstoque, itens, criadoPor);
        }

        NfeCancelamentoRequest req = new NfeCancelamentoRequest();
        req.setChaveNfe(chaveNfe);
        req.setNProtocolo(nProtocolo);
        req.setJustificativa(justificativa);

        NfeEventoPreparado preparado = cancelamentoService.prepararEvento(req, cnpjEmitente, uf, certContexto, 1);

        if (!nfeEventoService.marcarTransmitido(evento.getId(), preparado.dhEvento(), preparado.payloadHash())) {
            // Corrida perdida entre o claim e este marcador -- outra chamada concorrente avancou
            // a mesma linha antes desta. Nunca transmite: o evento ja esta em voo por outra chamada.
            throw BusinessException.cancelamentoEmAndamento(pedidoId);
        }

        String respostaSoap;
        try {
            respostaSoap = cancelamentoService.transmitirEvento(preparado, certContexto);
        } catch (SefazTransmissaoIncertaException e) {
            log.warn("[NfeCancelamentoOrquestrador] Falha de transporte na transmissao do cancelamento | "
                    + "pedidoId={} | eventoId={} | erro={}", pedidoId, evento.getId(), e.getMessage());
            // Linha ja ficou TRANSMITIDO -- representa exatamente "enviado, resultado desconhecido".
            // Nenhuma escrita adicional; reconciliacao resolve depois.
            throw BusinessException.cancelamentoAguardandoReconciliacao(pedidoId);
        }

        NfeEventoRetorno retorno = eventoRetornoParser.parse(respostaSoap);
        NfeEventoService.Decisao decisao = decidirClassificacao(retorno);

        return finalizarEDecidir(evento.getId(), decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                pedidoId, emissaoId, empresaId, controlaEstoque, itens, criadoPor);
    }

    /**
     * Reconciliacao de um evento TRANSMITIDO/PENDENTE_CONFIRMACAO -- nunca retransmite. Consulta a
     * Situacao NF-e procurando o procEventoNFe exato (chNFe+110111+nSeqEvento), nunca decide so
     * pelo cStat=101 do documento quando o evento detalhado nao vem junto.
     */
    public String reconciliar(Long pedidoId, Long emissaoId, Long empresaId, String uf, String chaveNfe,
                               CertificadoContexto certContexto, boolean controlaEstoque,
                               List<PedidoItem> itens, String criadoPor) {

        NfeEvento evento = nfeEventoService.buscarUltimaTentativa(chaveNfe);
        if (evento == null) {
            throw new IllegalStateException("Reconciliacao de cancelamento chamada sem nfe_evento para chave=" + chaveNfe);
        }

        if (!nfeEventoService.tentarAdquirirJanelaConsulta(evento.getId())) {
            throw BusinessException.cancelamentoAguardandoReconciliacao(pedidoId);
        }

        NfeConsultaSituacaoRetorno retorno;
        try {
            retorno = consultaSituacaoService.consultarEvento(chaveNfe, uf, tpAmb,
                    certContexto != null ? certContexto.sslContext() : null,
                    NfeEvento.TiposEvento.CANCELAMENTO, "01");
        } catch (SefazTransmissaoIncertaException e) {
            log.warn("[NfeCancelamentoOrquestrador] Falha de transporte na reconciliacao do cancelamento | "
                    + "pedidoId={} | erro={}", pedidoId, e.getMessage());
            throw BusinessException.cancelamentoAguardandoReconciliacao(pedidoId);
        }

        NfeEventoService.Decisao decisao = decidirClassificacaoReconciliacao(retorno);
        return finalizarEDecidir(evento.getId(), decisao, NfeEvento.OrigensResolucao.CONSULTA_SITUACAO,
                pedidoId, emissaoId, empresaId, controlaEstoque, itens, criadoPor);
    }

    // -------------------------------------------------------------------------
    // Classificacao -- combina cStatLote/infEvento/falhaParse ANTES de consultar o classificador,
    // que so recebe cStat individual real (nunca decide sobre ausencia/malformacao).
    // -------------------------------------------------------------------------

    private NfeEventoService.Decisao decidirClassificacao(NfeEventoRetorno retorno) {
        if (retorno.isFalhaParse()) {
            log.warn("[NfeCancelamentoOrquestrador] Resposta do cancelamento ilegivel -- tratado como "
                    + "incerto, nunca rejeicao | detalhe={}", retorno.getDetalheFalhaParse());
            return new NfeEventoService.Decisao(NfeEvento.Estados.PENDENTE_CONFIRMACAO, null, null, null, false);
        }
        if (retorno.isLoteRejeitado()) {
            return new NfeEventoService.Decisao(NfeEvento.Estados.REJEITADO, retorno.getCStatLote(),
                    retorno.getXMotivoLote(), null, false);
        }
        if (retorno.isResultadoIndividualDisponivel()) {
            NfeEventoClassificador.Resultado r = classificador.classificar(retorno.getCStatEvento());
            return new NfeEventoService.Decisao(r.estado(), retorno.getCStatEvento(), retorno.getXMotivoEvento(),
                    retorno.getNProtEvento(), r.foraDoPrazo());
        }
        // cStatLote=128 (lote processado) sem infEvento presente/legivel -- resultado individual
        // desconhecido, NUNCA rejeicao inventada.
        return new NfeEventoService.Decisao(NfeEvento.Estados.PENDENTE_CONFIRMACAO, retorno.getCStatLote(),
                retorno.getXMotivoLote(), null, false);
    }

    private NfeEventoService.Decisao decidirClassificacaoReconciliacao(NfeConsultaSituacaoRetorno retorno) {
        if (retorno.isFalhaParse()) {
            return new NfeEventoService.Decisao(NfeEvento.Estados.PENDENTE_CONFIRMACAO, null, null, null, false);
        }
        if (retorno.isEventoEncontrado() && retorno.getCStatEvento() != null) {
            NfeEventoClassificador.Resultado r = classificador.classificar(retorno.getCStatEvento());
            return new NfeEventoService.Decisao(r.estado(), retorno.getCStatEvento(), retorno.getXMotivoEvento(),
                    retorno.getNProtEvento(), r.foraDoPrazo());
        }
        if (retorno.isCanceladaSemEventoDetalhado()) {
            // cStat=101 e o resultado da SITUACAO da NF-e (retConsSitNFe), nao do EVENTO
            // individual (infEvento/procEventoNFe) -- misturar os dois niveis semanticos exporia
            // cStatEvento=101 em /situacao como se fosse retorno de registro do evento, o que nao
            // e (achado de banca, 12-08-2026). Projeta REGISTRADO (NfeEmissao=CANCELADO,
            // resolucao_origem=CONSULTA_SITUACAO) mas cStat/xMotivo/nProt do EVENTO ficam null --
            // nunca inventa dado de evento que a SEFAZ nao detalhou. A evidencia do 101 em si fica
            // so no log de aplicacao (NfeConsultaSituacaoService ja loga cStat/xMotivo da
            // consulta) -- sem novo campo no contrato publico OMS por enquanto, sem necessidade
            // real do consumidor ainda demonstrada.
            return new NfeEventoService.Decisao(NfeEvento.Estados.REGISTRADO, null, null, null, false);
        }
        return new NfeEventoService.Decisao(NfeEvento.Estados.PENDENTE_CONFIRMACAO, retorno.getCStat(),
                retorno.getXMotivo(), null, false);
    }

    // -------------------------------------------------------------------------
    // Finalizacao + tradução do desfecho em retorno normal ou BusinessException
    // -------------------------------------------------------------------------

    private String finalizarEDecidir(Long eventoId, NfeEventoService.Decisao decisao, String resolucaoOrigem,
                                      Long pedidoId, Long emissaoId, Long empresaId,
                                      boolean controlaEstoque, List<PedidoItem> itens, String criadoPor) {
        boolean aplicado = nfeEventoService.finalizar(eventoId, decisao, resolucaoOrigem, emissaoId, pedidoId,
                controlaEstoque, itens, empresaId, criadoPor);

        if (!aplicado) {
            // Ciclo ja estava terminal (chamada duplicada/concorrente resolveu primeiro) --
            // reconsulta o estado real em vez de decidir com base na Decisao local.
            NfeEvento atual = nfeEventoService.buscarAtual(eventoId);
            return resultadoFinal(atual.getEstado(), pedidoId, atual.getCstat(), atual.getXmotivo(), atual.getNprot());
        }
        return resultadoFinal(decisao.estado(), pedidoId, decisao.cStat(), decisao.xMotivo(), decisao.nProt());
    }

    private String resultadoFinal(String estado, Long pedidoId, Integer cStat, String xMotivo, String nProt) {
        if (NfeEvento.Estados.REGISTRADO.equals(estado)) {
            return "Cancelamento homologado (cStat=" + cStat + ", nProt=" + nProt + ")";
        }
        if (NfeEvento.Estados.REJEITADO.equals(estado)) {
            throw BusinessException.cancelamentoRejeitado(cStat, xMotivo);
        }
        throw BusinessException.cancelamentoAguardandoReconciliacao(pedidoId);
    }
}
