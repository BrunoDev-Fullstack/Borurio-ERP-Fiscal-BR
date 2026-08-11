package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.service.NfeConsultaSituacaoService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

/**
 * Orquestra a reconciliação de um ciclo TRANSMITIDO/PENDENTE_CONFIRMACAO (Gate 3, 10-08-2026) —
 * nunca retransmite, nunca gera chave/número novo enquanto o ciclo anterior não tiver destino
 * definitivo. Chamado exclusivamente por {@link PedidoEmissaoService} quando um pedido em
 * "AGUARDANDO" tenta reemitir e existe um ciclo fiscal correspondente ainda incerto.
 *
 * Sequência (nunca mantém transação de banco aberta durante I/O de rede):
 *   1. claim atômico da janela de backoff (transação curta, própria — {@link NfeEmissaoService#tentarAdquirirJanelaConsulta})
 *   2. local-first: nfe_documento resolve sem tocar a rede quando as 3 condições se cumprem
 *   3. só se local-first não resolver: Consulta Situação SEFAZ (fora de qualquer transação)
 *   4. classificação do resultado
 *   5. se terminal (AUTORIZADO/AGUARDANDO_CORRECAO/NUMERO_OCUPADO): finalização atômica única
 *      ({@link NfeEmissaoService#resolverCicloComEfeitos}); se ainda inconclusivo: nenhuma
 *      escrita adicional além do claim já commitado no passo 1.
 */
@Service
public class NfeReconciliacaoService {

    private static final Logger log = LoggerFactory.getLogger(NfeReconciliacaoService.class);

    // cStat definitivos o suficiente para confiar em nfe_documento sem consultar a SEFAZ de novo
    // — mesmo conjunto que o Gate 2 já classifica como terminal-capaz na emissão original.
    private static final Set<Integer> CSTAT_LOCAL_AUTORIZADO = Set.of(100, 150);
    private static final Set<Integer> CSTAT_LOCAL_AGUARDANDO_CORRECAO = Set.of(225, 302, 303);
    private static final Set<Integer> CSTAT_LOCAL_NUMERO_OCUPADO = Set.of(205, 206, 218);

    private final NfeEmissaoService nfeEmissaoService;
    private final NfeDocumentoService documentoService;
    private final NfeConsultaSituacaoService consultaSituacaoService;
    private final SefazReconciliacaoProperties reconciliacaoProperties;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public NfeReconciliacaoService(NfeEmissaoService nfeEmissaoService,
                                    NfeDocumentoService documentoService,
                                    NfeConsultaSituacaoService consultaSituacaoService,
                                    SefazReconciliacaoProperties reconciliacaoProperties) {
        this.nfeEmissaoService = nfeEmissaoService;
        this.documentoService = documentoService;
        this.consultaSituacaoService = consultaSituacaoService;
        this.reconciliacaoProperties = reconciliacaoProperties;
    }

    /**
     * Nunca retorna "sucesso silencioso" — todo desfecho vira uma BusinessException, para nunca
     * ser confundido com uma emissão nova real:
     *   pendente (claim perdido/backoff/217/635/falha de transporte/falha de parse) -> EMISSAO_AGUARDANDO_RECONCILIACAO (409, retryable)
     *   AGUARDANDO_CORRECAO (achado local ou via consulta)                          -> SEFAZ_REJECTED (422)
     *   NUMERO_OCUPADO                                                              -> NUMERO_FISCAL_OCUPADO (409, retryable — próxima tentativa abre ciclo novo)
     * Só retorna normalmente quando reconcilia como AUTORIZADO — chamador monta a resposta de
     * sucesso a partir da chave já congelada.
     */
    public void reconciliar(Pedido pedido, NfeEmissao emissao, Empresa empresa, boolean controlaEstoque) {
        Long pedidoId = pedido.getId();

        if (!nfeEmissaoService.tentarAdquirirJanelaConsulta(emissao.getId())) {
            // Backoff ainda não venceu, OU outra chamada concorrente já está reconciliando, OU o
            // ciclo já não está mais pendente — em qualquer caso, nunca toca a rede.
            throw BusinessException.emissaoAguardandoReconciliacao(pedidoId);
        }

        registrarEscalonamentoSeNecessario(emissao);

        Decisao decisao = tentarResolverLocalmente(emissao);
        if (decisao == null) {
            decisao = consultarSefazEClassificar(emissao, empresa);
        }

        if (decisao.pendente()) {
            throw BusinessException.emissaoAguardandoReconciliacao(pedidoId);
        }

        String novoStatusPedido = PedidoEmissaoService.mapearStatusPedido(decisao.estado());
        String chaveParaPedido = decisao.chaveConfirmada() != null ? decisao.chaveConfirmada() : emissao.getChaveNfe();
        nfeEmissaoService.resolverCicloComEfeitos(emissao.getId(), decisao.estado(), decisao.cStat(), decisao.xMotivo(), decisao.nProt(),
                pedidoId, novoStatusPedido, chaveParaPedido,
                controlaEstoque, pedido.getItens(), pedido.getEmpresaId(), "sistema-reconciliacao");

        if (NfeEmissao.Estados.NUMERO_OCUPADO.equals(decisao.estado())) {
            throw BusinessException.numeroFiscalOcupado(pedidoId, decisao.cStat(), decisao.xMotivo());
        }
        if (NfeEmissao.Estados.AGUARDANDO_CORRECAO.equals(decisao.estado())) {
            throw BusinessException.sefazRejected(decisao.cStat() != null ? decisao.cStat() : -1, decisao.xMotivo());
        }
        // AUTORIZADO: retorna normalmente.
    }

