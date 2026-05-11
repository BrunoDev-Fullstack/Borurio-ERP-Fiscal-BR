package br.com.borurio.app.service;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.core.mvc.api.PageResponse;

import java.util.List;

public interface PedidoService {

    Pedido criar(Pedido pedido, List<PedidoItem> itens);

    /** Retorna o pedido com a lista de itens preenchida. */
    Pedido buscarComItens(Long id);

    /** Retorna o pedido validando que pertence à empresa. */
    Pedido buscarComItensEEmpresa(Long id, Long empresaId);

    Pedido buscarPorId(Long id);

    List<Pedido> listarTodos();

    List<Pedido> listarPorEmpresa(Long empresaId);

    PageResponse<Pedido> listarPaginado(Long empresaId, int page, int size);

    void atualizarStatus(Long id, String status, String chaveNfe);
}
