package br.com.borurio.app.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OmsFiscalAuthorization {

    private Long id;
    private Long empresaId;
    private Long integratorId;
    private String codigoOms;
    private String jti;
    private LocalDateTime tokenExpiraEm;
    private LocalDateTime emitidoEm;
    private LocalDateTime atualizadoEm;
    private LocalDateTime revogadoEm;
    private String motivoRevogacao;
}
