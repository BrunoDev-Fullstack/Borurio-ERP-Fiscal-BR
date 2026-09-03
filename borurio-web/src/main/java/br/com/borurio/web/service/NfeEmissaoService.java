package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.mapper.PedidoItemMapper;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.mapper.NfeDocumentoMapper;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.web.dto.AberturaCicloResultado;
import br.com.borurio.web.dto.AbandonoCicloResultado;
import br.com.borurio.web.dto.TransporteNaoEntregueResultado;
import br.com.borurio.web.dto.TipoAberturaCiclo;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Gate 1 da máquina de estados fiscal de numeração — ciclo operacional do nNF. Substitui
 * ReservaFiscalService: em vez de um contador simples (incrementa na reserva, sem volta), o
 * número fica "em voo" (nfe_emissao) e só é fiscalmente consumido (nfe_sequencia.ultimo_numero)
 * quando o ciclo chega a um resultado terminal — AUTORIZADO ou DENEGADO. Rejeição comum de
 * validação (AGUARDANDO_CORRECAO) e resultado incerto (TRANSMITIDO/PENDENTE_CONFIRMACAO) NÃO
 * liberam o gate da série — o número continua pertencendo ao pedido até ter destino definitivo,
 * o que é o requisito central de "não pular número" (ver plano de 07-08-2026,
 * scalable-floating-wand.md).
 *
 * Duas operações, duas transações curtas — nunca uma chamada de rede (SEFAZ) dentro de nenhuma
 * das duas, mesmo princípio já documentado em ReservaFiscalService: segurar lock de banco durante
 * I/O externo travaria toda emissão concorrente da mesma série.
 *
 * Ordem canônica de lock do projeto (P0-3, 07-08-2026, hardening pós-banca — evita deadlock
 * entre esta classe e FiscalNumberingService): Empresa, quando necessária -> nfe_sequencia ->
 * nfe_emissao. Nunca adquirir nfe_emissao antes de nfe_sequencia numa transação que precise das
 * duas. abrirCiclo/retomarCicloAtivo já seguiam essa ordem; resolverCiclo foi corrigido para
 * segui-la também (antes travava nfe_emissao primeiro — inversão AB-BA real, comprovada por
 * auditoria de código antes da correção, ver NfeEmissaoServiceAdversarialTest).
 *
 * Gate 1 propositalmente NÃO cobre: classificação semântica de cStat (Gate 2 — o chamador decide
 * o estado alvo), reconciliação ativa de PENDENTE_CONFIRMACAO/TRANSMITIDO via consulta à SEFAZ
 * (Gate 3 — aqui só existe o guard que bloqueia nova tentativa, não a resolução), efeitos de
 * estoque e atualização de Pedido.status (ficam em PedidoEmissaoService, como já era com
 * ReservaFiscalService).
 */
@Service
public class NfeEmissaoService {

    private static final Logger log = LoggerFactory.getLogger(NfeEmissaoService.class);

    /**
     * Resultado de {@code aplicarNovoEstado} -- distingue os desfechos possíveis pra que
     * {@link #resolverCicloComEfeitos} saiba exatamente quando é seguro tocar Pedido/Estoque.
     * Fase 1 SVC (17-08-2026, persistência/ciclo de substituição): antes só existia um boolean
     * aplicado/não-aplicado; os dois casos novos (NORMAL substituída, ver
     * {@code emissao_origem_id} em {@link NfeEmissao}) nunca disparam efeito operacional.
     */
    private enum ResultadoAplicacao {
        /** Ciclo (não substituído) já estava terminal antes desta chamada -- no-op. */
        NAO_APLICADO_JA_TERMINAL,
        /** Transição normal aplicada -- único resultado que autoriza efeitos de Pedido/Estoque. */
        APLICADO_NORMAL,
        /** 1ª evidência fiscal gravada numa NORMAL substituída -- nunca consome número/libera gate/toca Pedido/Estoque. */
        APLICADO_EVIDENCIA_SUBSTITUIDA,
        /** NORMAL substituída já tinha evidência gravada (idêntica ou divergente, ver log WARN) -- nunca reaplica. */
        NAO_APLICADO_EVIDENCIA_JA_REGISTRADA
    }

    private final EmpresaMapper empresaMapper;
    private final PedidoMapper pedidoMapper;
    private final PedidoItemMapper pedidoItemMapper;
    private final NfeSequenciaService sequenciaService;
    private final NfeEmissaoMapper nfeEmissaoMapper;
    private final NfeDocumentoMapper nfeDocumentoMapper;
    private final EstoqueService estoqueService;
    private final SefazReconciliacaoProperties reconciliacaoProperties;

