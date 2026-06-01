package br.com.borurio.app.entity;

public class ProdutoBatchItemResultado {

    public enum Status { CRIADO, ATUALIZADO, REJEITADO }

    private final String codigo;
    private final Status status;
    private final Long   produtoId;
    private final String errorCode;
    private final String message;

    private ProdutoBatchItemResultado(String codigo, Status status,
                                      Long produtoId, String errorCode, String message) {
        this.codigo    = codigo;
        this.status    = status;
        this.produtoId = produtoId;
        this.errorCode = errorCode;
        this.message   = message;
    }

    public static ProdutoBatchItemResultado criado(String codigo, Long produtoId) {
        return new ProdutoBatchItemResultado(codigo, Status.CRIADO, produtoId, null, null);
    }

    public static ProdutoBatchItemResultado atualizado(String codigo, Long produtoId) {
        return new ProdutoBatchItemResultado(codigo, Status.ATUALIZADO, produtoId, null, null);
    }

    public static ProdutoBatchItemResultado rejeitado(String codigo, String errorCode, String message) {
        return new ProdutoBatchItemResultado(codigo, Status.REJEITADO, null, errorCode, message);
    }

    public String getCodigo()    { return codigo; }
    public Status getStatus()    { return status; }
    public Long   getProdutoId() { return produtoId; }
    public String getErrorCode() { return errorCode; }
    public String getMessage()   { return message; }
}
