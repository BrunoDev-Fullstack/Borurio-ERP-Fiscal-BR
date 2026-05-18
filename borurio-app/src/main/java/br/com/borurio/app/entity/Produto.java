package br.com.borurio.app.entity;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Produto {

    private Long id;
    private Long empresaId;

    @NotBlank(message = "Código do produto é obrigatório")
    @Size(max = 60, message = "Código deve ter no máximo 60 caracteres")
    private String codigo;

    @NotBlank(message = "Descrição é obrigatória")
    @Size(max = 120, message = "Descrição deve ter no máximo 120 caracteres")
    private String descricao;

    @NotBlank(message = "NCM é obrigatório")
    @Size(min = 8, max = 8, message = "NCM deve conter 8 dígitos")
    private String ncm;

    @NotBlank(message = "CFOP é obrigatório")
    @Size(min = 4, max = 4, message = "CFOP deve conter 4 dígitos")
    private String cfop;

    @NotBlank(message = "Unidade comercial é obrigatória")
    private String unidade;

    @NotNull(message = "Preço é obrigatório")
    @DecimalMin(value = "0.01", message = "Preço deve ser maior que zero")
    private BigDecimal preco;

    private Integer estado;

    @NotNull(message = "Origem é obrigatória (0=Nacional, 1–8=Importada)")
    private Integer origem;

    private String csosn;
    private BigDecimal estoque;
    private BigDecimal estoqueReservado;
    private LocalDateTime criadoEm;
    private LocalDateTime atualizadoEm;
}
