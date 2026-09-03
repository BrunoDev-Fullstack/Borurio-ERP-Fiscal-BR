package br.com.borurio.web.dto;

import java.time.LocalDateTime;

public class OmsFiscalAuthorizationResponse {

    private String token;
    private Long empresaId;
    private String cnpj;
    private String razaoSocial;
    private LocalDateTime tokenExpiraEm;

    public OmsFiscalAuthorizationResponse(String token, Long empresaId, String cnpj,
                                           String razaoSocial, LocalDateTime tokenExpiraEm) {
        this.token         = token;
        this.empresaId     = empresaId;
        this.cnpj          = cnpj;
        this.razaoSocial   = razaoSocial;
        this.tokenExpiraEm = tokenExpiraEm;
    }

    public String getToken()               { return token; }
    public Long getEmpresaId()             { return empresaId; }
    public String getCnpj()               { return cnpj; }
    public String getRazaoSocial()         { return razaoSocial; }
    public LocalDateTime getTokenExpiraEm() { return tokenExpiraEm; }
}
