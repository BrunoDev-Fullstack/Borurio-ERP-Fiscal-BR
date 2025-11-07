package br.com.borurio.core.mvc.api;

/**
 * Enum responsável por centralizar os códigos e mensagens padrão de resposta
 * utilizados em toda a aplicação ERP Fiscal Borurio.
 *
 * Cada resposta segue o formato: {code, message, data}
 */
public enum ResultCodeEnum {

    // 2xx - Sucesso
    SUCCESS(200, "Operação realizada com sucesso."),
    CREATED(201, "Registro criado com sucesso."),
    UPDATED(202, "Registro atualizado com sucesso."),
    DELETED(204, "Registro removido com sucesso."),

    // 4xx - Erros de cliente
    BAD_REQUEST(400, "Requisição inválida."),
    UNAUTHORIZED(401, "Não autorizado."),
    FORBIDDEN(403, "Acesso negado."),
    NOT_FOUND(404, "Recurso não encontrado."),
    CONFLICT(409, "Conflito de dados."),

    // 5xx - Erros de servidor
    SERVER_ERROR(500, "Erro interno no servidor."),
    SERVICE_UNAVAILABLE(503, "Serviço temporariamente indisponível.");

    private final int code;
    private final String message;

    ResultCodeEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
