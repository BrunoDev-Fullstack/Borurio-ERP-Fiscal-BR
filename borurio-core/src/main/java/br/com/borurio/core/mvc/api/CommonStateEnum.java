package br.com.borurio.core.mvc.api;

/**
 * Estado padrão das respostas da aplicação
 */
public enum CommonStateEnum {

    SUCCESS(true, "Operação realizada com sucesso."),
    FAILURE(false, "Falha ao processar a operação.");

    private final boolean success;
    private final String message;

    CommonStateEnum(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
