package br.com.borurio.app.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OmsIntegrator {

    private Long id;
    private String codigo;
    private String nome;
    private Boolean ativo;
    private LocalDateTime criadoEm;
    private LocalDateTime desativadoEm;
}
