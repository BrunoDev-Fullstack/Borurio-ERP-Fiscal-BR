package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.web.dto.AberturaCicloResultado;
import br.com.borurio.web.dto.TipoAberturaCiclo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    private final EmpresaMapper empresaMapper;
    private final PedidoMapper pedidoMapper;
    private final NfeSequenciaService sequenciaService;
    private final NfeEmissaoMapper nfeEmissaoMapper;
    private final EstoqueService estoqueService;
    private final SefazReconciliacaoProperties reconciliacaoProperties;

    public NfeEmissaoService(EmpresaMapper empresaMapper, PedidoMapper pedidoMapper,
                              NfeSequenciaService sequenciaService, NfeEmissaoMapper nfeEmissaoMapper,
                              EstoqueService estoqueService, SefazReconciliacaoProperties reconciliacaoProperties) {
        this.empresaMapper = empresaMapper;
        this.pedidoMapper = pedidoMapper;
        this.sequenciaService = sequenciaService;
        this.nfeEmissaoMapper = nfeEmissaoMapper;
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
     * reconciliação concorrente), {@link #aplicarNovoEstado} devolve {@code false} e este método
     * para imediatamente, sem tocar Pedido/Estoque — os efeitos já foram aplicados juntos, na
     * mesma transação, pela chamada que venceu a primeira vez. Nunca é possível aplicar o efeito
     * fiscal sem o efeito operacional, nem vice-versa.
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
        boolean aplicado = aplicarNovoEstado(emissaoId, novoEstado, cStat, xMotivo, nProt);
        if (!aplicado) {
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

    // Retorna true se o novo estado foi de fato aplicado nesta chamada; false se o ciclo já
    // estava terminal (no-op idempotente).
    private boolean aplicarNovoEstado(Long emissaoId, String novoEstado, Integer cStat, String xMotivo, String nProt) {
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
        if (NfeEmissao.Estados.isTerminal(emissao.getEstado())) {
            // Idempotência: decidida pelo estado já travado (a fonte válida), nunca pela
            // pré-leitura de cima — protege contra uma segunda resolução do mesmo ciclo (Gate 3:
            // reconciliação pode ser chamada mais de uma vez). false sinaliza ao chamador
            // (resolverCicloComEfeitos) que os efeitos de Pedido/Estoque já foram aplicados antes,
            // na transação que resolveu este ciclo pela primeira vez — nunca reaplicar.
            return false;
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
        return true;
    }
}
