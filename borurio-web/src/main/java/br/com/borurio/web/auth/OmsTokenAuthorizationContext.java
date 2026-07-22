package br.com.borurio.web.auth;

/**
 * Resultado da validação de um jti OMS, incluindo os dados de tenant (empresaId/codigoOms)
 * lidos do BANCO — nunca dos claims do próprio token. O JwtFilter usa estes valores para
 * autenticar, e só compara com os claims do token como checagem de integridade adicional
 * (Gate 7H, ponto 3): qualquer divergência é tratada como token inválido.
 */
public final class OmsTokenAuthorizationContext {

    private final OmsTokenValidationResult status;
    private final Long authId;
    private final Long empresaId;
    private final String codigoOms;

    private OmsTokenAuthorizationContext(OmsTokenValidationResult status, Long authId,
                                          Long empresaId, String codigoOms) {
        this.status = status;
        this.authId = authId;
        this.empresaId = empresaId;
        this.codigoOms = codigoOms;
    }

    public static OmsTokenAuthorizationContext inativo(OmsTokenValidationResult status) {
        return new OmsTokenAuthorizationContext(status, null, null, null);
    }

    public static OmsTokenAuthorizationContext ativo(Long authId, Long empresaId, String codigoOms) {
        return new OmsTokenAuthorizationContext(OmsTokenValidationResult.ACTIVE, authId, empresaId, codigoOms);
    }

    public OmsTokenValidationResult getStatus() { return status; }
    public Long getAuthId()                    { return authId; }
    public Long getEmpresaId()                  { return empresaId; }
    public String getCodigoOms()                { return codigoOms; }
}
