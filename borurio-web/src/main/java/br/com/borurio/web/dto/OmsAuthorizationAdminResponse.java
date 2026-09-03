package br.com.borurio.web.dto;

import java.time.LocalDateTime;

/**
 * Sem Lombok @Data (evita toString() auto-gerado expondo o token) e sem override manual de
 * toString() — depende do Object#toString() padrão, que não imprime campos.
 */
public class OmsAuthorizationAdminResponse {

    private final Long authId;
    private final String status;
    private final Long versao;
    private final LocalDateTime tokenExpiraEm;
    private final String token;
    private final String requestId;

    public OmsAuthorizationAdminResponse(Long authId, String status, Long versao,
                                          LocalDateTime tokenExpiraEm, String token, String requestId) {
        this.authId        = authId;
        this.status        = status;
        this.versao        = versao;
        this.tokenExpiraEm = tokenExpiraEm;
        this.token         = token;
        this.requestId     = requestId;
    }

    public Long getAuthId()                 { return authId; }
    public String getStatus()               { return status; }
    public Long getVersao()                 { return versao; }
    public LocalDateTime getTokenExpiraEm()  { return tokenExpiraEm; }
    public String getToken()                { return token; }
    public String getRequestId()            { return requestId; }
}
