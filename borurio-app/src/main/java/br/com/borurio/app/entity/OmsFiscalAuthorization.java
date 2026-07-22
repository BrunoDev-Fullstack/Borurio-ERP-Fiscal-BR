package br.com.borurio.app.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Getters/setters sem toString() gerado — possui jti (token técnico ativo), que não deve ser
 * exposto por um log/print acidental do objeto inteiro (@Data geraria toString com esse campo).
 */
@Getter
@Setter
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
    private Long versao;
}
