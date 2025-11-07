package br.com.borurio.core.mvc.api;

/**
 * =============================================================================
 * RESULT UTIL — UTILITÁRIO DE RESPOSTAS PADRONIZADAS
 * =============================================================================
 * Garante consistência nas respostas JSON entre todos os módulos:
 *  - core
 *  - app
 *  - fiscal
 *  - web
 *
 * Estrutura Padrão de Retorno:
 * {
 *     "code": 200,
 *     "message": "Operação realizada com sucesso",
 *     "data": { ... }
 * }
 *
 * Compatibilidade:
 *  - success() → resposta de sucesso padrão
 *  - ok()      → alias compatível com chamadas antigas (ex: módulos fiscais)
 *  - error()   → resposta de falha genérica ou controlada
 *  - created(), updated(), deleted(), notFound() → semântica REST
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Revisão: 07/11/2025
 * =============================================================================
 */
public final class ResultUtil {

    private ResultUtil() {
        throw new IllegalStateException("Classe utilitária - não deve ser instanciada.");
    }

    // ============================================================
    // MÉTODOS DE SUCESSO (HTTP 200 / 201 / 204)
    // ============================================================

    /** Retorna uma resposta de sucesso genérica. */
    public static <T> Result<T> success(T data) {
        return new Result<>(
                ResultCodeEnum.SUCCESS.getCode(),
                ResultCodeEnum.SUCCESS.getMessage(),
                data
        );
    }

    /** Retorna uma resposta de sucesso com mensagem customizada. */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(
                ResultCodeEnum.SUCCESS.getCode(),
                message,
                data
        );
    }

    /** Alias compatível com versões antigas (ex: fiscal ou web). */
    public static <T> Result<T> ok(String message) {
        return success(message, null);
    }

    /** Alias compatível com versões antigas (com payload). */
    public static <T> Result<T> ok(String message, T data) {
        return success(message, data);
    }

    /** Indica criação de recurso (HTTP 201). */
    public static <T> Result<T> created(T data) {
        return new Result<>(
                ResultCodeEnum.CREATED.getCode(),
                ResultCodeEnum.CREATED.getMessage(),
                data
        );
    }

    /** Indica atualização de recurso (HTTP 200). */
    public static <T> Result<T> updated(T data) {
        return new Result<>(
                ResultCodeEnum.UPDATED.getCode(),
                ResultCodeEnum.UPDATED.getMessage(),
                data
        );
    }

    /** Indica exclusão de recurso (HTTP 204). */
    public static <T> Result<T> deleted(T data) {
        return new Result<>(
                ResultCodeEnum.DELETED.getCode(),
                ResultCodeEnum.DELETED.getMessage(),
                data
        );
    }

    // ============================================================
    // MÉTODOS DE ERRO (HTTP 400 / 404 / 500)
    // ============================================================

    /** Retorna um erro genérico com mensagem descritiva. */
    public static <T> Result<T> error(String message) {
        return new Result<>(
                ResultCodeEnum.SERVER_ERROR.getCode(),
                message,
                null
        );
    }

    /** Retorna um erro específico conforme enum do código. */
    public static <T> Result<T> error(ResultCodeEnum codeEnum) {
        return new Result<>(
                codeEnum.getCode(),
                codeEnum.getMessage(),
                null
        );
    }

    /** Retorna um erro de recurso não encontrado (HTTP 404). */
    public static <T> Result<T> notFound(String message) {
        return new Result<>(
                ResultCodeEnum.NOT_FOUND.getCode(),
                message,
                null
        );
    }
}