    public NfeEmissaoService(EmpresaMapper empresaMapper, PedidoMapper pedidoMapper,
                              PedidoItemMapper pedidoItemMapper,
                              NfeSequenciaService sequenciaService, NfeEmissaoMapper nfeEmissaoMapper,
                              NfeDocumentoMapper nfeDocumentoMapper,
                              EstoqueService estoqueService, SefazReconciliacaoProperties reconciliacaoProperties) {
        this.empresaMapper = empresaMapper;
        this.pedidoMapper = pedidoMapper;
        this.pedidoItemMapper = pedidoItemMapper;
        this.sequenciaService = sequenciaService;
        this.nfeEmissaoMapper = nfeEmissaoMapper;
        this.nfeDocumentoMapper = nfeDocumentoMapper;
        this.estoqueService = estoqueService;
        this.reconciliacaoProperties = reconciliacaoProperties;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AberturaCicloResultado abrirCiclo(Long pedidoId, String cnpjEmitente) {
        Empresa empresa = empresaMapper.buscarPorCnpjParaAtualizar(cnpjEmitente);
        if (empresa == null) {
            throw BusinessException.companyNotFound(cnpjEmitente);
        }
        String serie = (empresa.getSerieNfePadrao() != null && !empresa.getSerieNfePadrao().isBlank())
                ? empresa.getSerieNfePadrao()
                : "1";

        NfeSequencia seq = sequenciaService.buscarOuCriarParaAtualizar(cnpjEmitente, serie);

        if (seq.getEmissaoAtivaId() != null) {
            return retomarCicloAtivo(pedidoId, cnpjEmitente, serie, seq.getEmissaoAtivaId());
        }
        return abrirCicloNovo(pedidoId, empresa, cnpjEmitente, serie, seq.getUltimoNumero());
    }

    private AberturaCicloResultado retomarCicloAtivo(Long pedidoId, String cnpjEmitente, String serie,
                                                       Long emissaoAtivaId) {
        NfeEmissao ativa = nfeEmissaoMapper.buscarPorIdParaAtualizar(emissaoAtivaId);
        if (ativa == null) {
            // Gate apontando para uma linha que não existe — nunca deveria acontecer (ambos são
            // escritos na mesma transação em abrirCicloNovo). Falha explícita em vez de NPE.
            throw new IllegalStateException("Gate fiscal de CNPJ=" + cnpjEmitente + " série=" + serie
                    + " aponta para nfe_emissao id=" + emissaoAtivaId + ", que não existe.");
        }
        if (!ativa.getPedidoId().equals(pedidoId)) {
            throw BusinessException.emissaoEmAndamentoNaSerie(cnpjEmitente, serie);
        }

        String estado = ativa.getEstado();
        if (NfeEmissao.Estados.TRANSMITIDO.equals(estado)
                || NfeEmissao.Estados.PENDENTE_CONFIRMACAO.equals(estado)) {
            // Resultado ainda incerto (timeout, ou crash entre marcar TRANSMITIDO e receber a
            // resposta da SEFAZ) — nunca gerar uma chave nova enquanto a anterior não tiver
            // destino definitivo. Reconciliação real (consultar a SEFAZ pela chave já congelada)
            // é Gate 3, ainda não implementado — por ora só bloqueia.
            throw BusinessException.emissaoAguardandoReconciliacao(pedidoId);
        }
        if (NfeEmissao.Estados.RESERVADO.equals(estado)) {
            // Crash antes de transmitir: mesma linha, mesmo número, estoque intocado (a reserva
            // original ainda está de pé — matriz de estoque no plano do Gate 1).
            pedidoMapper.atualizarSerieReservada(pedidoId, serie);
            return new AberturaCicloResultado(ativa, TipoAberturaCiclo.RETOMADA_RESERVADO);
        }
        if (NfeEmissao.Estados.AGUARDANDO_CORRECAO.equals(estado)) {
            nfeEmissaoMapper.retomarComoReservado(ativa.getId());
            ativa.setEstado(NfeEmissao.Estados.RESERVADO);
            ativa.setTentativas(ativa.getTentativas() + 1);
            pedidoMapper.atualizarSerieReservada(pedidoId, serie);
            return new AberturaCicloResultado(ativa, TipoAberturaCiclo.RETOMADA_AGUARDANDO_CORRECAO);
        }

        // AUTORIZADO/DENEGADO liberam o gate em resolverCiclo — se chegou até aqui com um desses
        // dois estados, o gate ficou preso indevidamente. Estado inconsistente, falha explícita.
        throw new IllegalStateException("Gate fiscal de CNPJ=" + cnpjEmitente + " série=" + serie
                + " aponta para nfe_emissao id=" + ativa.getId() + " em estado terminal ("
                + estado + ") — deveria ter liberado o gate ao resolver.");
    }

    private AberturaCicloResultado abrirCicloNovo(Long pedidoId, Empresa empresa, String cnpjEmitente,
                                                    String serie, int ultimoNumero) {
        int candidato = ultimoNumero + 1; // peek — só vira definitivo em resolverCiclo/consumirNumero

        NfeEmissao nova = new NfeEmissao();
        nova.setPedidoId(pedidoId);
        nova.setEmpresaId(empresa.getId());
        nova.setCnpjEmitente(cnpjEmitente);
        nova.setModelo("55");
        nova.setSerie(serie);
        nova.setNumeroNfe(candidato);
        nova.setEstado(NfeEmissao.Estados.RESERVADO);
        nova.setTentativas(1);
        nfeEmissaoMapper.inserir(nova);

        sequenciaService.ocuparGate(cnpjEmitente, serie, nova.getId());
        pedidoMapper.atualizarSerieReservada(pedidoId, serie);

        return new AberturaCicloResultado(nova, TipoAberturaCiclo.NOVA_ABERTURA);
    }

    /**
     * Congela a chave da tentativa em voo — chamado por NfeGeracaoService assim que a chave é
     * calculada, ANTES da chamada à SEFAZ. Transação simples (UPDATE por PK), sem isolamento
     * especial: não compete por gate, só grava um fato já decidido.
     */
    @Transactional
    public void marcarTransmitido(Long emissaoId, String chaveNfe) {
        nfeEmissaoMapper.marcarTransmitido(emissaoId, chaveNfe);
    }

    /**
     * Reverte uma marcação de TRANSMITIDO feita cedo demais — usado só quando há certeza local e
     * síncrona (mesma requisição, sem round-trip de rede envolvido) de que nada foi de fato
     * transmitido à SEFAZ. Caso real: {@code NfeGeracaoService.marcarTransmitido} roda antes de
     * {@code NfeOrquestradorService.processar()}, que inclui validação de XSD/assinatura — falhas
     * aí são locais, não de rede, mas acontecem depois da chave já ter sido congelada.
     *
     * Nunca usar para timeout ou qualquer cenário em que a rede pode ter sido alcançada — nesse
     * caso o estado correto é PENDENTE_CONFIRMACAO via {@link #resolverCiclo}, não isto.
     * Idempotente: no-op se o ciclo não estiver mais em TRANSMITIDO.
     */
    @Transactional
    public void reverterParaReservadoPorFalhaLocal(Long emissaoId) {
        nfeEmissaoMapper.reverterTransmitidoParaReservado(emissaoId);
    }

    /** Último ciclo de nNF (qualquer estado) registrado para este pedido, ou null se nenhum existir. */
    public NfeEmissao buscarUltimaEmissaoDoPedido(Long pedidoId) {
        return nfeEmissaoMapper.buscarUltimaPorPedido(pedidoId);
    }

    /**
     * SVC Fase 2 (18-08-2026) — leitura simples (sem lock), usada por NfeGeracaoService para
     * resolver tpEmis/dhCont/xJust/numeroNfe/serie/cnpjEmitente a partir do ciclo já reservado
     * (Gate 1), ANTES de montar chave/XML. Não bloqueante de propósito: a chamada acontece antes
     * de qualquer chamada à SEFAZ, fora de qualquer transação de escrita do ciclo fiscal.
     */
    public NfeEmissao buscarPorId(Long id) {
        return nfeEmissaoMapper.buscarPorId(id);
    }

    /**
     * Resolve o ciclo do nNF a partir de um estado-alvo já classificado pelo chamador (Gate 1 não
     * inclui a camada semântica de classificação de cStat — isso é Gate 2; por ora o chamador
     * decide entre AUTORIZADO/DENEGADO/AGUARDANDO_CORRECAO/PENDENTE_CONFIRMACAO com a mesma lógica
     * binária que já existia em PedidoEmissaoService.resolverStatus()).
     *
     * Idempotente: se o ciclo já estiver num estado terminal (AUTORIZADO/DENEGADO), esta chamada
     * não faz nada — protege contra uma segunda resolução do mesmo ciclo (relevante para Gate 3,
     * quando reconciliação puder ser chamada mais de uma vez sobre o mesmo id).
     *
     * Só AUTORIZADO/DENEGADO avançam nfe_sequencia.ultimo_numero e liberam o gate.
     * AGUARDANDO_CORRECAO e PENDENTE_CONFIRMACAO mantêm o gate ocupado — é essa retenção que
     * garante que nenhum outro pedido da mesma série avança enquanto este número não tiver
     * destino definitivo.
     */
    // P0-3 (07-08-2026, hardening pós-banca): isolamento REPEATABLE_READ (não SERIALIZABLE) —
    // achado real contra MySQL, não teórico. Sob SERIALIZABLE, o InnoDB converte TODA leitura
    // simples (mesmo sem FOR UPDATE) numa leitura com LOCK IN SHARE MODE implícito. Isso quebrava
    // a premissa central da pré-leitura abaixo (deliberadamente não-bloqueante): duas chamadas
    // concorrentes de resolverCiclo tomavam cada uma um shared lock na mesma linha de nfe_emissao
    // via pré-leitura, e as duas travavam tentando promover esse shared lock pra FOR UPDATE
    // (exclusive) — deadlock genuíno, reproduzido em NfeEmissaoLockOrderRealMySqlIT antes desta
    // correção. REPEATABLE_READ não tem essa conversão implícita; os dois locks que realmente
    // precisam ser exclusivos (nfe_sequencia e nfe_emissao) já usam FOR UPDATE explícito, então a
    // garantia de exclusão mútua é idêntica — só a pré-leitura deixa de tomar lock nenhum, como
    // sempre foi a intenção. Sem risco de fantasma: os dois FOR UPDATE aqui são buscas por PK/
    // chave única de uma linha já existente, nunca um range scan concorrente com INSERT.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void resolverCiclo(Long emissaoId, String novoEstado, Integer cStat, String xMotivo, String nProt) {
        aplicarNovoEstado(emissaoId, novoEstado, cStat, xMotivo, nProt);
    }

