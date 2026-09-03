package br.com.borurio.web.dto;

import br.com.borurio.app.entity.PedidoItem;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Item do payload de criação de pedido — não expõe {@code id}, {@code pedidoId} nem
 * {@code valorTotal} (calculado pelo servidor). PedidoItem é entidade interna; usar este DTO
 * evita reabrir a mesma superfície de mass assignment que PedidoCreateRequest fechou no cabeçalho
 * do pedido (achado de revisão de 20-07-2026).
 */
@Data
public class PedidoItemCreateRequest {

    private Long produtoId;
    private BigDecimal quantidade;
    private BigDecimal valorUnitario;
    private String codigoProduto;
    private String descricao;
    private String ncm;
    private String cfop;
    private String unidade;
    private Integer origem;
    private String csosn;

    public PedidoItem toPedidoItem() {
        PedidoItem item = new PedidoItem();
        item.setProdutoId(produtoId);
        item.setQuantidade(quantidade);
        item.setValorUnitario(valorUnitario);
        item.setCodigoProduto(codigoProduto);
        item.setDescricao(descricao);
        item.setNcm(ncm);
        item.setCfop(cfop);
        item.setUnidade(unidade);
        item.setOrigem(origem);
        item.setCsosn(csosn);
        return item;
    }
}
