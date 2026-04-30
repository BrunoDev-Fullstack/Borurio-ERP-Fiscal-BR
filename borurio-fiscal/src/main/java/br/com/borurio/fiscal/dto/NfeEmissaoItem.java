package br.com.borurio.fiscal.dto;

import java.math.BigDecimal;

public class NfeEmissaoItem {

    private String codigoProduto;
    private String descricao;
    private String ncm;
    private String cfop;
    private String unidade;
    private BigDecimal quantidade;
    private BigDecimal valorUnitario;

    public NfeEmissaoItem() {}

    public NfeEmissaoItem(String codigoProduto, String descricao, String ncm,
                          String cfop, String unidade,
                          BigDecimal quantidade, BigDecimal valorUnitario) {
        this.codigoProduto = codigoProduto;
        this.descricao = descricao;
        this.ncm = ncm;
        this.cfop = cfop;
        this.unidade = unidade;
        this.quantidade = quantidade;
        this.valorUnitario = valorUnitario;
    }

    public BigDecimal getValorTotal() {
        if (quantidade == null || valorUnitario == null) return BigDecimal.ZERO;
        return quantidade.multiply(valorUnitario);
    }

    public String getCodigoProduto() { return codigoProduto; }
    public void setCodigoProduto(String codigoProduto) { this.codigoProduto = codigoProduto; }

    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }

    public String getNcm() { return ncm; }
    public void setNcm(String ncm) { this.ncm = ncm; }

    public String getCfop() { return cfop; }
    public void setCfop(String cfop) { this.cfop = cfop; }

    public String getUnidade() { return unidade; }
    public void setUnidade(String unidade) { this.unidade = unidade; }

    public BigDecimal getQuantidade() { return quantidade; }
    public void setQuantidade(BigDecimal quantidade) { this.quantidade = quantidade; }

    public BigDecimal getValorUnitario() { return valorUnitario; }
    public void setValorUnitario(BigDecimal valorUnitario) { this.valorUnitario = valorUnitario; }
}