    /**
     * Finalização atômica do ciclo (Gate 3, 10-08-2026 — fecha a janela de crash comprovada na
     * auditoria entre resolverCiclo() e os efeitos de Pedido/Estoque, que antes eram aplicados em
     * transações separadas por PedidoEmissaoService). Uma única transação: nfe_emissao (+
     * nfe_sequencia quando terminal) -> Pedido -> Estoque. Ou tudo persiste, ou nada persiste.
     *
     * Exactly-once do CONJUNTO: se o ciclo já estava terminal (chamada duplicada — retry,
     * reconciliação concorrente), {@code aplicarNovoEstado} devolve um resultado diferente de
     * {@code APLICADO_NORMAL} e este método para imediatamente, sem tocar Pedido/Estoque — os
     * efeitos já foram aplicados juntos, na mesma transação, pela chamada que venceu a primeira
     * vez. Nunca é possível aplicar o efeito fiscal sem o efeito operacional, nem vice-versa.
     *
     * Fase 1 SVC (17-08-2026): se {@code emissaoId} identifica uma NORMAL já substituída por
     * contingência (existe uma linha com {@code emissao_origem_id = emissaoId}),
     * {@code aplicarNovoEstado} persiste só a evidência fiscal na própria linha NORMAL e devolve
     * {@code APLICADO_EVIDENCIA_SUBSTITUIDA}/{@code NAO_APLICADO_EVIDENCIA_JA_REGISTRADA} — este
     * método nunca chama {@code pedidoMapper}/{@code estoqueService} pra nenhum dos dois.
     *
     * Ordem de lock estendida: Empresa -> nfe_sequencia -> nfe_emissao -> Pedido -> Estoque —
     * primeira vez que Pedido/Estoque entram na mesma transação que nfe_sequencia/nfe_emissao;
     * antes disso as duas fases eram sempre sequenciais (transações disjuntas no tempo).
     *
     * pedidoMapper é usado diretamente aqui (não via PedidoService), mesmo padrão já usado por
     * abrirCicloNovo/retomarCicloAtivo para atualizarSerieReservada — a existência do Pedido já é
     * garantida por quem chama este método (sempre a partir de um ciclo real de nfe_emissao).
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void resolverCicloComEfeitos(Long emissaoId, String novoEstado, Integer cStat, String xMotivo, String nProt,
                                         Long pedidoId, String statusPedido, String chaveNfeParaPedido,
                                         boolean controlaEstoque, List<PedidoItem> itens, Long empresaId, String criadoPor) {
        ResultadoAplicacao resultado = aplicarNovoEstado(emissaoId, novoEstado, cStat, xMotivo, nProt);
        if (resultado != ResultadoAplicacao.APLICADO_NORMAL) {
            return;
        }

        pedidoMapper.atualizarStatus(pedidoId, statusPedido, chaveNfeParaPedido);

        if (controlaEstoque) {
            if (NfeEmissao.Estados.AUTORIZADO.equals(novoEstado)) {
                estoqueService.baixaDefinitivaItens(itens, empresaId, pedidoId, criadoPor);
            } else if (NfeEmissao.Estados.AGUARDANDO_CORRECAO.equals(novoEstado)
                    || NfeEmissao.Estados.NUMERO_OCUPADO.equals(novoEstado)) {
                estoqueService.desfazerReservaItens(itens, empresaId, pedidoId, criadoPor);
            }
            // PENDENTE_CONFIRMACAO nunca chega aqui como terminal — aplicarNovoEstado não altera
            // nfe_sequencia para esse estado, mas o CHAMADOR também nunca deve invocar este método
            // (que assume estado final decidido) para PENDENTE_CONFIRMACAO; ver NfeReconciliacaoService.
        }
    }

    /**
     * Recovery administrativo (02-09-2026, modelo "gap" revisado no mesmo dia pós-incidente) —
     * encerra um ciclo em AGUARDANDO_CORRECAO cujo dado de origem não pode mais ser corrigido pelo
     * fluxo normal (ex.: xProd rejeitado por schema num pedido sem endpoint de edição de item),
     * liberando o gate da série para os demais pedidos.
     *
     * Modelo "gap" — a linha de {@code nfe_emissao} NÃO é apagada e ocupa permanentemente o slot
     * {@code (cnpj_emitente, modelo, serie, numero_nfe)} via a UNIQUE {@code uk_nfe_emissao_numero}.
     * Portanto o abandono:
     *   - libera o gate da série ({@code emissao_ativa_id -> NULL});
     *   - AVANÇA {@code nfe_sequencia.ultimo_numero} até o {@code numero_nfe} deste ciclo (ver
     *     {@link #avancarSequenciaParaGap}) — nunca além, nunca regredindo. O nNF fica "queimado":
     *     o próximo {@code abrirCiclo} da série pega {@code numero_nfe + 1}, nunca reusa o nNF.
     * Diferença em relação aos terminais de {@code isTerminal} (AUTORIZADO/DENEGADO/NUMERO_OCUPADO):
     * aqueles avançam o contador porque a SEFAZ deu destino fiscal ao nNF; aqui o contador avança
     * só para não colidir na constraint — sem autorização, sem denegação, e sem chamar
     * {@code consumirNumero()} (cuja checagem estrita {@code numero == ultimoNumero+1} não vale
     * aqui: o gap é esperado). É por isso que ABANDONADO fica fora de {@code isTerminal} e esta é
     * uma operação separada, nunca um {@code novoEstado} de {@code aplicarNovoEstado}.
     *
     * Guards (todos sob o lock FOR UPDATE de nfe_emissao):
     *   - só a partir de AGUARDANDO_CORRECAO — nunca RESERVADO/TRANSMITIDO/PENDENTE_CONFIRMACAO
     *     (ainda em voo), nunca AUTORIZADO/DENEGADO/NUMERO_OCUPADO/CANCELADO (destino já dado);
     *   - {@code nprot} obrigatoriamente nulo — um ciclo com protocolo teve destino real na SEFAZ;
     *   - nenhuma emissão filha de contingência apontando para este ciclo (senão a filha ficaria
     *     órfã ao liberar o gate);
     *   - idempotente: se já está ABANDONADO, não re-marca — mas AINDA repara {@code ultimo_numero}
     *     se ficou atrás do nNF ({@code idempotente=true}, {@code sequenciaAvancada} reflete o
     *     reparo). Nunca é um no-op cego antes de checar o contador.
     *
     * NÃO toca Pedido nem Estoque: o {@code Pedido} de origem continua no status que já tinha
     * (tipicamente REJEITADO) — a decisão de reemitir/descartar o pedido é de quem opera, não
     * deste recovery. NÃO chama a SEFAZ. Preserva {@code cstat}/{@code xmotivo}/{@code nprot} como
     * evidência histórica; preenche {@code resolvido_em}.
     *
     * Ordem canônica de lock: nfe_sequencia -> nfe_emissao (mesma de abrirCiclo/aplicarNovoEstado).
     * REPEATABLE_READ com FOR UPDATE explícito nas duas linhas que precisam ser exclusivas —
     * mesma disciplina de {@code resolverCiclo}.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AbandonoCicloResultado abandonarCiclo(Long emissaoId, String motivo) {
        // Pré-leitura NÃO bloqueante só para localizar cnpj/série (nunca decide a transição).
        NfeEmissao preRead = nfeEmissaoMapper.buscarPorId(emissaoId);
        if (preRead == null) {
            throw BusinessException.emissaoNaoEncontrada(emissaoId);
        }
        NfeSequencia seq = sequenciaService.buscarSeExistirParaAtualizar(
                preRead.getCnpjEmitente(), preRead.getSerie());

        NfeEmissao emissao = nfeEmissaoMapper.buscarPorIdParaAtualizar(emissaoId);
        if (emissao == null) {
            throw BusinessException.emissaoNaoEncontrada(emissaoId);
        }

        // Idempotência: já abandonado -> não re-marca, mas AINDA repara o contador (modelo gap)
        // se ultimo_numero ficou atrás do nNF. Nunca é um no-op cego — Bruno, 02-09-2026.
        if (NfeEmissao.Estados.ABANDONADO.equals(emissao.getEstado())) {
            boolean gateJaLivre = seq == null || !emissaoId.equals(seq.getEmissaoAtivaId());
            boolean sequenciaAvancada = avancarSequenciaParaGap(seq, emissao);
            return AbandonoCicloResultado.idempotente(emissao, gateJaLivre,
                    seq.getUltimoNumero(), sequenciaAvancada);
        }

        if (!NfeEmissao.Estados.AGUARDANDO_CORRECAO.equals(emissao.getEstado())) {
            throw BusinessException.cicloNaoAbandonavel(emissaoId, emissao.getEstado());
        }
        if (emissao.getNprot() != null && !emissao.getNprot().isBlank()) {
            throw BusinessException.cicloComProtocolo(emissaoId);
        }
        NfeEmissao filha = nfeEmissaoMapper.buscarPorOrigemId(emissaoId);
        if (filha != null) {
            throw BusinessException.cicloSubstituido(emissaoId, filha.getId());
        }

        int afetadas = nfeEmissaoMapper.marcarAbandonado(emissaoId);
        if (afetadas != 1) {
            // Já seguramos o FOR UPDATE desta linha; o WHERE de marcarAbandonado
            // (estado='AGUARDANDO_CORRECAO' AND nprot IS NULL) casa com o que acabamos de validar
            // sob lock -> affectedRows != 1 aqui é estado impossível.
            throw new IllegalStateException("marcarAbandonado não afetou nfe_emissao id=" + emissaoId
                    + " (estado sob lock=" + emissao.getEstado() + ", nprot=" + emissao.getNprot()
                    + ") — inconsistência inesperada.");
        }

        // Modelo gap: a linha ABANDONADA ocupa o slot (cnpj,modelo,serie,nNF) para sempre via
        // uk_nfe_emissao_numero. Avança ultimo_numero até o nNF deste ciclo para que o próximo
        // abrirCiclo NÃO recalcule o mesmo candidato e colida no INSERT. Nunca além, nunca regride.
        boolean sequenciaAvancada = avancarSequenciaParaGap(seq, emissao);

        // Libera o gate — só se ele realmente aponta para este ciclo (nunca liberar gate alheio).
        boolean gateLiberado = false;
        if (seq != null && emissaoId.equals(seq.getEmissaoAtivaId())) {
            sequenciaService.liberarGate(emissao.getCnpjEmitente(), emissao.getSerie());
            gateLiberado = true;
        }

        log.warn("[NfeEmissao] Ciclo ABANDONADO | emissaoId={} | pedidoId={} | cnpj={} | serie={} | "
                        + "nNF={} | cstat={} | gateLiberado={} | ultimoNumero={} | sequenciaAvancada={} | motivo=\"{}\"",
                emissaoId, emissao.getPedidoId(), emissao.getCnpjEmitente(), emissao.getSerie(),
                emissao.getNumeroNfe(), emissao.getCstat(), gateLiberado, seq.getUltimoNumero(),
                sequenciaAvancada, motivo);

        return AbandonoCicloResultado.abandonado(emissao, gateLiberado,
                seq.getUltimoNumero(), sequenciaAvancada);
    }

    /**
     * Modelo "gap" dos recovery administrativos (ABANDONADO / TRANSPORTE_NAO_ENTREGUE, 02-09-2026):
     * avança {@code nfe_sequencia.ultimo_numero} até o {@code numero_nfe} deste ciclo encerrado sem
     * autorização — a linha de {@code nfe_emissao} nunca é apagada e ocupa o slot
     * {@code (cnpj_emitente, modelo, serie, numero_nfe)} para sempre via a UNIQUE
     * {@code uk_nfe_emissao_numero}; um {@code abrirCiclo} posterior recalcularia
     * {@code ultimo_numero + 1} e colidiria no INSERT. Nunca regride, nunca ultrapassa o nNF.
     * Idempotente: no-op se {@code ultimo_numero >= numero_nfe}. Mantém o objeto {@code seq} em
     * memória coerente com o UPDATE.
     *
     * @return {@code true} se {@code ultimo_numero} foi avançado nesta chamada.
     */
    private boolean avancarSequenciaParaGap(NfeSequencia seq, NfeEmissao emissao) {
        if (seq == null) {
            // Uma nfe_emissao existente implica que abrirCicloNovo criou a linha de nfe_sequencia
            // (buscarOuCriarParaAtualizar). seq nulo aqui é estado impossível — falha explícita em
            // vez de deixar o gap aberto (reabriria a colisão de uk_nfe_emissao_numero).
            throw new IllegalStateException("nfe_emissao id=" + emissao.getId() + " existe mas não há "
                    + "linha em nfe_sequencia para cnpj=" + emissao.getCnpjEmitente()
                    + " serie=" + emissao.getSerie() + " — impossível reparar o contador (modelo gap).");
        }
        if (seq.getUltimoNumero() >= emissao.getNumeroNfe()) {
            return false;
        }
        boolean avancou = sequenciaService.avancarUltimoNumeroParaRecovery(
                emissao.getCnpjEmitente(), emissao.getSerie(), emissao.getNumeroNfe());
        seq.setUltimoNumero(emissao.getNumeroNfe());
        return avancou;
    }

