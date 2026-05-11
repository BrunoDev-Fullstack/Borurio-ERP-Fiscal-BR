package br.com.borurio.web.exception;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** Bean Validation (@Valid em @RequestBody) — retorna campo a campo. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        log.warn("[API] Validation: {}", errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new Result<>(422, "Dados inválidos", errors));
    }

    /** Bean Validation em @PathVariable / @RequestParam (@Validated na classe). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<?>> handleConstraint(ConstraintViolationException e) {
        log.warn("[API] Constraint violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ResultUtil.error(422, e.getMessage()));
    }

    /** JSON malformado ou tipo incompatível no corpo da requisição. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("[API] Unreadable body: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultUtil.error(400, "Corpo da requisição inválido ou malformado"));
    }

    /** Regras de negócio inválidas — parâmetros inconsistentes fornecidos pelo cliente. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleBadRequest(IllegalArgumentException e) {
        log.warn("[API] Bad request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultUtil.error(400, e.getMessage()));
    }

    /** Violação de estado — operação não permitida no estado atual (ex: cancelar NF-e não autorizada). */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<?>> handleUnprocessable(IllegalStateException e) {
        log.warn("[API] Unprocessable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ResultUtil.error(422, e.getMessage()));
    }

    /** Recurso não encontrado. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Result<?>> handleNotFound(NoSuchElementException e) {
        log.warn("[API] Not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ResultUtil.error(404, e.getMessage()));
    }

    /** Acesso negado — ROLE insuficiente. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<?>> handleForbidden(AccessDeniedException e) {
        log.warn("[API] Access denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ResultUtil.error(403, "Acesso negado"));
    }

    /** Fallback — erros não mapeados. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleGeneric(Exception e) {
        log.error("[API] Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultUtil.error(500, "Erro interno do servidor"));
    }
}
