package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.dto.NfeCceRequest;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operações fiscais vinculadas ao pedido: consulta situação, cancelamento, CC-e.
 * Requer que o pedido tenha chaveNfe preenchida (status AUTORIZADO ou AGUARDANDO).
 *
 * Toda operação resolve a empresa e o certificado corretos via FiscalContextoResolver a
 * partir do próprio pedido — nunca usa configuração global. Isso garante que cancelamento,
 * CC-e e consulta de uma NF-e emitida por um CNPJ nunca usem, nem por engano, o certificado
 * ou o contexto de outro CNPJ do mesmo cliente OMS.
 */
@Service
public class PedidoOperacaoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoOperacaoService.class);

    private final PedidoService pedidoService;
    private final NfeDocumentoService documentoService;
    private final NfeCancelamentoOrquestradorService cancelamentoOrquestradorService;
    private final NfeEventoService nfeEventoService;
    private final NfeCceService cceService;
    private final EstoqueService estoqueService;
    private final EmpresaMapper empresaMapper;
    private final FiscalContextoResolver contextoResolver;
    private final NfeEmissaoService nfeEmissaoService;

    public PedidoOperacaoService(PedidoService pedidoService,
                                  NfeDocumentoService documentoService,
                                  NfeCancelamentoOrquestradorService cancelamentoOrquestradorService,
                                  NfeEventoService nfeEventoService,
                                  NfeCceService cceService,
                                  EstoqueService estoqueService,
                                  EmpresaMapper empresaMapper,
                                  FiscalContextoResolver contextoResolver,
                                  NfeEmissaoService nfeEmissaoService) {
        this.pedidoService     = pedidoService;
        this.documentoService  = documentoService;
        this.cancelamentoOrquestradorService = cancelamentoOrquestradorService;
        this.nfeEventoService  = nfeEventoService;
        this.cceService        = cceService;
        this.estoqueService    = estoqueService;
        this.empresaMapper     = empresaMapper;
        this.contextoResolver  = contextoResolver;
        this.nfeEmissaoService = nfeEmissaoService;
    }

    // -------------------------------------------------------------------------
    // CONSULTA SITUAÇÃO
    // Leitura pura do estado persistido — NUNCA consulta a SEFAZ ao vivo (ver nota de segurança
    // abaixo). nfe_emissao tem precedência absoluta sempre que existe; nfe_documento só é usado
    // como exceção legada para pedidos emitidos antes do Gate 1 (07-08-2026).
    // -------------------------------------------------------------------------

    public Map<String, Object> consultarSituacao(Long pedidoId) throws Exception {
        // P0-2 (07-08-2026, hardening pós-banca) — mesma fronteira de isolamento de emitir().
        Pedido pedido = pedidoService.buscarPorIdDoTenanteAtual(pedidoId);

        // Gate de contrato OMS (11-08-2026): busca nfe_emissao ANTES de decidir a chave — nunca
        // valida/usa Pedido.chaveNfe isoladamente primeiro. nfe_emissao.chaveNfe é congelada em
        // marcarTransmitido(), ANTES da chamada à SEFAZ; Pedido.chaveNfe só é gravada depois, em
        // resolverCicloComEfeitos()/atualizarStatus(). Isso significa que numa falha de rede na
        // PRIMEIRA tentativa de emissão (timeout/indisponibilidade), PedidoEmissaoService.emitir()
        // cai no catch (linha ~236: `pedidoService.atualizarStatus(pedidoId, "ERRO",
        // pedido.getChaveNfe())`, usando o valor ANTERIOR — null na primeira tentativa) e resolve o
        // ciclo via `resolverCiclo` (não `resolverCicloComEfeitos`, que não toca em Pedido) — o
        // resultado real e comprovado é: nfe_emissao com chaveNfe congelada e estado
        // PENDENTE_CONFIRMACAO, mas Pedido.chaveNfe continua null. Validar só Pedido.chaveNfe
        // bloquearia /situacao exatamente no cenário em que a OMS mais precisa fazer polling — ver
        // teste consultarSituacao_pedidoChaveNulaComEmissaoCongelada_funcionaViaNfeEmissao.
        NfeEmissao emissao = nfeEmissaoService.buscarUltimaEmissaoDoPedido(pedidoId);

        String chaveEmissao = emissao != null ? emissao.getChaveNfe() : null;
        String chavePedido  = pedido.getChaveNfe();
        if (chavePedido != null && chaveEmissao != null && !chavePedido.equals(chaveEmissao)) {
            // Nas duas transações que gravam as duas colunas (marcarTransmitido / resolverCicloCom
            // Efeitos), o mesmo valor é usado para as duas — nunca deveriam divergir quando ambas
            // existem. Se divergirem, é inconsistência de dado real: nunca decide silenciosamente
            // qual fonte "vence", só torna o problema visível (fail-safe).
            log.error("[PedidoOperacao] Divergência chaveNfe entre Pedido e nfe_emissao — "
                            + "pedidoId={} | pedido.chaveNfe={} | nfe_emissao.chaveNfe={}",
                    pedidoId, chavePedido, chaveEmissao);
        }

        // Chave fiscal efetiva: nfe_emissao tem precedência absoluta sempre que existe (é a fonte
        // do ciclo fiscal); Pedido.chaveNfe só é usada no fallback legado (sem nenhum ciclo em
        // nfe_emissao — pedido emitido antes do Gate 1, 07-08-2026). A MESMA variável `chave` é
        // usada daqui em diante para resposta, validação de CNPJ e busca em nfe_documento — nunca
        // uma fonte para uma coisa e outra fonte para outra dentro do mesmo ramo.
        String chave = chaveEmissao != null ? chaveEmissao : chavePedido;
        if (chave == null || chave.isBlank()) {
            throw new IllegalStateException(
                    "Pedido " + pedidoId + " não possui chave de NF-e. Execute /emitir primeiro.");
        }
        validarCnpjDocumento(chave, pedido);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pedidoId", pedidoId);
        resp.put("numero",   pedido.getNumero());
        resp.put("status",   pedido.getStatus());
        resp.put("chaveNfe", chave);

        // nfe_emissao é a fonte de serie/numeroNFe/estadoFiscal/cStat/xMotivo/nProt sempre que
        // existe um ciclo — nfe_documento não cobre PENDENTE_CONFIRMACAO por falha de rede (nunca
        // recebeu resposta da SEFAZ, então nunca ganha linha em nfe_documento). O ramo `else`
        // abaixo é EXCEÇÃO LEGADA, exclusiva de pedidos sem nenhuma linha em nfe_emissao.
        if (emissao != null) {
            resp.put("serie",        emissao.getSerie());
            resp.put("numeroNFe",    emissao.getNumeroNfe());
            resp.put("estadoFiscal", emissao.getEstado());
            // cStat preserva o tipo String do contrato legado (nfe_documento.cStat sempre foi
            // String, ex.: "100") — o mesmo campo público nunca pode alternar entre "100" e 100
            // dependendo de o pedido ter ou não um ciclo em nfe_emissao.
            resp.put("cStat",   emissao.getCstat() != null ? String.valueOf(emissao.getCstat()) : null);
            resp.put("xMotivo", emissao.getXmotivo());
            resp.put("nProt",   emissao.getNprot());
            documentoService.buscarPorChave(chave).ifPresent(doc -> resp.put("dhRecbto", doc.getDhRecbto()));

            // Projeção do cancelamento (gate de 12-08-2026): estadoFiscal já reflete CANCELADO
            // via emissao.getEstado() acima (só NfeEmissaoMapper.marcarCancelado grava esse
            // valor). cStat/xMotivo/nProt logo acima continuam sendo os da AUTORIZAÇÃO original
            // — nunca sobrescritos; os campos abaixo, aditivos, trazem a evidência do EVENTO de
            // cancelamento em si, vinda de nfe_evento, nunca inventada.
            if (NfeEmissao.Estados.CANCELADO.equals(emissao.getEstado())) {
                NfeEvento eventoCancelamento = nfeEventoService.buscarUltimaTentativa(chave);
                if (eventoCancelamento != null) {
                    resp.put("cStatEvento", eventoCancelamento.getCstat());
                    resp.put("xMotivoEvento", eventoCancelamento.getXmotivo());
                    resp.put("nProtEvento", eventoCancelamento.getNprot());
                    resp.put("dataEventoCancelamento", eventoCancelamento.getResolvidoEm());
                }
            }
        } else {
            documentoService.buscarPorChave(chave).ifPresent(doc -> {
                resp.put("cStat",    doc.getCStat());
                resp.put("xMotivo",  doc.getXMotivo());
                resp.put("nProt",    doc.getNProt());
                resp.put("dhRecbto", doc.getDhRecbto());
            });
        }

        // consultaSefaz — campo LEGADO, mantido por retrocompatibilidade (o contrato anterior o
        // documentava como "sempre presente"; removê-lo quebraria quem já desserializa esse campo).
        // @deprecated sempre null a partir do Gate de contrato OMS (11-08-2026): nunca mais executa
        // consulta live à SEFAZ. /situacao é o endpoint que a OMS usa para polling — uma consulta
        // SOAP crua a cada GET contornaria exatamente o claim atômico + backoff que o Gate 3
        // construiu (NfeReconciliacaoService.tentarAdquirirJanelaConsulta), com risco real de
        // consumo indevido/cStat 656. Reconciliação ativa (ciclo TRANSMITIDO/PENDENTE_CONFIRMACAO)
        // só acontece pelo mecanismo protegido: reemitir o mesmo pedido via POST /emitir, que
        // delega para NfeReconciliacaoService — nunca por aqui.
        resp.put("consultaSefaz", null);

        log.info("[PedidoOperacao] Situação consultada | pedidoId={} | chave={}", pedidoId, chave);
        return resp;
    }

    // -------------------------------------------------------------------------
    // CANCELAMENTO
    // Exige status AUTORIZADO e nProt gravado no nfe_documento.
    // -------------------------------------------------------------------------

    public String cancelar(Long pedidoId, String justificativa) throws Exception {
        if (justificativa == null || justificativa.trim().length() < 15) {
            throw new IllegalArgumentException(
                    "Justificativa de cancelamento deve ter no mínimo 15 caracteres.");
        }

        // P0-2 (07-08-2026, hardening pós-banca) — mesma fronteira de isolamento de emitir().
        Pedido pedido = pedidoService.buscarComItensDoTenanteAtual(pedidoId);

        // Idempotência pós-confirmação (gate de cancelamento, 12-08-2026): repetir /cancelar
        // depois que o pedido já está CANCELADO devolve o resultado já homologado, sem novo
        // evento e sem novo estorno — nunca um erro genérico para uma chamada que só está
        // confirmando o que já aconteceu. Se não houver evidência de nfe_evento REGISTRADO
        // (ex.: cancelamento legado, anterior a este gate), cai no erro padrão abaixo — nunca
        // fabrica um sucesso sem prova.
        if ("CANCELADO".equals(pedido.getStatus()) && pedido.getChaveNfe() != null) {
            NfeEvento eventoRegistrado = nfeEventoService.buscarUltimaTentativa(pedido.getChaveNfe());
            if (eventoRegistrado != null && NfeEvento.Estados.REGISTRADO.equals(eventoRegistrado.getEstado())) {
                log.info("[PedidoOperacao] Cancelamento já confirmado — retorno idempotente | pedidoId={}", pedidoId);
                return "Cancelamento já confirmado anteriormente (nProt=" + eventoRegistrado.getNprot() + ")";
            }
        }

        if (!"AUTORIZADO".equals(pedido.getStatus())) {
            throw BusinessException.invalidOrderStatus(
                    "Cancelamento só é permitido para pedidos com status AUTORIZADO. " +
                    "Status atual: " + pedido.getStatus());
        }
        String chave = validarChave(pedido);
        validarCnpjDocumento(chave, pedido);

        NfeDocumento doc = documentoService.buscarPorChave(chave)
                .orElseThrow(() -> new IllegalStateException(
                        "Documento fiscal não encontrado para chave: " + chave));

        if (doc.getNProt() == null || doc.getNProt().isBlank()) {
            throw new IllegalStateException(
                    "Protocolo de autorização (nProt) não disponível. " +
                    "Consulte a situação do pedido antes de cancelar.");
        }

        // Resolve empresa/certificado real do pedido — contextoResolver lança exceção em vez
        // de cair no emitente/certificado global se a resolução falhar.
        FiscalContexto ctx = contextoResolver.resolver(pedido);
        String cnpjEmitente = ctx.empresa().getCnpj();
        String ufEmitente    = ctx.empresa().getUf();

        // Ciclo do nNF (Gate 1) do pedido, quando existir — liga o evento de cancelamento à
        // projeção NfeEmissao.estado=CANCELADO. Pode ser null para pedidos legados emitidos
        // antes do Gate 1 (07-08-2026); o cancelamento continua funcionando, só sem a projeção.
        NfeEmissao emissao = nfeEmissaoService.buscarUltimaEmissaoDoPedido(pedidoId);
        Long emissaoId = emissao != null ? emissao.getId() : null;

        boolean controla = controlaEstoque(pedido.getEmpresaId());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String criadoPor = auth != null ? auth.getName() : "sistema";

        log.info("[PedidoOperacao] Cancelando NF-e | pedidoId={} | chave={} | nProt={} | cnpj={}",
                pedidoId, chave, doc.getNProt(), cnpjEmitente);

        String retorno = cancelamentoOrquestradorService.cancelar(pedidoId, emissaoId, pedido.getEmpresaId(),
                cnpjEmitente, ufEmitente, chave, doc.getNProt(), justificativa.trim(), ctx.certificado(),
                controla, pedido.getItens(), criadoPor);

        log.info("[PedidoOperacao] Pedido cancelado | pedidoId={}", pedidoId);
        return retorno;
    }

    // -------------------------------------------------------------------------
    // CARTA DE CORREÇÃO ELETRÔNICA (CC-e)
    // -------------------------------------------------------------------------

    public String emitirCce(Long pedidoId, String correcao) throws Exception {
        if (correcao == null || correcao.trim().length() < 15) {
            throw new IllegalArgumentException(
                    "Texto da correção deve ter no mínimo 15 caracteres.");
        }

        // P0-2 (07-08-2026, hardening pós-banca) — mesma fronteira de isolamento de emitir().
        Pedido pedido = pedidoService.buscarPorIdDoTenanteAtual(pedidoId);
        if (!"AUTORIZADO".equals(pedido.getStatus())) {
            throw BusinessException.invalidOrderStatus(
                    "CC-e só é permitida para pedidos com status AUTORIZADO. " +
                    "Status atual: " + pedido.getStatus());
        }
        String chave = validarChave(pedido);
        validarCnpjDocumento(chave, pedido);

        NfeCceRequest req = new NfeCceRequest();
        req.setChaveNfe(chave);
        req.setCorrecao(correcao.trim());

        // Mesmo contexto real do pedido usado no cancelamento — nunca o emitente global.
        FiscalContexto ctx = contextoResolver.resolver(pedido);
        String cnpjEmitente = ctx.empresa().getCnpj();
        String ufEmitente    = ctx.empresa().getUf();

        log.info("[PedidoOperacao] CC-e | pedidoId={} | chave={} | cnpj={}", pedidoId, chave, cnpjEmitente);
        return cceService.corrigir(req, cnpjEmitente, ufEmitente, ctx.certificado());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private String validarChave(Pedido pedido) {
        String chave = pedido.getChaveNfe();
        if (chave == null || chave.isBlank()) {
            throw new IllegalStateException(
                    "Pedido " + pedido.getId() + " não possui chave de NF-e. " +
                    "Execute /emitir primeiro.");
        }
        return chave;
    }

    /**
     * Integridade fiscal multiempresa: confere que o CNPJ embutido na própria chave de acesso
     * (posições 6-19, ground truth do que foi de fato transmitido à SEFAZ) bate com o CNPJ do
     * pedido. Protege contra operar um documento com o contexto de outro CNPJ por
     * inconsistência de dado — falha explícita em vez de seguir silenciosamente.
     */
    private void validarCnpjDocumento(String chave, Pedido pedido) {
        String cnpjChave = extrairCnpjDaChave(chave);
        String cnpjPedido = pedido.getCnpjEmitente() != null
                ? pedido.getCnpjEmitente().replaceAll("\\D", "") : null;
        if (cnpjPedido != null && !cnpjPedido.equals(cnpjChave)) {
            throw BusinessException.documentoCnpjDivergente(cnpjChave, cnpjPedido);
        }
    }

    /** Chave de acesso NF-e: cUF(2) + AAMM(4) + CNPJ(14) + mod(2) + serie(3) + nNF(9) + tpEmis(1) + cNF(8) + cDV(1). */
    private String extrairCnpjDaChave(String chave) {
        return chave.length() >= 20 ? chave.substring(6, 20) : null;
    }

    /** Empresa não encontrada ou id nulo → controla estoque (default seguro). */
    private boolean controlaEstoque(Long empresaId) {
        if (empresaId == null) return true;
        try {
            Empresa e = empresaMapper.buscarPorId(empresaId);
            return e == null || e.controlaEstoque();
        } catch (Exception e) {
            log.warn("[PedidoOperacao] Falha ao resolver empresa | empresaId={} | erro={}", empresaId, e.getMessage());
            return true;
        }
    }
}
