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

    public static BusinessException batchLimitExceeded() {
        return new BusinessException(
                "BATCH_LIMIT_EXCEEDED",
                "O lote excede o limite máximo de 200 produtos por requisição.",
                422);
    }

    // -------------------------------------------------------------------------
    // OMS — autorização fiscal
    // -------------------------------------------------------------------------

    public static BusinessException companyNotFound(String cnpj) {
        return new BusinessException(
                "COMPANY_NOT_FOUND",
                "Empresa não encontrada ou não pré-cadastrada: CNPJ=" + cnpj,
                422);
    }

    public static BusinessException invalidCertificate(String detail) {
        return new BusinessException(
                "INVALID_CERTIFICATE",
                "Certificado A1 inválido: " + detail,
                422);
    }

    public static BusinessException cnpjCertificateMismatch(String cnpjEnviado, String cnpjCert) {
        return new BusinessException(
                "CNPJ_CERTIFICATE_MISMATCH",
                "CNPJ enviado (" + cnpjEnviado + ") não corresponde ao CNPJ do certificado (" + cnpjCert + ")",
                422);
    }

    public static BusinessException certificateExpired() {
        return new BusinessException(
                "CERTIFICATE_EXPIRED",
                "O certificado A1 está expirado e não pode ser utilizado para emissão.",
                422);
    }

    public static BusinessException invalidApiKey() {
        return new BusinessException(
                "INVALID_API_KEY",
                "API Key ausente, inválida, expirada ou revogada.",
                401);
    }

    public static BusinessException authorizationRevoked() {
        return new BusinessException(
                "AUTHORIZATION_REVOKED",
                "A autorização fiscal foi revogada. Realize uma nova autorização.",
                401);
    }
}
