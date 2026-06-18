package br.com.borurio.app.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OmsApiKey {

    private Long id;
    private Long integratorId;
    private String descricao;
    private String chaveHash;
    private Boolean ativo;
    private LocalDateTime criadoEm;
    private LocalDateTime expiraEm;
    private LocalDateTime revogadoEm;
}
