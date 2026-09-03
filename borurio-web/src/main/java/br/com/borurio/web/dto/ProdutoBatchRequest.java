package br.com.borurio.web.dto;

import java.math.BigDecimal;
import java.util.List;

public class ProdutoBatchRequest {

    private List<ProdutoItemRequest> produtos;

    public List<ProdutoItemRequest> getProdutos() { return produtos; }
    public void setProdutos(List<ProdutoItemRequest> produtos) { this.produtos = produtos; }

    public static class ProdutoItemRequest {

        private String     codigo;
        private String     descricao;
        private String     ncm;
        private String     cfop;
        private String     unidade;
        private BigDecimal preco;
        private Integer    origem;
        private String     csosn;

        public String     getCodigo()   { return codigo; }
        public String     getDescricao(){ return descricao; }
        public String     getNcm()      { return ncm; }
        public String     getCfop()     { return cfop; }
        public String     getUnidade()  { return unidade; }
        public BigDecimal getPreco()    { return preco; }
        public Integer    getOrigem()   { return origem; }
        public String     getCsosn()    { return csosn; }

        public void setCodigo(String codigo)       { this.codigo = codigo; }
        public void setDescricao(String descricao) { this.descricao = descricao; }
        public void setNcm(String ncm)             { this.ncm = ncm; }
        public void setCfop(String cfop)           { this.cfop = cfop; }
        public void setUnidade(String unidade)     { this.unidade = unidade; }
        public void setPreco(BigDecimal preco)     { this.preco = preco; }
        public void setOrigem(Integer origem)      { this.origem = origem; }
        public void setCsosn(String csosn)         { this.csosn = csosn; }
    }
}
