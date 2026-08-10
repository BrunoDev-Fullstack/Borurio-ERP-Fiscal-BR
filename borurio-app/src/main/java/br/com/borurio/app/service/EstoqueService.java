package br.com.borurio.app.service;

import br.com.borurio.app.entity.EstoqueMovimento;
import br.com.borurio.app.entity.EstoqueSaldo;
import br.com.borurio.app.entity.PedidoItem;

import java.math.BigDecimal;
import java.util.List;

public interface EstoqueService {

    /** Reserva estoque para cada item antes da transmissão SEFAZ. Lança BusinessException (INSUFFICIENT_STOCK/PRODUCT_NOT_FOUND) se insuficiente ou produto inexistente. */
    void reservarItens(List<PedidoItem> itens, Long empresaId, Long pedidoId, String criadoPor);

    /** Confirma baixa definitiva após cStat=100 (AUTORIZADO). */
    void baixaDefinitivaItens(List<PedidoItem> itens, Long empresaId, Long pedidoId, String criadoPor);

    /** Desfaz reserva em caso de REJEITADO ou ERRO. */
    void desfazerReservaItens(List<PedidoItem> itens, Long empresaId, Long pedidoId, String criadoPor);

    /** Estorna baixa definitiva após cancelamento de NF-e autorizada. */
    void estornarBaixaItens(List<PedidoItem> itens, Long empresaId, Long pedidoId, String criadoPor);

    /** Entrada manual de estoque (NF entrada, ajuste inicial, etc.). */
    EstoqueMovimento entrada(Long produtoId, Long empresaId, BigDecimal quantidade,
                             String criadoPor, String observacao);

    /** Consulta saldo atual: total, reservado e disponível. */
    EstoqueSaldo consultarSaldo(Long produtoId, Long empresaId);
}
