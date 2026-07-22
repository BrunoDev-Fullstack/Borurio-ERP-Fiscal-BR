package br.com.borurio.app.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Getters/setters sem toString() gerado — jtiAnterior/jtiNovo nunca devem ser expostos por um
 * log/print acidental do objeto inteiro (diferente de @Data, que geraria toString com esses campos).
 */
@Getter
@Setter
public class OmsFiscalAuthorizationAudit {

    private Long id;
    private Long authId;
    private String evento;
    private String jtiAnterior;
    private String jtiNovo;
    private LocalDateTime emitidoEmNovo;
    private LocalDateTime tokenExpiraEmNovo;
    private Long versaoAnterior;
    private Long versaoNova;
    private String motivoCodigo;
    private String motivoDetalhe;
    private Long executadoPorUsuarioId;
    private String idempotencyKey;
    private String requestId;
    private LocalDateTime criadoEm;
}
