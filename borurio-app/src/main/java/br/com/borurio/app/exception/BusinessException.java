package br.com.borurio.app.exception;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Exceção de negócio com errorCode identificável pelo consumidor da API.
 * O GlobalExceptionHandler retorna { code, message, data, errorCode, retryable, requestId } no envelope.
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;
    private final boolean retryable;
    private final Map<String, Object> data;

    public BusinessException(String errorCode, String message, int httpStatus) {
        this(errorCode, message, httpStatus, false, null);
    }

    public BusinessException(String errorCode, String message, int httpStatus, boolean retryable) {
        this(errorCode, message, httpStatus, retryable, null);
    }

    public BusinessException(String errorCode, String message, int httpStatus,
                              boolean retryable, Map<String, Object> data) {
        super(message);
        this.errorCode  = errorCode;
        this.httpStatus = httpStatus;
        this.retryable  = retryable;
        this.data       = data;
    }

    public String getErrorCode()  { return errorCode; }
    public int    getHttpStatus() { return httpStatus; }

    /** Indica ao consumidor da API se a mesma operação pode ser reenviada sem alterar dados. */
    public boolean isRetryable() { return retryable; }

    /** Dados estruturados adicionais (ex.: cStat/xMotivo de uma rejeição SEFAZ). Pode ser null. */
    public Map<String, Object> getData() { return data; }

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

    public static BusinessException companyInactive(String cnpj) {
        return new BusinessException(
                "COMPANY_INACTIVE",
                "Empresa com CNPJ=" + cnpj + " está inativa. Contate o administrador para reativação.",
                422);
    }

    public static BusinessException cnpjNotAuthorizedForOmsClient(String cnpj) {
        return new BusinessException(
                "CNPJ_NOT_AUTHORIZED",
                "CNPJ=" + cnpj + " não está autorizado para este cliente OMS. "
                        + "Realize a autorização via POST /api/integration/fiscal-authorizations.",
                403);
    }

    public static BusinessException certNotFoundForCnpj(String cnpj) {
        return new BusinessException(
                "CERT_NOT_FOUND_FOR_CNPJ",
                "Nenhum certificado ativo encontrado para CNPJ=" + cnpj
                        + ". Verifique se a autorização fiscal foi realizada para este CNPJ.",
                422);
    }

    // -------------------------------------------------------------------------
    // Emissão de NF-e — códigos padronizados de retry (Requisito 4)
    // -------------------------------------------------------------------------

    /** Cadastro do emitente sem endereço completo — não adianta retry sem corrigir o dado. */
    public static BusinessException emitterAddressIncomplete() {
        return new BusinessException(
                "EMITTER_ADDRESS_INCOMPLETE",
                "Cadastro do emitente incompleto.",
                422,
                false);
    }

    /**
     * SEFAZ rejeitou a NF-e (cStat >= 200) — na maioria dos casos é dado incorreto
     * (NCM/CFOP/CSOSN/schema), não falha transitória. retryable=false: reenviar sem corrigir
     * a causa (exposta em data.cStat/data.xMotivo) só repete a mesma rejeição. O pedido pode
     * ser reemitido pelo mesmo /emitir depois de corrigido (ver STATUS_EMISSIVEIS).
     */
    public static BusinessException sefazRejected(int cStat, String xMotivo) {
        return new BusinessException(
                "SEFAZ_REJECTED",
                "NF-e rejeitada pela SEFAZ: " + xMotivo,
                422,
                false,
                Map.of("cStat", cStat, "xMotivo", xMotivo != null ? xMotivo : ""));
    }

    /** Timeout de rede na chamada à SEFAZ — falha transitória, retry é seguro. */
    public static BusinessException sefazTimeout() {
        return new BusinessException(
                "SEFAZ_TIMEOUT",
                "Tempo limite excedido ao transmitir a NF-e para a SEFAZ.",
                503,
                true);
    }

    /** SEFAZ inacessível (conexão recusada/DNS) — falha transitória, retry é seguro. */
    public static BusinessException sefazUnavailable() {
        return new BusinessException(
                "SEFAZ_UNAVAILABLE",
                "SEFAZ temporariamente indisponível.",
                503,
                true);
    }

    /** XML gerado não passou na validação de schema local — retry só ajuda se os dados forem corrigidos. */
    public static BusinessException xmlSchemaInvalid(String detail) {
        return new BusinessException(
                "XML_SCHEMA_INVALID",
                "XML da NF-e não passou na validação de schema: " + detail,
                422,
                false);
    }
}