    /**
     * Recovery administrativo (02-09-2026) — encerra um ciclo em TRANSMITIDO/PENDENTE_CONFIRMACAO
     * cuja tentativa de transmissão foi COMPROVADAMENTE rejeitada no transporte/gateway ANTES de
     * chegar ao autorizador da SEFAZ (ex.: HTTP 403 do proxy por certificado inválido, resposta
     * HTML em vez de SOAP). Nenhuma NF-e existe fiscalmente: sem {@code retEnviNFe}, sem recibo,
     * sem {@code protNFe}, sem {@code nProt}, sem {@code dhRecbto}.
     *
     * Efeito na numeração idêntico ao {@link #abandonarCiclo} (modelo "gap") — encerra o ciclo,
     * libera o gate e AVANÇA {@code nfe_sequencia.ultimo_numero} até o {@code numero_nfe} deste
     * ciclo (ver {@link #avancarSequenciaParaGap}), nunca além, nunca regredindo, porque a linha de
     * {@code nfe_emissao} ocupa o slot {@code uk_nfe_emissao_numero} para sempre. Preenche
     * {@code resolvido_em}, preserva {@code cstat}/{@code xmotivo}/{@code chave} como evidência —
     * mas parte de outro estado e com uma prova de transporte diferente. Além disso, aqui o
     * {@code Pedido} volta para {@code ERRO} (estado emissível) com a {@code chaveNfe} espúria
     * limpa, e a reserva de estoque (se o emit a fez) é desfeita — porque PENDENTE_CONFIRMACAO
     * mantém a reserva até um destino definitivo, e este é o destino: "a NF-e nunca existiu".
     *
     * Guards (todos sob o lock FOR UPDATE de nfe_emissao):
     *   - estado ∈ {TRANSMITIDO, PENDENTE_CONFIRMACAO};
     *   - {@code nprot} nulo;
     *   - {@code tentativas_consulta == 0} — se já houve consulta à SEFAZ, existe um cStat real
     *     que este recovery não pode sobrepor;
     *   - nenhuma linha de {@code nfe_documento} da chave com {@code n_prot}/{@code dh_recbto}/
     *     {@code xml_protocolo} preenchidos (evidência de recepção/processamento pela SEFAZ);
     *   - nenhuma emissão filha de contingência;
     *   - idempotente: se já está TRANSPORTE_NAO_ENTREGUE, não re-marca — mas AINDA repara
     *     {@code ultimo_numero} se ficou atrás do nNF ({@code idempotente=true},
     *     {@code sequenciaAvancada} reflete o reparo). Nunca é no-op cego antes de checar o contador.
     *
     * NÃO chama a SEFAZ. NÃO toca outra série/emissão. Nunca inventa DENEGADO nem apaga evidência.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public TransporteNaoEntregueResultado marcarTransporteNaoEntregue(Long emissaoId, String motivo) {
        NfeEmissao preRead = nfeEmissaoMapper.buscarPorId(emissaoId);
        if (preRead == null) {
            throw BusinessException.emissaoNaoEncontrada(emissaoId);
        }
        NfeSequencia seq = sequenciaService.buscarSeExistirParaAtualizar(
                preRead.getCnpjEmitente(), preRead.getSerie());

        NfeEmissao emissao = nfeEmissaoMapper.buscarPorIdParaAtualizar(emissaoId);
        if (emissao == null) {
            throw BusinessException.emissaoNaoEncontrada(emissaoId);
        }

        // Idempotência: já marcado -> não re-marca, mas AINDA repara o contador (modelo gap) se
        // ultimo_numero ficou atrás do nNF. Nunca é um no-op cego — Bruno, 02-09-2026.
        if (NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE.equals(emissao.getEstado())) {
            boolean gateJaLivre = seq == null || !emissaoId.equals(seq.getEmissaoAtivaId());
            boolean sequenciaAvancada = avancarSequenciaParaGap(seq, emissao);
            return TransporteNaoEntregueResultado.idempotente(emissao, gateJaLivre,
                    seq.getUltimoNumero(), sequenciaAvancada);
        }

        if (!NfeEmissao.Estados.TRANSMITIDO.equals(emissao.getEstado())
                && !NfeEmissao.Estados.PENDENTE_CONFIRMACAO.equals(emissao.getEstado())) {
            throw BusinessException.cicloNaoElegivelTransporte(emissaoId, emissao.getEstado());
        }
        if (emissao.getNprot() != null && !emissao.getNprot().isBlank()) {
            throw BusinessException.cicloComProtocolo(emissaoId);
        }
        if (emissao.getTentativasConsulta() > 0) {
            throw BusinessException.cicloJaReconciliado(emissaoId, emissao.getTentativasConsulta());
        }
        NfeEmissao filha = nfeEmissaoMapper.buscarPorOrigemId(emissaoId);
        if (filha != null) {
            throw BusinessException.cicloSubstituido(emissaoId, filha.getId());
        }
        if (emissao.getChaveNfe() != null && !emissao.getChaveNfe().isBlank()) {
            Optional<NfeDocumento> doc = nfeDocumentoMapper.findByChave(emissao.getChaveNfe());
            if (doc.isPresent()) {
                NfeDocumento d = doc.get();
                boolean temEvidencia = (d.getNProt() != null && !d.getNProt().isBlank())
                        || d.getDhRecbto() != null
                        || (d.getXmlProtocolo() != null && !d.getXmlProtocolo().isBlank());
                if (temEvidencia) {
                    throw BusinessException.evidenciaDeProcessamento(emissaoId, emissao.getChaveNfe());
                }
            }
        }

        int afetadas = nfeEmissaoMapper.marcarTransporteNaoEntregue(emissaoId);
        if (afetadas != 1) {
            throw new IllegalStateException("marcarTransporteNaoEntregue não afetou nfe_emissao id="
                    + emissaoId + " (estado sob lock=" + emissao.getEstado() + ", nprot=" + emissao.getNprot()
                    + ", tentativasConsulta=" + emissao.getTentativasConsulta() + ") — inconsistência inesperada.");
        }

        // Modelo gap (igual ao abandono): a linha ocupa (cnpj,modelo,serie,nNF) para sempre via
        // uk_nfe_emissao_numero — avança ultimo_numero até o nNF deste ciclo. Nunca além, nunca regride.
        boolean sequenciaAvancada = avancarSequenciaParaGap(seq, emissao);

        boolean gateLiberado = false;
        if (seq != null && emissaoId.equals(seq.getEmissaoAtivaId())) {
            sequenciaService.liberarGate(emissao.getCnpjEmitente(), emissao.getSerie());
            gateLiberado = true;
        }

        Long pedidoId = emissao.getPedidoId();
        Pedido pedido = pedidoMapper.buscarPorId(pedidoId);
        boolean estoqueDesfeito = false;
        if (pedido != null) {
            pedidoMapper.atualizarStatus(pedidoId, "ERRO", null);
            Long empresaAncoraId = pedido.getEmpresaId();
            Empresa ancora = empresaAncoraId != null ? empresaMapper.buscarPorId(empresaAncoraId) : null;
            boolean controlaEstoque = ancora == null || ancora.controlaEstoque();
            if (empresaAncoraId != null && controlaEstoque) {
                List<PedidoItem> itens = pedidoItemMapper.listarPorPedido(pedidoId);
                estoqueService.desfazerReservaItens(itens, empresaAncoraId, pedidoId, "sistema-recovery-transporte");
                estoqueDesfeito = true;
            }
        }

        log.warn("[NfeEmissao] Ciclo TRANSPORTE_NAO_ENTREGUE | emissaoId={} | pedidoId={} | cnpj={} | "
                        + "serie={} | nNF={} | cstatSintetico={} | gateLiberado={} | ultimoNumero={} | "
                        + "sequenciaAvancada={} | pedido->ERRO | estoqueDesfeito={} | motivo=\"{}\"",
                emissaoId, pedidoId, emissao.getCnpjEmitente(), emissao.getSerie(), emissao.getNumeroNfe(),
                emissao.getCstat(), gateLiberado, seq.getUltimoNumero(), sequenciaAvancada,
                estoqueDesfeito, motivo);

        return TransporteNaoEntregueResultado.aplicado(emissao, gateLiberado,
                seq.getUltimoNumero(), sequenciaAvancada, estoqueDesfeito);
    }

    /**
     * Claim atômico da janela de reconciliação (Gate 3) — UPDATE condicional único (ver
     * NfeEmissaoMapper.tentarAdquirirJanelaConsulta), nunca lock explícito + leitura + decisão.
     * Transação própria, curta, sem nenhuma chamada de rede dentro dela — a Consulta Situação só
     * acontece depois que esta transação já commitou, do lado de fora de qualquer @Transactional.
     */
    @Transactional
    public boolean tentarAdquirirJanelaConsulta(Long emissaoId) {
        int affected = nfeEmissaoMapper.tentarAdquirirJanelaConsulta(
                emissaoId,
                LocalDateTime.now(),
                reconciliacaoProperties.getBackoffInicialSegundos(),
                reconciliacaoProperties.getBackoffMultiplicador(),
                reconciliacaoProperties.getBackoffMaximoSegundos());
        return affected == 1;
    }

