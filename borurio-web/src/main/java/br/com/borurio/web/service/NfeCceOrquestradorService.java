package br.com.borurio.web.service;

import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.dto.ListaEventosRetorno;
import br.com.borurio.fiscal.dto.NfeCceEventoPreparado;
import br.com.borurio.fiscal.dto.NfeCceRequest;
import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import br.com.borurio.fiscal.dto.NfeEventoRetorno;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.entity.NfeEventoIdempotencia;
import br.com.borurio.fiscal.entity.NfeEventoSequencia;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.mapper.NfeEventoIdempotenciaMapper;
import br.com.borurio.fiscal.mapper.NfeEventoMapper;
import br.com.borurio.fiscal.mapper.NfeEventoSequenciaMapper;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeCceClassificador;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeConsultaSituacaoService;
import br.com.borurio.fiscal.service.NfeEventoRetornoParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Gate CC-e (evento 110110, 12-08-2026) -- orquestra rede + classificação + coordenação de
 * nfe_evento_sequencia (identidade fiscal crescente) e nfe_evento_idempotencia (identidade da
 * intenção OMS). Deliberadamente NÃO reutiliza NfeEventoService (cancelamento): a semântica de
 * sequência é fundamentalmente diferente (identidade fixa vs. crescente com retry-vs-nova-tentativa
 * a distinguir por Idempotency-Key).
 *
 * Usa {@link TransactionTemplate} explícito (mesmo padrão de OmsAuthorizationAdminService, Gate
 * 7H) em vez de bean separado para contornar auto-invocação de @Transactional -- mais direto que
 * o desenho usado no gate de cancelamento.
 *
 * CC-e nunca altera Pedido/NfeEmissao/NfeDocumento/estoque -- a evidência fica inteiramente em
 * nfe_evento/nfe_evento_idempotencia/NfeLog.
 */
@Service
public class NfeCceOrquestradorService {

    private static final Logger log = LoggerFactory.getLogger(NfeCceOrquestradorService.class);
    private static final int MAX_NSEQ = 20;

