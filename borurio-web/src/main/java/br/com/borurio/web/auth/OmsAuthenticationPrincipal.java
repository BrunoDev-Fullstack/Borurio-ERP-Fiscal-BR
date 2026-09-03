package br.com.borurio.web.auth;

/**
 * Principal do SecurityContext para requisições autenticadas via token OMS.
 * Não representa um usuário do sistema — representa a sessão fiscal de uma empresa
 * autorizada pelo integrador OMS.
 */
public record OmsAuthenticationPrincipal(Long empresaId, String jti, String codigoOms) {

    @Override
    public String toString() {
        return "OMS[empresaId=" + empresaId + ", codigoOms=" + codigoOms + "]";
    }
}