    // -------------------------------------------------------------------------
    // Local-first — nfe_documento como evidência persistida, nunca como verdade que sobrepõe
    // a situação fiscal sem as 3 condições comprovadas.
    // -------------------------------------------------------------------------

    private Decisao tentarResolverLocalmente(NfeEmissao emissao) {
        if (emissao.getChaveNfe() == null) {
            return null; // sem chave congelada, nada a comparar localmente
        }
        Optional<NfeDocumento> docOpt = documentoService.buscarPorChave(emissao.getChaveNfe());
        if (docOpt.isEmpty()) {
            return null;
        }
        NfeDocumento doc = docOpt.get();

        // Condição 1: chave == chave congelada (buscarPorChave já filtra pela chave, mas nunca
        // confia em coincidência de índice sem confirmar explicitamente).
        if (!emissao.getChaveNfe().equals(doc.getChaveNfe())) {
            return null;
        }

        Integer cStat = parseIntSeguro(doc.getCStat());
        if (cStat == null) {
            return null; // Condição 2: cStat precisa ser um valor definitivo e legível.
        }

        if (CSTAT_LOCAL_AUTORIZADO.contains(cStat)) {
            // Condição 3: protocolo coerente quando exigido — autorização sem nProt não é confiável.
            if (doc.getNProt() == null || doc.getNProt().isBlank()) {
                return null;
            }
            log.info("[NfeReconciliacao] Resolvido localmente via nfe_documento (autorizado) | chave={} | cStat={}",
                    emissao.getChaveNfe(), cStat);
            return Decisao.autorizado(cStat, doc.getXMotivo(), doc.getNProt(), doc.getChaveNfe());
        }
        if (CSTAT_LOCAL_AGUARDANDO_CORRECAO.contains(cStat)) {
            log.info("[NfeReconciliacao] Resolvido localmente via nfe_documento (aguardando correção) | chave={} | cStat={}",
                    emissao.getChaveNfe(), cStat);
            return Decisao.aguardandoCorrecao(cStat, doc.getXMotivo());
        }
        if (CSTAT_LOCAL_NUMERO_OCUPADO.contains(cStat)) {
            log.info("[NfeReconciliacao] Conflito resolvido localmente via nfe_documento | chave={} | cStat={}",
                    emissao.getChaveNfe(), cStat);
            return Decisao.numeroOcupado(cStat, doc.getXMotivo());
        }
        // Qualquer outro cStat (204/217/301/539/635/103-106/110/etc) não é definitivo o
        // suficiente localmente — consulta a SEFAZ.
        return null;
    }

    // -------------------------------------------------------------------------
    // Consulta Situação SEFAZ — só chamada quando local-first não resolveu.
    // -------------------------------------------------------------------------

    private Decisao consultarSefazEClassificar(NfeEmissao emissao, Empresa empresa) {
        if (emissao.getChaveNfe() == null) {
            // Sem chave congelada não deveria acontecer para um ciclo TRANSMITIDO/
            // PENDENTE_CONFIRMACAO (marcarTransmitido sempre grava a chave antes desses estados
            // existirem) — fail-safe conservador: sem chave não há o que consultar.
            return Decisao.aindaPendente();
        }
        String uf = empresa != null && empresa.getUf() != null ? empresa.getUf() : "SP";
        NfeConsultaSituacaoRetorno retorno;
        try {
            retorno = consultaSituacaoService.consultar(emissao.getChaveNfe(), uf, tpAmb);
        } catch (SefazTransmissaoIncertaException e) {
            log.warn("[NfeReconciliacao] Falha de transporte na Consulta Situação | chave={} | erro={}",
                    emissao.getChaveNfe(), e.getMessage());
            return Decisao.aindaPendente();
        }
        if (retorno.isFalhaParse()) {
            log.warn("[NfeReconciliacao] Falha ao interpretar resposta da Consulta Situação | chave={} | detalhe={}",
                    emissao.getChaveNfe(), retorno.getDetalheFalhaParse());
            return Decisao.aindaPendente();
        }
        return classificarRetornoConsulta(emissao, retorno);
    }

