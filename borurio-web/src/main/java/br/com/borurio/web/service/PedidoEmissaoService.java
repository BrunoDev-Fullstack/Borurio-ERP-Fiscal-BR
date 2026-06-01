package br.com.borurio.web.service;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.fiscal.dto.NfeEmissaoItem;
import br.com.borurio.fiscal.dto.NfeEmissaoRequest;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.fiscal.dto.NfeSefazRetorno;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge: Pedido + snapshot fiscal → NfeEmissaoRequest → motor fiscal.
 * Após transmissão:
 *   cStat=100  → AUTORIZADO  + baixa definitiva de estoque
 *   cStat≥200  → REJEITADO   + desfaz reserva de estoque
 *   lote aceito sem infProt → AGUARDANDO (reserva mantida — Opção A)
 *   exceção     → ERRO       + desfaz reserva de estoque
 */
@Service
public class PedidoEmissaoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoEmissaoService.class);

    private final PedidoService pedidoService;
    private final NfeGeracaoService nfeGeracaoService;
    private final NfeSefazRetornoParser retornoParser;
    private final EstoqueService estoqueService;
    private final EmpresaMapper empresaMapper;

    public PedidoEmissaoService(PedidoService pedidoService,
                                NfeGeracaoService nfeGeracaoService,
                                NfeSefazRetornoParser retornoParser,
                                EstoqueService estoqueService,
                                EmpresaMapper empresaMapper) {
        this.pedidoService     = pedidoService;
        this.nfeGeracaoService = nfeGeracaoService;
        this.retornoParser     = retornoParser;
        this.estoqueService    = estoqueService;
        this.empresaMapper     = empresaMapper;
    }

    public NfeGeracaoResult emitir(Long pedidoId) throws Exception {
        Pedido pedido = pedidoService.buscarComItens(pedidoId);

        if (!"RASCUNHO".equals(pedido.getStatus())) {
            throw BusinessException.invalidOrderStatus(
                    "Pedido não está em RASCUNHO. Status atual: " + pedido.getStatus());
        }
        if (pedido.getItens() == null || pedido.getItens().isEmpty()) {
            throw new IllegalArgumentException("Pedido sem itens não pode ser emitido.");
        }

        Long empresaId = EmpresaContextHolder.get() != null
                ? EmpresaContextHolder.get() : pedido.getEmpresaId();
        String criadoPor = resolverCriadoPor();

        // Reserva ANTES da chamada SEFAZ — lança IllegalStateException (→ 422) se insuficiente
        estoqueService.reservarItens(pedido.getItens(), empresaId, pedidoId, criadoPor);

        NfeEmissaoRequest req = montarRequest(pedido);
        Empresa empresa = resolverEmpresa(empresaId);

        log.info("[PedidoEmissao] Transmitindo | pedidoId={} | dest={} | empresaId={} | itens={}",
                pedidoId, pedido.getDestCnpjCpf(), empresaId, req.getItens().size());

        NfeGeracaoResult result;
        try {
            result = nfeGeracaoService.gerar(req, empresa);
        } catch (Exception e) {
            pedidoService.atualizarStatus(pedidoId, "ERRO", null);
            desfazerReservaSeguro(pedido.getItens(), empresaId, pedidoId, criadoPor);
            throw e;
        }

        String novoStatus = resolverStatus(result.getSoapRetorno());
        pedidoService.atualizarStatus(pedidoId, novoStatus, result.getChaveNfe());

        if ("AUTORIZADO".equals(novoStatus)) {
            estoqueService.baixaDefinitivaItens(pedido.getItens(), empresaId, pedidoId, criadoPor);
        } else if ("REJEITADO".equals(novoStatus)) {
            desfazerReservaSeguro(pedido.getItens(), empresaId, pedidoId, criadoPor);
        }
        // AGUARDANDO: reserva mantida (Opção A — liberar manualmente ou no próximo ciclo de consulta)

        log.info("[PedidoEmissao] Concluído | pedidoId={} | status={} | chave={}",
                pedidoId, novoStatus, result.getChaveNfe());

        return result;
    }

    // -------------------------------------------------------------------------
    // Resolução de status baseada no cStat real da SEFAZ
    // -------------------------------------------------------------------------

    private String resolverStatus(String soapRetorno) {
        try {
            NfeSefazRetorno retorno = retornoParser.parse(soapRetorno);
            if (retorno.isAutorizada()) return "AUTORIZADO";   // cStat=100
            if (retorno.getCStat() >= 200) return "REJEITADO"; // cStat 2xx–9xx
            return "AGUARDANDO"; // cStat=104: lote aceito, aguardando autorização individual
        } catch (Exception e) {
            log.warn("[PedidoEmissao] Falha ao parsear retorno para status | erro={}", e.getMessage());
            return "AGUARDANDO";
        }
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

    private Empresa resolverEmpresa(Long empresaId) {
        if (empresaId == null) return null;
        try {
            return empresaMapper.buscarPorId(empresaId);
        } catch (Exception e) {
            log.warn("[PedidoEmissao] Falha ao resolver empresa | empresaId={} | erro={}", empresaId, e.getMessage());
            return null;
        }
    }

    private String resolverCriadoPor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "sistema";
    }

    private NfeEmissaoRequest montarRequest(Pedido pedido) {
        NfeEmissaoRequest req = new NfeEmissaoRequest();
        req.setSerie(pedido.getSerieNfe() != null ? pedido.getSerieNfe() : "1");
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
