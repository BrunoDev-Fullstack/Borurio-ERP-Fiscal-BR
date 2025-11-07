package br.com.borurio.core.mvc.api;

/**
 * Enum genérico para representar estados comuns de entidades e processos
 * dentro do ERP Fiscal Borurio Brasil.
 *
 * Utilizado em cadastros, fluxos de NF-e e controle de operações.
 */
public enum CommonStateEnum {

    ATIVO(1, "Ativo"),
    INATIVO(0, "Inativo"),
    PENDENTE(2, "Pendente"),
    CANCELADO(3, "Cancelado"),
    PROCESSANDO(4, "Processando"),
    ERRO(5, "Erro");

    private final int code;
    private final String descricao;

    CommonStateEnum(int code, String descricao) {
        this.code = code;
        this.descricao = descricao;
    }

    public int getCode() {
        return code;
    }

    public String getDescricao() {
        return descricao;
    }
}
