package br.com.borurio.app.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class EstoqueSaldo {

    private Long produtoId;
    private Long empresaId;
    private BigDecimal estoqueTotal;
    private BigDecimal estoqueReservado;
    /** estoqueTotal - estoqueReservado */
    private BigDecimal estoqueDisponivel;
}
