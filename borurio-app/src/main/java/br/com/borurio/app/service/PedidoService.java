package br.com.borurio.app.service;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;

import java.util.List;

public interface PedidoService {

    Pedido criar(Pedido pedido, List<PedidoItem> itens);

    /** Retorna o pedido com a lista de itens preenchida. */
    Pedido buscarComItens(Long id);

    Pedido buscarPorId(Long id);

    List<Pedido> listarTodos();

    void atualizarStatus(Long id, String status, String chaveNfe);
}