    private final NfeEventoMapper eventoMapper;
    private final NfeEventoSequenciaMapper sequenciaMapper;
    private final NfeEventoIdempotenciaMapper idempotenciaMapper;
    private final NfeCceService cceService;
    private final NfeEventoRetornoParser eventoRetornoParser;
    private final NfeCceClassificador classificador;
    private final NfeConsultaSituacaoService consultaSituacaoService;
    private final SefazReconciliacaoProperties reconciliacaoProperties;
    private final TransactionTemplate transactionTemplate;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeCceOrquestradorService(NfeEventoMapper eventoMapper, NfeEventoSequenciaMapper sequenciaMapper,
                                      NfeEventoIdempotenciaMapper idempotenciaMapper, NfeCceService cceService,
                                      NfeEventoRetornoParser eventoRetornoParser, NfeCceClassificador classificador,
                                      NfeConsultaSituacaoService consultaSituacaoService,
                                      SefazReconciliacaoProperties reconciliacaoProperties,
                                      PlatformTransactionManager transactionManager) {
        this.eventoMapper = eventoMapper;
        this.sequenciaMapper = sequenciaMapper;
        this.idempotenciaMapper = idempotenciaMapper;
        this.cceService = cceService;
        this.eventoRetornoParser = eventoRetornoParser;
        this.classificador = classificador;
        this.consultaSituacaoService = consultaSituacaoService;
        this.reconciliacaoProperties = reconciliacaoProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // SERIALIZABLE (mesmo padrão de NfeSequenciaServiceImpl, Gate 1): nfe_evento_sequencia é
        // uma linha compartilhada sob FOR UPDATE ("próxima sequência livre desta chave"), disputada
        // por Idempotency-Keys diferentes -- sob REPEATABLE READ (default do MySQL), N chamadas
        // concorrentes reivindicando identidades NOVAS para a MESMA chave produzem deadlocks reais
        // (gap locks do INSERT em nfe_evento_idempotencia colidindo com o FOR UPDATE da sequência),
        // comprovado em NfeCceGateConcorrenciaRealMySqlIT antes desta correção.
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    private record Contexto(Long pedidoId, Long empresaId, String cnpjEmitente, String uf, String chaveNfe,
                             String conteudo, String idempotencyKey, CertificadoContexto certContexto) {}

    private record Decisao(String estado, Integer cStat, String xMotivo, String nProt, String dhRegEvento,
                            boolean divergente) {
        static Decisao pendente() {
            return new Decisao(NfeEventoIdempotencia.Estados.PENDENTE_CONFIRMACAO, null, null, null, null, false);
        }
        static Decisao registrado(Integer cStat, String xMotivo, String nProt, String dhRegEvento) {
            return new Decisao(NfeEventoIdempotencia.Estados.REGISTRADO, cStat, xMotivo, nProt, dhRegEvento, false);
        }
        static Decisao rejeitado(Integer cStat, String xMotivo) {
            return new Decisao(NfeEventoIdempotencia.Estados.REJEITADO, cStat, xMotivo, null, null, false);
        }
        static Decisao paraDivergente() {
            return new Decisao(NfeEventoIdempotencia.Estados.REJEITADO, null,
                    "Sequência ocupada por conteúdo divergente", null, null, true);
        }
    }

    private sealed interface ResultadoReserva permits NovaReserva, JaExistente, EmAndamento {}
    private record NovaReserva(NfeEventoIdempotencia idempotencia, int nSeqEvento) implements ResultadoReserva {}
    private record JaExistente(NfeEventoIdempotencia idempotencia) implements ResultadoReserva {}
    private record EmAndamento() implements ResultadoReserva {}

    // -------------------------------------------------------------------------
    // Fluxo principal
    // -------------------------------------------------------------------------

    public String corrigir(Long pedidoId, Long empresaId, String cnpjEmitente, String uf, String chaveNfe,
                            String correcao, String idempotencyKey, CertificadoContexto certContexto) throws Exception {
        validarIdempotencyKey(idempotencyKey);
        String conteudo = correcao.trim(); // canonicalização única -- mesmo trim usado em persistir/hash/XML
        Contexto ctx = new Contexto(pedidoId, empresaId, cnpjEmitente, uf, chaveNfe, conteudo, idempotencyKey, certContexto);
        String hash = calcularHash(chaveNfe, conteudo);

        NfeEventoIdempotencia existente = idempotenciaMapper.buscarPorIdempotencyKey(idempotencyKey);
        if (existente != null) {
            return processarComIdempotenciaExistente(existente, hash, ctx);
        }

        garantirGateExiste(ctx);

        ResultadoReserva reserva;
        try {
            reserva = transactionTemplate.execute(status -> reservarNovaOperacao(ctx, idempotencyKey, hash));
        } catch (DuplicateKeyException e) {
            // Corrida: outra chamada com a MESMA Idempotency-Key venceu entre a checagem inicial
            // (fora de TX) e o INSERT -- toda a transação já sofreu rollback (inclusive o
            // nfe_evento reservado/reaberto por esta tentativa), nada órfão fica na base.
            NfeEventoIdempotencia concorrente = idempotenciaMapper.buscarPorIdempotencyKey(idempotencyKey);
            if (concorrente == null) {
                throw new IllegalStateException("Idempotency-Key colidiu mas releitura não encontrou "
                        + "registro -- estado inconsistente para key=" + idempotencyKey);
            }
            return processarComIdempotenciaExistente(concorrente, hash, ctx);
        } catch (ConcurrencyFailureException e) {
            // Deadlock/lock-wait-timeout real do MySQL (comprovado em NfeCceGateConcorrenciaRealMySqlIT
            // sob alta concorrência de Idempotency-Keys NOVAS disputando o FOR UPDATE de
            // nfe_evento_sequencia) -- toda a transação já sofreu rollback, nunca decide nada a
            // partir de uma tentativa que nem chegou a comitar; a chamada só precisa ser refeita.
            log.warn("[NfeCceOrquestrador] Corrida de lock real na reserva -- tratada como CCE_EM_ANDAMENTO, "
                    + "retryable | pedidoId={} | erro={}", pedidoId, e.getMessage());
            throw BusinessException.cceEmAndamento(pedidoId);
        }

        if (reserva instanceof EmAndamento) {
            throw BusinessException.cceEmAndamento(pedidoId);
        }
        if (reserva instanceof JaExistente je) {
            return processarComIdempotenciaExistente(je.idempotencia(), hash, ctx);
        }
        NovaReserva nr = (NovaReserva) reserva;
        return prepararETransmitir(nr.idempotencia(), nr.nSeqEvento(), ctx);
    }

    // -------------------------------------------------------------------------
    // Replay / retomada por Idempotency-Key
    // -------------------------------------------------------------------------

    private String processarComIdempotenciaExistente(NfeEventoIdempotencia idem, String hash, Contexto ctx) throws Exception {
        if (!idem.getEmpresaId().equals(ctx.empresaId()) || !idem.getPedidoId().equals(ctx.pedidoId())
                || !idem.getTipoEvento().equals(NfeEvento.TiposEvento.CCE)) {
            // Nunca decide por analogia -- mesma Idempotency-Key pertencendo a outro tenant/pedido
            // nunca pode devolver dado nenhum, mesmo que o hash bata por coincidência.
            throw BusinessException.cceIdempotencyKeyConflict(idem.getIdempotencyKey());
        }
        if (!idem.getConteudoHash().equals(hash)) {
            throw BusinessException.cceIdempotencyKeyConflict(idem.getIdempotencyKey());
        }

        NfeEvento evento = eventoMapper.buscarPorId(idem.getNfeEventoId());
        int nSeqEvento = evento != null ? evento.getNSeqEvento() : 0;

        if (NfeEventoIdempotencia.Estados.isTerminal(idem.getEstadoResultado())) {
            return resultadoFinalDoSnapshot(idem.getEstadoResultado(), idem.getCstatResultado(),
                    idem.getXmotivoResultado(), idem.getNprotResultado(), ctx.chaveNfe(), nSeqEvento, ctx.pedidoId());
        }

        // PREPARADO/TRANSMITIDO/PENDENTE_CONFIRMACAO -- olha o nfe_evento real associado a ESTA
        // operação pra decidir entre retomar transmissão ou reconciliar. Nunca retransmite às cegas.
        if (NfeEvento.Estados.PREPARADO.equals(evento.getEstado())) {
            return prepararETransmitir(idem, nSeqEvento, ctx);
        }
        return reconciliar(idem, evento, ctx);
    }

    // -------------------------------------------------------------------------
    // Bootstrap de sequência histórica -- primeira vez que esta chave é vista localmente
    // -------------------------------------------------------------------------

    private void garantirGateExiste(Contexto ctx) throws Exception {
        NfeEventoSequencia existente = sequenciaMapper.buscarSemLock(ctx.chaveNfe(), NfeEvento.TiposEvento.CCE);
        if (existente != null) {
            return; // fast path -- nenhuma chamada de rede
        }

        ListaEventosRetorno lista;
        try {
            lista = consultaSituacaoService.listarEventosPorTipo(ctx.chaveNfe(), ctx.uf(), tpAmb,
                    ctx.certContexto() != null ? ctx.certContexto().sslContext() : null, NfeEvento.TiposEvento.CCE);
        } catch (SefazTransmissaoIncertaException e) {
            log.warn("[NfeCceOrquestrador] Falha de transporte no bootstrap de sequência | chave={} | erro={}",
                    ctx.chaveNfe(), e.getMessage());
            throw BusinessException.cceBootstrapIndeterminado(ctx.chaveNfe());
        }
        if (lista.falhaParse()) {
            log.warn("[NfeCceOrquestrador] Resposta ilegível no bootstrap de sequência | chave={} | detalhe={}",
                    ctx.chaveNfe(), lista.detalheFalhaParse());
            throw BusinessException.cceBootstrapIndeterminado(ctx.chaveNfe());
        }

        int baseline = lista.eventos().stream()
                .filter(e -> NfeEventoIdempotencia.Estados.REGISTRADO.equals(classificador.classificar(e.cStat()).estado()))
                .mapToInt(ListaEventosRetorno.EventoEncontrado::nSeqEvento)
                .max().orElse(0);

        log.info("[NfeCceOrquestrador] Bootstrap de sequência concluído | chave={} | ultimoNSeqRegistrado={}",
                ctx.chaveNfe(), baseline);

        try {
            transactionTemplate.execute(status -> {
                NfeEventoSequencia nova = new NfeEventoSequencia();
                nova.setChaveNfe(ctx.chaveNfe());
                nova.setTipoEvento(NfeEvento.TiposEvento.CCE);
                nova.setUltimoNSeqRegistrado(baseline);
                sequenciaMapper.inserir(nova);
                return null;
            });
        } catch (DuplicateKeyException e) {
            // Outra chamada concorrente também bootstrapped esta chave -- inofensivo: as duas
            // calculam o baseline a partir da MESMA fonte (SEFAZ), então o valor é o mesmo; a que
            // chegou primeiro já gravou, esta apenas segue (a linha já existe pro passo seguinte).
            log.info("[NfeCceOrquestrador] Bootstrap concorrente -- outra chamada já criou o gate | chave={}", ctx.chaveNfe());
        }
    }

    // -------------------------------------------------------------------------
    // Reserva/reabertura (dentro de TransactionTemplate)
    // -------------------------------------------------------------------------

    private ResultadoReserva reservarNovaOperacao(Contexto ctx, String idempotencyKey, String hash) {
        // Rechecagem da Idempotency-Key com o isolamento SERIALIZABLE já garantindo exclusão
        // mútua -- fecha a corrida em que duas chamadas com a MESMA chave passam pela checagem
        // inicial (fora de TX) antes de qualquer uma comitar (mesmo padrão de
        // OmsAuthorizationAdminService.tentarRotacionar).
        NfeEventoIdempotencia existenteAposLock = idempotenciaMapper.buscarPorIdempotencyKeyParaAtualizar(idempotencyKey);
        if (existenteAposLock != null) {
            return new JaExistente(existenteAposLock);
        }

        NfeEventoSequencia seq = sequenciaMapper.buscarParaAtualizar(ctx.chaveNfe(), NfeEvento.TiposEvento.CCE);
        if (seq == null) {
            throw new IllegalStateException("nfe_evento_sequencia ausente para chave=" + ctx.chaveNfe()
                    + " após garantirGateExiste -- bug de bootstrap.");
        }

        NfeEvento evento;
        if (seq.getEventoAtivoId() != null) {
            NfeEvento ativo = eventoMapper.buscarPorIdParaAtualizar(seq.getEventoAtivoId());
            if (ativo == null) {
                throw new IllegalStateException("nfe_evento_sequencia aponta para evento_ativo_id="
                        + seq.getEventoAtivoId() + " inexistente -- chave=" + ctx.chaveNfe());
            }
            if (!NfeEvento.Estados.REJEITADO.equals(ativo.getEstado())) {
                // PREPARADO/TRANSMITIDO/PENDENTE_CONFIRMACAO de OUTRA operação -- nunca reserva
                // nova nem reabre; o chamador desta Idempotency-Key nova precisa aguardar.
                return new EmAndamento();
            }
            int reaberta = eventoMapper.retomarAposRejeicaoComConteudo(ativo.getId(), ctx.conteudo());
            if (reaberta == 0) {
                return new EmAndamento(); // corrida: outra chamada já avançou a linha
            }
            evento = ativo;
        } else {
            int novoNSeq = seq.getUltimoNSeqRegistrado() + 1;
            if (novoNSeq > MAX_NSEQ) {
                throw BusinessException.cceLimiteSequenciaAtingido(ctx.chaveNfe(), seq.getUltimoNSeqRegistrado());
            }
            NfeEvento nova = new NfeEvento();
            nova.setPedidoId(ctx.pedidoId());
            nova.setEmpresaId(ctx.empresaId());
            nova.setCnpjEmitente(ctx.cnpjEmitente());
            nova.setChaveNfe(ctx.chaveNfe());
            nova.setTipoEvento(NfeEvento.TiposEvento.CCE);
            nova.setNSeqEvento(novoNSeq);
            nova.setIdEvento("ID" + NfeEvento.TiposEvento.CCE + ctx.chaveNfe() + String.format("%02d", novoNSeq));
            nova.setEstado(NfeEvento.Estados.PREPARADO);
            nova.setConteudoEvento(ctx.conteudo());
            eventoMapper.inserirPreparado(nova); // UNIQUE(chave,tipo,nSeq) -- defesa adicional, corrida extrema
            sequenciaMapper.ocuparGate(ctx.chaveNfe(), NfeEvento.TiposEvento.CCE, nova.getId());
            evento = nova;
        }

        NfeEventoIdempotencia idem = new NfeEventoIdempotencia();
        idem.setIdempotencyKey(idempotencyKey);
        idem.setEmpresaId(ctx.empresaId());
        idem.setPedidoId(ctx.pedidoId());
        idem.setTipoEvento(NfeEvento.TiposEvento.CCE);
        idem.setConteudoHash(hash);
        idem.setNfeEventoId(evento.getId());
        idem.setEstadoResultado(NfeEventoIdempotencia.Estados.PREPARADO);
        idempotenciaMapper.inserir(idem); // pode lançar DuplicateKeyException -- propaga, rollback total

        return new NovaReserva(idem, evento.getNSeqEvento());
    }

    // -------------------------------------------------------------------------
    // Transmissão
    // -------------------------------------------------------------------------

    private String prepararETransmitir(NfeEventoIdempotencia idem, int nSeqEvento, Contexto ctx) throws Exception {
        NfeCceRequest req = new NfeCceRequest();
        req.setChaveNfe(ctx.chaveNfe());
        req.setCorrecao(ctx.conteudo());

        NfeCceEventoPreparado preparado = cceService.prepararEvento(req, ctx.cnpjEmitente(), ctx.uf(),
                ctx.certContexto(), nSeqEvento);

        Boolean marcado = transactionTemplate.execute(status -> {
            int rows = eventoMapper.marcarTransmitido(idem.getNfeEventoId(), preparado.dhEvento(), preparado.payloadHash());
            if (rows == 1) {
                idempotenciaMapper.marcarTransmitido(idem.getId());
            }
            return rows == 1;
        });
        if (marcado == null || !marcado) {
            // Corrida perdida entre a reserva e este marcador -- outra chamada concorrente já
            // avançou a mesma linha antes desta.
            throw BusinessException.cceEmAndamento(ctx.pedidoId());
        }

        String respostaSoap;
        try {
            respostaSoap = cceService.transmitirEvento(preparado, ctx.certContexto());
        } catch (SefazTransmissaoIncertaException e) {
            log.warn("[NfeCceOrquestrador] Falha de transporte na transmissão da CC-e | pedidoId={} | "
                    + "eventoId={} | erro={}", ctx.pedidoId(), idem.getNfeEventoId(), e.getMessage());
            // Linha já ficou TRANSMITIDO -- nenhuma escrita adicional; reconciliação resolve depois.
            throw BusinessException.cceAguardandoReconciliacao(ctx.pedidoId());
        }

        NfeEventoRetorno retorno = eventoRetornoParser.parse(respostaSoap);
        Decisao decisao = decidirClassificacao(retorno);
        return finalizarEDecidir(idem.getId(), idem.getNfeEventoId(), decisao, ctx.pedidoId(), ctx.chaveNfe(), nSeqEvento);
    }

    // -------------------------------------------------------------------------
    // Reconciliação
    // -------------------------------------------------------------------------

    private String reconciliar(NfeEventoIdempotencia idem, NfeEvento evento, Contexto ctx) {
        Boolean claim = transactionTemplate.execute(status -> eventoMapper.tentarAdquirirJanelaConsulta(
                evento.getId(), LocalDateTime.now(), reconciliacaoProperties.getBackoffInicialSegundos(),
                reconciliacaoProperties.getBackoffMultiplicador(), reconciliacaoProperties.getBackoffMaximoSegundos()) == 1);
        if (claim == null || !claim) {
            throw BusinessException.cceAguardandoReconciliacao(ctx.pedidoId());
        }

        NfeConsultaSituacaoRetorno retorno;
        try {
            retorno = consultaSituacaoService.consultarEvento(ctx.chaveNfe(), ctx.uf(), tpAmb,
                    ctx.certContexto() != null ? ctx.certContexto().sslContext() : null,
                    NfeEvento.TiposEvento.CCE, String.valueOf(evento.getNSeqEvento()));
        } catch (SefazTransmissaoIncertaException e) {
            log.warn("[NfeCceOrquestrador] Falha de transporte na reconciliação da CC-e | pedidoId={} | erro={}",
                    ctx.pedidoId(), e.getMessage());
            throw BusinessException.cceAguardandoReconciliacao(ctx.pedidoId());
        }

        Decisao decisao = decidirClassificacaoReconciliacao(retorno, ctx.conteudo());
        return finalizarEDecidir(idem.getId(), evento.getId(), decisao, ctx.pedidoId(), ctx.chaveNfe(), evento.getNSeqEvento());
    }

    // -------------------------------------------------------------------------
    // Classificação
    // -------------------------------------------------------------------------

    private Decisao decidirClassificacao(NfeEventoRetorno retorno) {
        if (retorno.isFalhaParse()) {
            log.warn("[NfeCceOrquestrador] Resposta da CC-e ilegível -- tratada como incerta, nunca rejeição | detalhe={}",
                    retorno.getDetalheFalhaParse());
            return Decisao.pendente();
        }
        if (retorno.isLoteRejeitado()) {
            return Decisao.rejeitado(retorno.getCStatLote(), retorno.getXMotivoLote());
        }
        if (retorno.isResultadoIndividualDisponivel()) {
            NfeCceClassificador.Resultado r = classificador.classificar(retorno.getCStatEvento());
            return new Decisao(r.estado(), retorno.getCStatEvento(), retorno.getXMotivoEvento(),
                    retorno.getNProtEvento(), retorno.getDhRegEvento(), false);
        }
        // 128 (lote processado) sem infEvento presente/legível -- resultado individual
        // desconhecido, NUNCA rejeição inventada.
        return Decisao.pendente();
    }

    private Decisao decidirClassificacaoReconciliacao(NfeConsultaSituacaoRetorno retorno, String conteudoEsperado) {
        if (retorno.isFalhaParse()) {
            return Decisao.pendente();
        }
        if (!retorno.isEventoEncontrado() || retorno.getCStatEvento() == null) {
            return Decisao.pendente();
        }
        NfeCceClassificador.Resultado classificacao = classificador.classificar(retorno.getCStatEvento());
        if (NfeEventoIdempotencia.Estados.REGISTRADO.equals(classificacao.estado())) {
            // Achado de banca (12-08-2026): identidade fiscal batendo (chave+110110+nSeq) NUNCA
            // prova sozinho que o CONTEÚDO bate -- outra via pode ter registrado essa sequência
            // com um xCorrecao diferente. Sem o conteúdo encontrado pra comparar, NUNCA assume
            // que é nosso (fail-safe -- trata como divergente, nunca como sucesso silencioso).
            String encontrado = retorno.getConteudoEventoEncontrado();
            if (encontrado == null || !encontrado.trim().equals(conteudoEsperado)) {
                return Decisao.paraDivergente();
            }
        }
        return new Decisao(classificacao.estado(), retorno.getCStatEvento(), retorno.getXMotivoEvento(),
                retorno.getNProtEvento(), retorno.getDhRegEvento(), false);
    }

    // -------------------------------------------------------------------------
    // Finalização (dentro de TransactionTemplate) -- evento + idempotência + gate, sem rede
    // -------------------------------------------------------------------------

    private String finalizarEDecidir(Long idemId, Long eventoId, Decisao decisao, Long pedidoId,
                                      String chaveNfe, int nSeqEvento) {
        Boolean aplicado = transactionTemplate.execute(status ->
                aplicarFinalizacao(idemId, eventoId, decisao, chaveNfe, nSeqEvento));

        if (aplicado == null || !aplicado) {
            // Ciclo já estava terminal (chamada duplicada/concorrente da MESMA Idempotency-Key
            // resolveu primeiro) -- reconsulta o snapshot da PRÓPRIA operação (idempotência) por
            // id, nunca do nfe_evento (que pode ter sido reaberto por outra operação depois).
            NfeEventoIdempotencia atual = idempotenciaMapper.buscarPorId(idemId);
            if (atual == null) {
                throw new IllegalStateException("nfe_evento_idempotencia id=" + idemId
                        + " não encontrado ao reconsultar desfecho já terminal.");
            }
            return resultadoFinalDoSnapshot(atual.getEstadoResultado(), atual.getCstatResultado(),
                    atual.getXmotivoResultado(), atual.getNprotResultado(), chaveNfe, nSeqEvento, pedidoId);
        }
        return resultadoFinalDoSnapshot(decisao.estado(), decisao.cStat(), decisao.xMotivo(),
                decisao.divergente() ? null : decisao.nProt(), chaveNfe, nSeqEvento, pedidoId);
    }

    private boolean aplicarFinalizacao(Long idemId, Long eventoId, Decisao decisao, String chaveNfe, int nSeqEvento) {
        NfeEvento travado = eventoMapper.buscarPorIdParaAtualizar(eventoId);
        if (travado == null) {
            throw new IllegalStateException("nfe_evento id=" + eventoId + " não encontrado ao finalizar CC-e.");
        }
        if (NfeEvento.Estados.isTerminal(travado.getEstado())) {
            return false;
        }

        // nfe_evento reflete a REALIDADE fiscal da identidade: se divergente, a sequência FOI
        // registrada (só não com nosso conteúdo) -- REGISTRADO aqui, mesmo que a operação atual
        // (idempotência) registre REJEITADO com o motivo de divergência.
        String estadoNfeEvento = decisao.divergente() ? NfeEvento.Estados.REGISTRADO : decisao.estado();
        boolean terminalNfeEvento = NfeEvento.Estados.isTerminal(estadoNfeEvento);

        travado.setEstado(estadoNfeEvento);
        travado.setCstat(decisao.cStat());
        travado.setXmotivo(decisao.xMotivo());
        travado.setNprot(decisao.nProt());
        travado.setResolucaoOrigem(terminalNfeEvento
                ? (decisao.dhRegEvento() != null ? NfeEvento.OrigensResolucao.CONSULTA_SITUACAO : NfeEvento.OrigensResolucao.EVENTO_DIRETO)
                : null);
        travado.setResolvidoEm(terminalNfeEvento ? LocalDateTime.now() : null);
        eventoMapper.atualizarResultado(travado);

        NfeEventoIdempotencia idemAtualizada = new NfeEventoIdempotencia();
        idemAtualizada.setId(idemId);
        idemAtualizada.setEstadoResultado(decisao.estado());
        idemAtualizada.setCstatResultado(decisao.cStat());
        idemAtualizada.setXmotivoResultado(decisao.xMotivo());
        idemAtualizada.setNprotResultado(decisao.divergente() ? null : decisao.nProt());
        idemAtualizada.setDhRegEventoResultado(decisao.dhRegEvento());
        idemAtualizada.setResolvidoEm(NfeEventoIdempotencia.Estados.isTerminal(decisao.estado()) ? LocalDateTime.now() : null);
        idempotenciaMapper.atualizarResultado(idemAtualizada);

        if (NfeEvento.Estados.REGISTRADO.equals(estadoNfeEvento)) {
            // Libera o gate e avança a sequência consumida -- vale tanto pro sucesso real quanto
            // pro divergente (a sequência FOI consumida por alguém, nunca reaproveitada).
            sequenciaMapper.atualizarUltimoNSeq(chaveNfe, NfeEvento.TiposEvento.CCE, nSeqEvento);
            sequenciaMapper.liberarGate(chaveNfe, NfeEvento.TiposEvento.CCE);
            if (!decisao.divergente()) {
                log.info("[NfeCceOrquestrador] CC-e homologada | chave={} | nSeq={} | cStat={}",
                        chaveNfe, nSeqEvento, decisao.cStat());
            } else {
                log.warn("[NfeCceOrquestrador] CC-e divergente -- sequência consumida por outro conteúdo | "
                        + "chave={} | nSeq={}", chaveNfe, nSeqEvento);
            }
        }
        // REJEITADO (não divergente): gate continua apontando pra esta linha, disponível pra
        // reabertura por uma nova Idempotency-Key. PENDENTE_CONFIRMACAO: nada muda no gate.
        return true;
    }

    /**
     * cStat==null junto com REJEITADO é o sinal de divergência (Decisao.paraDivergente() nunca grava
     * um cStat próprio -- a rejeição desta operação não veio de uma resposta SEFAZ para ESTA
     * tentativa, veio da constatação de que a sequência já tem outro conteúdo). Uma rejeição SEFAZ
     * normal sempre carrega um cStat real.
     */
    private String resultadoFinalDoSnapshot(String estado, Integer cStat, String xMotivo, String nProt,
                                             String chaveNfe, int nSeqEvento, Long pedidoId) {
        if (NfeEventoIdempotencia.Estados.REGISTRADO.equals(estado)) {
            return resultadoRegistrado(cStat, nProt);
        }
        if (NfeEventoIdempotencia.Estados.REJEITADO.equals(estado)) {
            if (cStat == null) {
                throw BusinessException.cceEventoDivergente(chaveNfe, nSeqEvento);
            }
            throw BusinessException.cceRejeitada(cStat, xMotivo);
        }
        throw BusinessException.cceAguardandoReconciliacao(pedidoId);
    }

    private String resultadoRegistrado(Integer cStat, String nProt) {
        return "CC-e homologada (cStat=" + cStat + ", nProt=" + nProt + ")";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void validarIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BusinessException.invalidIdempotencyKey();
        }
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalidIdempotencyKey();
        }
    }

    private String calcularHash(String chaveNfe, String conteudo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((chaveNfe + "|" + conteudo).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular hash de conteúdo da CC-e", e);
        }
    }
}
