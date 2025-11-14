package br.com.borurio.core.mvc.api;

/**
 * Enum de códigos de resposta padronizados do ERP Borurio Brasil.
 * Utilizado em todos os módulos (core, app, fiscal, web).
 *
 * Padrão: { code, message }
 */
public enum ResultCodeEnum {

    SUCCESS(200, "Sucesso"),
    FAIL(400, "Falha na operação"),
    NOT_FOUND(404, "Registro não encontrado"),
    BAD_REQUEST(400, "Requisição inválida"),
    ERROR(500, "Erro interno do servidor");

    private final int code;
    private final String message;

    ResultCodeEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
