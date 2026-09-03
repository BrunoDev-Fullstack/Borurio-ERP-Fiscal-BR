package br.com.borurio.app.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class EstoqueMovimento {

    private Long id;
    private Long produtoId;
    private Long empresaId;

    /** RESERVA | DESFAZER_RESERVA | BAIXA | ESTORNO | ENTRADA */
    private String tipo;

    private BigDecimal quantidade;

    /** PEDIDO | MANUAL */
    private String referenciaTipo;

    private Long referenciaId;
    private String observacao;
    private String criadoPor;
    private LocalDateTime criadoEm;
}