    private ResultadoAplicacao aplicarNovoEstado(Long emissaoId, String novoEstado, Integer cStat, String xMotivo, String nProt) {
        // ABANDONADO e TRANSPORTE_NAO_ENTREGUE nunca são destino de resolução de ciclo
        // (SEFAZ/reconciliação): só são alcançáveis pelos recovery administrativos dedicados, que
        // têm sua própria transação e guards. Barrar aqui impede que uma futura chamada de
        // resolverCiclo passe um desses por engano — não consomem número, então cairiam no ramo
        // não-terminal e deixariam o gate preso.
        if (NfeEmissao.Estados.ABANDONADO.equals(novoEstado)) {
            throw new IllegalArgumentException(
                    "ABANDONADO não é um estado de resolução de ciclo — use abandonarCiclo().");
        }
        if (NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE.equals(novoEstado)) {
            throw new IllegalArgumentException(
                    "TRANSPORTE_NAO_ENTREGUE não é um estado de resolução de ciclo — use "
                            + "marcarTransporteNaoEntregue().");
        }
        // Ordem canônica de lock do projeto: Empresa -> nfe_sequencia -> nfe_emissao (mesma de
        // abrirCiclo/retomarCicloAtivo e de FiscalNumberingService). Este método recebe só o id
        // da emissão, então precisa descobrir cnpj/série ANTES de travar nfe_sequencia — mas essa
        // descoberta nunca pode decidir a transição em si (fonte de TOCTOU). Por isso: pré-leitura
        // NÃO bloqueante só para localizar cnpj/série -> trava nfe_sequencia -> só então trava
        // nfe_emissao -> revalida tudo contra as linhas já travadas -> aplica.
        boolean terminal = NfeEmissao.Estados.isTerminal(novoEstado);
        NfeSequencia seq = null;

        if (terminal) {
            // Só precisa travar nfe_sequencia quando a transição vai de fato consumir número/
            // liberar o gate — terminal já é conhecido aqui porque vem do parâmetro novoEstado,
            // nunca de uma leitura de banco, então essa decisão não é TOCTOU-prone.
            NfeEmissao preRead = nfeEmissaoMapper.buscarPorId(emissaoId);
            if (preRead == null) {
                throw new IllegalStateException("nfe_emissao id=" + emissaoId + " não encontrada ao resolver ciclo.");
            }
            seq = sequenciaService.buscarSeExistirParaAtualizar(preRead.getCnpjEmitente(), preRead.getSerie());
        }

        NfeEmissao emissao = nfeEmissaoMapper.buscarPorIdParaAtualizar(emissaoId);
        if (emissao == null) {
            throw new IllegalStateException("nfe_emissao id=" + emissaoId + " não encontrada ao resolver ciclo.");
        }

        // Fase 1 SVC (17-08-2026), fast-path (banca 17-08-2026, achado de code-review confirmado
        // contra MySQL real — gap lock em uk_nfe_emissao_origem: X no supremum + INSERT_INTENTION
        // concorrente WAITING): emissao_ativa_id NUNCA vira fonte de verdade — só um atalho seguro
        // enquanto nfe_sequencia já está travada por outro motivo (transição terminal). Sob esse
        // MESMO lock, se o gate ainda aponta pra esta emissão, uma substituição (Caminho B) já
        // commitada não pode existir: abrirContingencia precisa desse mesmo lock, na mesma ordem
        // canônica, ANTES de inserir a filha e mover o gate — então "gate == esta emissão" e
        // "filha já existe" são mutuamente exclusivos no instante em que travamos. Só pulamos a
        // busca por emissao_origem_id (a que toma o gap lock) neste caso; em qualquer outro —
        // gate apontando pra outro lugar, gate NULL, ou transição não-terminal (P0-3 continua
        // nunca travando nfe_sequencia aqui, de propósito, pra não reintroduzir a serialização que
        // aquela correção existia pra eliminar) — a busca por emissao_origem_id roda exatamente
        // como antes. emissao_origem_id continua a única prova durável; isto não a substitui.
        boolean gateAindaNestaEmissao = terminal && seq != null && emissaoId.equals(seq.getEmissaoAtivaId());

        if (!gateAindaNestaEmissao) {
            NfeEmissao filha = nfeEmissaoMapper.buscarPorOrigemIdParaAtualizar(emissaoId);
            if (filha != null) {
                int affected = nfeEmissaoMapper.aplicarEvidenciaSubstituida(emissaoId, novoEstado, cStat, xMotivo, nProt);
                if (affected == 1) {
                    return ResultadoAplicacao.APLICADO_EVIDENCIA_SUBSTITUIDA;
                }
                // affected == 0: já havia evidência gravada (2ª resposta tardia, ex. retry
                // duplicado). Idempotência por TUPLA FISCAL COMPLETA, null-safe — mesmo estado
                // sozinho não prova mesma evidência (AUTORIZADO/100/nProt=X != AUTORIZADO/100/nProt=Y).
                //
                // Achado de code-review (17-08-2026, 2ª rodada, CONFIRMED): a versão anterior fazia
                // um SEGUNDO SELECT não-bloqueante aqui (buscarPorId) pra ler a "gravada" -- sob
                // REPEATABLE_READ, esse SELECT reaproveitaria o MESMO snapshot consistente já
                // estabelecido pela 1ª leitura não-bloqueante da transação (preRead, logo acima),
                // podendo devolver dado anterior ao commit de quem gravou a evidência primeiro,
                // mesmo já sabendo (via affected==0) que existe evidência mais nova. `emissao`
                // (já obtida via FOR UPDATE alguns passos acima) é a resposta certa: leitura COM
                // LOCK sempre vê o último dado commitado, nunca o snapshot -- e como seguramos essa
                // trava continuamente desde então, ninguém mais pôde tê-la alterado nesse meio-tempo.
                // Reaproveitar em vez de reler elimina o bug e a viagem extra ao banco.
                boolean identica = Objects.equals(emissao.getEstado(), novoEstado)
                        && Objects.equals(emissao.getCstat(), cStat)
                        && Objects.equals(emissao.getXmotivo(), xMotivo)
                        && Objects.equals(emissao.getNprot(), nProt);
                if (!identica) {
                    // Nunca decide sozinho qual versão vale, nunca sobrescreve — só torna a
                    // divergência visível (mesmo padrão de registrarEscalonamentoSeNecessario em
                    // NfeReconciliacaoService).
                    log.warn("[NfeEmissao] Evidência divergente para NORMAL substituída id={} -- gravada="
                                    + "estado={}/cstat={}/xmotivo={}/nprot={}, tentada=estado={}/cstat={}/xmotivo={}/nprot={} "
                                    + "-- não sobrescrita, requer revisão manual.",
                            emissaoId, emissao.getEstado(), emissao.getCstat(), emissao.getXmotivo(), emissao.getNprot(),
                            novoEstado, cStat, xMotivo, nProt);
                }
                return ResultadoAplicacao.NAO_APLICADO_EVIDENCIA_JA_REGISTRADA;
            }
        }

        if (NfeEmissao.Estados.encerraCiclo(emissao.getEstado())) {
            // Idempotência: decidida pelo estado já travado (a fonte válida), nunca pela
            // pré-leitura de cima — protege contra uma segunda resolução do mesmo ciclo (Gate 3:
            // reconciliação pode ser chamada mais de uma vez). Sinaliza ao chamador
            // (resolverCicloComEfeitos) que os efeitos de Pedido/Estoque já foram aplicados antes,
            // na transação que resolveu este ciclo pela primeira vez — nunca reaplicar.
            // encerraCiclo (não isTerminal): um ciclo já ABANDONADO também é no-op aqui — uma
            // resposta tardia da SEFAZ nunca ressuscita um ciclo que o operador encerrou.
            return ResultadoAplicacao.NAO_APLICADO_JA_TERMINAL;
        }

        if (terminal) {
            // Revalidação pós-lock: cnpj_emitente/serie de nfe_emissao são imutáveis após o
            // INSERT (nenhum UPDATE do projeto os altera), então esta divergência nunca deveria
            // ocorrer — falha explícita em vez de seguir com uma sequência errada.
            if (seq == null || !seq.getCnpjEmitente().equals(emissao.getCnpjEmitente())
                    || !seq.getSerie().equals(emissao.getSerie())) {
                throw new IllegalStateException("nfe_sequencia travada (cnpj=" + (seq != null ? seq.getCnpjEmitente() : null)
                        + ", serie=" + (seq != null ? seq.getSerie() : null) + ") não corresponde a nfe_emissao id="
                        + emissaoId + " (cnpj=" + emissao.getCnpjEmitente() + ", serie=" + emissao.getSerie() + ").");
            }
            // Gate inconsistente: o gate da série precisa apontar exatamente para esta emissão
            // antes de consumir número ou liberar — nunca aplicar o efeito de um ciclo alheio.
            if (seq.getEmissaoAtivaId() == null || !seq.getEmissaoAtivaId().equals(emissaoId)) {
                throw new IllegalStateException("Gate fiscal de CNPJ=" + emissao.getCnpjEmitente() + " série="
                        + emissao.getSerie() + " aponta para emissaoAtivaId=" + seq.getEmissaoAtivaId()
                        + ", não para id=" + emissaoId + " — recusando consumir número/liberar gate de outro ciclo.");
            }
        }

        emissao.setEstado(novoEstado);
        emissao.setCstat(cStat);
        emissao.setXmotivo(xMotivo);
        emissao.setNprot(nProt);
        emissao.setResolvidoEm(terminal ? LocalDateTime.now() : null);
        nfeEmissaoMapper.atualizarResultado(emissao);

        if (terminal) {
            sequenciaService.consumirNumero(emissao.getCnpjEmitente(), emissao.getSerie(), emissao.getNumeroNfe());
            sequenciaService.liberarGate(emissao.getCnpjEmitente(), emissao.getSerie());
        }
        return ResultadoAplicacao.APLICADO_NORMAL;
    }
}
