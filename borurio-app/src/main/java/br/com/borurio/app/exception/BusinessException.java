package br.com.borurio.app.exception;

import java.math.BigDecimal;

/**
 * Exceção de negócio com errorCode identificável pelo consumidor da API.
 * O GlobalExceptionHandler retorna { code, message, data: null, errorCode } no envelope.
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    public BusinessException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode  = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode()  { return errorCode; }
    public int    getHttpStatus() { return httpStatus; }

    // -------------------------------------------------------------------------
    // Factory methods — mantêm mensagens consistentes com o contrato de integração
    // -------------------------------------------------------------------------

    public static BusinessException productNotFound(Long produtoId) {
        return new BusinessException(
                "PRODUCT_NOT_FOUND",
                "Produto não encontrado: id=" + produtoId,
                422);
    }

    public static BusinessException productInactive(Long produtoId, String codigo) {
        return new BusinessException(
                "PRODUCT_INACTIVE",
                "Produto inativo não pode ser adicionado ao pedido: id=" + produtoId + " código=" + codigo,
                422);
    }

    public static BusinessException insufficientStock(String descricao, BigDecimal disponivel, BigDecimal solicitado) {
        return new BusinessException(
                "INSUFFICIENT_STOCK",
                "Estoque insuficiente para \"" + descricao + "\""
                        + " (disponível: " + disponivel + ", solicitado: " + solicitado + ")",
                422);
    }

    public static BusinessException invalidOrderStatus(String message) {
        return new BusinessException("INVALID_ORDER_STATUS", message, 422);
    }
}
