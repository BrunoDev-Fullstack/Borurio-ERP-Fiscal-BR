package br.com.borurio.core.mvc.api;

/**
 * Utilitário para construção de respostas padronizadas.
 * Compatível com os módulos App, Fiscal e Web.
 */
public class ResultUtil {

    public static <T> Result<T> success() {
        return new Result<>(
                ResultCodeEnum.SUCCESS.code(),
                ResultCodeEnum.SUCCESS.message(),
                null
        );
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(
                ResultCodeEnum.SUCCESS.code(),
                ResultCodeEnum.SUCCESS.message(),
                data
        );
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(
                ResultCodeEnum.ERROR.code(),
                (message != null ? message : ResultCodeEnum.ERROR.message()),
                null
        );
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(
                code,
                message,
                null
        );
    }
}
