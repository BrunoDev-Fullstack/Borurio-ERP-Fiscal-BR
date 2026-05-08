package br.com.borurio.app.entity;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PedidoItem {

    private Long id;
    private Long pedidoId;

    /** Referência ao produto cadastrado. Mantido para rastreabilidade — não usado na emissão. */
    private Long produtoId;

    private BigDecimal quantidade;
    private BigDecimal valorUnitario;
    private BigDecimal valorTotal;

    // =========================================================================
    // SNAPSHOT FISCAL — congelado no momento da criação do pedido.
    // Alterações posteriores no produto NÃO afetam estes campos.
    // =========================================================================

    private String codigoProduto;
    private String descricao;
    private String ncm;
    private String cfop;
    private String unidade;

    /** Origem da mercadoria: 0=Nacional, 1–8=Importada. */
    private Integer origem;

    /** CSOSN Simples Nacional (ex: 102, 400, 500, 900). */
    private String csosn;
}
