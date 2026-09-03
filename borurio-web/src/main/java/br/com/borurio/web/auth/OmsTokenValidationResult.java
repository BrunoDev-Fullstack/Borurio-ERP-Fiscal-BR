package br.com.borurio.web.auth;

/**
 * Resultado interno da validação de um jti OMS. Nunca exposto diretamente ao cliente da API —
 * JwtFilter colapsa NOT_FOUND/REVOKED/EXPIRED no mesmo HTTP 401 genérico (INVALID_OMS_TOKEN),
 * para não revelar ao portador do token qual dessas causas se aplica.
 */
public enum OmsTokenValidationResult {
    ACTIVE,
    NOT_FOUND,
    REVOKED,
    EXPIRED,
    SERVICE_UNAVAILABLE
}