    private Decisao classificarRetornoConsulta(NfeEmissao emissao, NfeConsultaSituacaoRetorno retorno) {
        int cStat = retorno.getCStat();

        if ((cStat == 100 || cStat == 150) && retorno.isAutorizadaComProtocolo()) {
            if (!emissao.getChaveNfe().equals(retorno.getChNFe())) {
                // Nunca aceitar autorização de chave diferente da congelada — proteção central
                // contra autorizar o pedido errado por qualquer divergência de parsing/roteamento.
                log.warn("[NfeReconciliacao] cStat autorizado mas chNFe divergente — ignorado | esperado={} | recebido={}",
                        emissao.getChaveNfe(), retorno.getChNFe());
                return Decisao.aindaPendente();
            }
            return Decisao.autorizado(cStat, retorno.getXMotivo(), retorno.getNProt(), retorno.getChNFe());
        }
        if (cStat == 225 || cStat == 302 || cStat == 303) {
            return Decisao.aguardandoCorrecao(cStat, retorno.getXMotivo());
        }
        if (cStat == 205 || cStat == 206 || cStat == 218) {
            return Decisao.numeroOcupado(cStat, retorno.getXMotivo());
        }
        if (cStat == 539) {
            // Duplicidade com chave divergente: só confirma conflito se a consulta trouxer uma
            // chNFe efetivamente diferente da congelada. Igual (raça a favor) ou ausente
            // continua inconclusivo — nunca decide sozinho.
            if (retorno.getChNFe() != null && !retorno.getChNFe().equals(emissao.getChaveNfe())) {
                return Decisao.numeroOcupado(cStat, retorno.getXMotivo());
            }
            return Decisao.aindaPendente();
        }
        // 204 (sem protocolo confiável para a própria chave), 217, 635, 110, 301, 103-106, ou
        // qualquer cStat não listado — todos permanecem PENDENTE_CONFIRMACAO. Nunca decide por omissão.
        return Decisao.aindaPendente();
    }

    // -------------------------------------------------------------------------
    // Escalonamento operacional — nunca decide sozinho, só registra a necessidade de intervenção.
    // -------------------------------------------------------------------------

    private void registrarEscalonamentoSeNecessario(NfeEmissao emissao) {
        int tentativas = emissao.getTentativasConsulta() + 1; // aproximação do valor pós-claim
        LocalDateTime referencia = emissao.getTransmitidoEm() != null ? emissao.getTransmitidoEm() : emissao.getCreatedAt();
        long idadeMinutos = referencia != null ? ChronoUnit.MINUTES.between(referencia, LocalDateTime.now()) : 0;

        if (tentativas >= reconciliacaoProperties.getLimiteTentativas()
                || idadeMinutos >= reconciliacaoProperties.getIdadeMaximaMinutos()) {
            log.warn("[NfeReconciliacao] Ciclo pendente excede janela operacional — necessita intervenção manual | "
                            + "emissaoId={} | pedidoId={} | tentativas={} | idadeMinutos={} | chave={}",
                    emissao.getId(), emissao.getPedidoId(), tentativas, idadeMinutos, emissao.getChaveNfe());
        }
    }

    private Integer parseIntSeguro(String valor) {
        if (valor == null || valor.isBlank()) return null;
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Decisao(boolean pendente, String estado, Integer cStat, String xMotivo,
                            String nProt, String chaveConfirmada) {
        static Decisao aindaPendente() {
            return new Decisao(true, null, null, null, null, null);
        }

        static Decisao autorizado(int cStat, String xMotivo, String nProt, String chave) {
            return new Decisao(false, NfeEmissao.Estados.AUTORIZADO, cStat, xMotivo, nProt, chave);
        }

        static Decisao aguardandoCorrecao(int cStat, String xMotivo) {
            return new Decisao(false, NfeEmissao.Estados.AGUARDANDO_CORRECAO, cStat, xMotivo, null, null);
        }

        static Decisao numeroOcupado(int cStat, String xMotivo) {
            return new Decisao(false, NfeEmissao.Estados.NUMERO_OCUPADO, cStat, xMotivo, null, null);
        }
    }
}
