package br.com.borurio.app.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Produto {

    private Long id;

    /** Código interno do produto. Mapeado para <cProd> na NF-e. */
    private String codigo;

    /** Descrição do produto. Mapeado para <xProd> na NF-e. */
    private String descricao;

    /** Código NCM (8 dígitos sem pontuação). */
    private String ncm;

    /** CFOP padrão para operações de saída (ex: 5102). */
    private String cfop;

    /** Unidade comercial: UN, KG, CX, LT, etc. */
    private String unidade;

    /** Preço unitário de venda. */
    private BigDecimal preco;

    /** 1 = ativo, 0 = inativo. */
    private Integer estado;

    private LocalDateTime criadoEm;
    private LocalDateTime atualizadoEm;
}
