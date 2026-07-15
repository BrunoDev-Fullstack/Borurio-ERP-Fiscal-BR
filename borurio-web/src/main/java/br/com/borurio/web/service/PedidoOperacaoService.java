package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.dto.NfeCancelamentoRequest;
import br.com.borurio.fiscal.dto.NfeCceRequest;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.service.NfeCancelamentoService;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
    private final NfeTransmitService transmitService;
    private final NfeCancelamentoService cancelamentoService;
    private final NfeCceService cceService;
    private final EstoqueService estoqueService;
    private final EmpresaMapper empresaMapper;
    private final FiscalContextoResolver contextoResolver;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public PedidoOperacaoService(PedidoService pedidoService,
                                  NfeDocumentoService documentoService,
                                  NfeTransmitService transmitService,
                                  NfeCancelamentoService cancelamentoService,
                                  NfeCceService cceService,
                                  EstoqueService estoqueService,
                                  EmpresaMapper empresaMapper,
                                  FiscalContextoResolver contextoResolver) {
        this.pedidoService     = pedidoService;
        this.documentoService  = documentoService;
        this.transmitService   = transmitService;
        this.cancelamentoService = cancelamentoService;
        this.cceService        = cceService;
        this.estoqueService    = estoqueService;
        this.empresaMapper     = empresaMapper;
        this.contextoResolver  = contextoResolver;
    }

    // -------------------------------------------------------------------------
    // CONSULTA SITUAÇÃO
    // Retorna o estado local (nfe_documento) + consulta live na SEFAZ.
    // -------------------------------------------------------------------------

    public Map<String, Object> consultarSituacao(Long pedidoId) throws Exception {
        Pedido pedido = pedidoService.buscarPorId(pedidoId);
        String chave  = validarChave(pedido);
        validarCnpjDocumento(chave, pedido);

        Optional<NfeDocumento> docOpt = documentoService.buscarPorChave(chave);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("pedidoId",  pedidoId);
        resp.put("numero",    pedido.getNumero());
        resp.put("status",    pedido.getStatus());
        resp.put("chaveNfe",  chave);

        docOpt.ifPresent(doc -> {
            resp.put("cStat",    doc.getCStat());
            resp.put("xMotivo",  doc.getXMotivo());
            resp.put("nProt",    doc.getNProt());
            resp.put("dhRecbto", doc.getDhRecbto());
        });

        // UF resolvida pela empresa emitente real do pedido — contextoResolver.resolver()
        // já lança exceção se a empresa não puder ser resolvida, então ctx.empresa() aqui
        // nunca é nulo. O fallback "SP" cobre só o caso de dado incompleto (empresa resolvida
        // mas sem UF cadastrada) — é uma consulta somente leitura à SEFAZ-SP (única UF
        // operada hoje), não uma assinatura/transmissão, então esse fallback estreito não
        // representa o mesmo risco de integridade que existiria em cancelamento/CC-e.
        FiscalContexto ctx = contextoResolver.resolver(pedido);
        String uf = ctx.empresa().getUf() != null ? ctx.empresa().getUf() : "SP";
        try {
            resp.put("consultaSefaz", transmitService.consultarNfe(chave, uf, tpAmb));
        } catch (Exception e) {
            log.warn("[PedidoOperacao] Consulta SEFAZ indisponível — dados locais retornados | pedidoId={} | erro={}",
                    pedidoId, e.getMessage());
            resp.put("consultaSefaz", null);
        }

        log.info("[PedidoOperacao] Situação consultada | pedidoId={} | chave={} | cnpj={}",
                pedidoId, chave, ctx.empresa().getCnpj());
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

        Pedido pedido = pedidoService.buscarComItens(pedidoId);
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

        NfeCancelamentoRequest req = new NfeCancelamentoRequest();
        req.setChaveNfe(chave);
        req.setNProtocolo(doc.getNProt());
        req.setJustificativa(justificativa.trim());

        // Resolve empresa/certificado real do pedido — contextoResolver lança exceção em vez
        // de cair no emitente/certificado global se a resolução falhar.
        FiscalContexto ctx = contextoResolver.resolver(pedido);
        String cnpjEmitente = ctx.empresa().getCnpj();
        String ufEmitente    = ctx.empresa().getUf();

        log.info("[PedidoOperacao] Cancelando NF-e | pedidoId={} | chave={} | nProt={} | cnpj={}",
                pedidoId, chave, doc.getNProt(), cnpjEmitente);

        String retorno = cancelamentoService.cancelar(req, cnpjEmitente, ufEmitente, ctx.certificado());

        pedidoService.atualizarStatus(pedidoId, "CANCELADO", chave);

        if (pedido.getItens() != null && !pedido.getItens().isEmpty() && controlaEstoque(pedido.getEmpresaId())) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String criadoPor = auth != null ? auth.getName() : "sistema";
            try {
                estoqueService.estornarBaixaItens(
                        pedido.getItens(), pedido.getEmpresaId(), pedidoId, criadoPor);
            } catch (Exception e) {
                log.error("[PedidoOperacao] Falha ao estornar estoque | pedidoId={} | erro={}",
                        pedidoId, e.getMessage());
            }
        }

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

        Pedido pedido = pedidoService.buscarPorId(pedidoId);
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
