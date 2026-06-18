package br.com.borurio.app.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OmsCompanyCertificate {

    private Long id;
    private Long authId;
    private String thumbprint;
    private byte[] certPfxEnc;
    private String certSenhaEnc;
    private String keyVersion;
    private LocalDateTime notBefore;
    private LocalDateTime notAfter;
    private Boolean ativo;
    private LocalDateTime cadastradoEm;
    private LocalDateTime substituidoEm;
    // auth_id_ativo_unico é coluna GENERATED ALWAYS — não mapeada em Java
}
