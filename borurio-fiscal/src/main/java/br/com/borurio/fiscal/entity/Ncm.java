package br.com.borurio.fiscal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entidade que representa a Tabela NCM (Nomenclatura Comum do Mercosul).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ncm {
    private Long id;
    private String codigo;
    private String descricao;
    private String aliquota;
    private String unidadeMedida;
    private Boolean ativo;
}
