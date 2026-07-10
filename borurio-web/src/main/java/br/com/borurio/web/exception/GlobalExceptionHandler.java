package br.com.borurio.web.exception;

import br.com.borurio.app.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Erro de negócio com errorCode identificável pelo OMS. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        log.warn("[API] Business error: errorCode={} | retryable={} | {}",
                e.getErrorCode(), e.isRetryable(), e.getMessage());
        Map<String, Object> body = errorBody(e.getHttpStatus(), e.getMessage(), e.getData(), e.isRetryable());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getHttpStatus()).body(body);
    }

    /** Bean Validation (@Valid em @RequestBody) — retorna campo a campo. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        log.warn("[API] Validation: {}", errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody(422, "Dados inválidos", errors));
    }

    /** Bean Validation em @PathVariable / @RequestParam (@Validated na classe). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e) {
        log.warn("[API] Constraint violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody(422, e.getMessage(), null));
    }

    /** JSON malformado ou tipo incompatível no corpo da requisição. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("[API] Unreadable body: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(400, "Corpo da requisição inválido ou malformado", null));
    }

    /** Regras de negócio inválidas — parâmetros inconsistentes fornecidos pelo cliente. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.warn("[API] Bad request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(400, e.getMessage(), null));
    }

    /** Violação de estado — operação não permitida no estado atual. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleUnprocessable(IllegalStateException e) {
        log.warn("[API] Unprocessable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(errorBody(422, e.getMessage(), null));
    }

    /** Recurso não encontrado. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
        log.warn("[API] Not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody(404, e.getMessage(), null));
    }

    /** Acesso negado — ROLE insuficiente. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(AccessDeniedException e) {
        log.warn("[API] Access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorBody(403, "Acesso negado", null));
    }

    /**
     * Fallback — erros não mapeados, em qualquer endpoint da API (não só NF-e). retryable=false
     * por padrão: sem saber a causa, não dá pra garantir que reenviar é seguro (pode ser bug
     * determinístico ou operação não-idempotente). Endpoints com retry realmente seguro devem
     * lançar um BusinessException tipado (ex.: SEFAZ_TIMEOUT) em vez de cair neste fallback.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("[API] Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(500, "Erro interno do servidor", null));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Map<String, Object> errorBody(int code, String message, Object data) {
        return errorBody(code, message, data, false);
    }

    private Map<String, Object> errorBody(int code, String message, Object data, boolean retryable) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code",      code);
        body.put("message",   message);
        body.put("data",      data);
        body.put("retryable", retryable);
        String rid = MDC.get("requestId");
        if (rid != null) body.put("requestId", rid);
        return body;
    }
}
