package br.com.borurio.core.mvc.api;

/**
 * =============================================================================
 * CLASSE: Result
 * -----------------------------------------------------------------------------
 * Modelo padrão de resposta JSON para as APIs REST do ERP Fiscal Borurio BR.
 *
 * Exemplo:
 * {
 *   "code": 200,
 *   "message": "Operação realizada com sucesso.",
 *   "data": { ... }
 * }
 * -----------------------------------------------------------------------------
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Módulo: borurio-core
 * =============================================================================
 */
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    // Getters e Setters
    public int getCode() {
        return code;
    }
    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }
    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }
    public void setData(T data) {
        this.data = data;
    }
}
