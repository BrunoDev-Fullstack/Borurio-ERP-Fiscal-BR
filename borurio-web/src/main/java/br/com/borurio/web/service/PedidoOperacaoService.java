package br.com.borurio.web.service;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.config.EmitenteProperties;
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
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Operações fiscais vinculadas ao pedido: consulta situação, cancelamento, CC-e.
 * Requer que o pedido tenha chaveNfe preenchida (status AUTORIZADO ou AGUARDANDO).
 */
@Service
public class PedidoOperacaoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoOperacaoService.class);

    private final PedidoService pedidoService;
    private final NfeDocumentoService documentoService;
    private final NfeTransmitService transmitService;
    private final NfeCancelamentoService cancelamentoService;
    private final NfeCceService cceService;
    private final EmitenteProperties emitente;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    public PedidoOperacaoService(PedidoService pedidoService,
                                  NfeDocumentoService documentoService,
                                  NfeTransmitService transmitService,
                                  NfeCancelamentoService cancelamentoService,
                                  NfeCceService cceService,
                                  EmitenteProperties emitente) {
        this.pedidoService     = pedidoService;
        this.documentoService  = documentoService;
        this.transmitService   = transmitService;
        this.cancelamentoService = cancelamentoService;
        this.cceService        = cceService;
        this.emitente          = emitente;
    }

    // -------------------------------------------------------------------------
    // CONSULTA SITUAÇÃO
    // Retorna o estado local (nfe_documento) + consulta live na SEFAZ.
    // -------------------------------------------------------------------------

    public Map<String, Object> consultarSituacao(Long pedidoId) throws Exception {
        Pedido pedido = pedidoService.buscarPorId(pedidoId);
        String chave  = validarChave(pedido);

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

        String uf = emitente.getUf() != null ? emitente.getUf() : "SP";
        resp.put("consultaSefaz", transmitService.consultarNfe(chave, uf, tpAmb));

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

        Pedido pedido = pedidoService.buscarPorId(pedidoId);
        if (!"AUTORIZADO".equals(pedido.getStatus())) {
            throw new IllegalStateException(
                    "Cancelamento só é permitido para pedidos com status AUTORIZADO. " +
                    "Status atual: " + pedido.getStatus());
        }
        String chave = validarChave(pedido);

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

        log.info("[PedidoOperacao] Cancelando NF-e | pedidoId={} | chave={} | nProt={}",
                pedidoId, chave, doc.getNProt());

        String retorno = cancelamentoService.cancelar(req);

        pedidoService.atualizarStatus(pedidoId, "CANCELADO", chave);

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
            throw new IllegalStateException(
                    "CC-e só é permitida para pedidos com status AUTORIZADO. " +
                    "Status atual: " + pedido.getStatus());
        }
        String chave = validarChave(pedido);

        NfeCceRequest req = new NfeCceRequest();
        req.setChaveNfe(chave);
        req.setCorrecao(correcao.trim());

        log.info("[PedidoOperacao] CC-e | pedidoId={} | chave={}", pedidoId, chave);
        return cceService.corrigir(req);
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
}
