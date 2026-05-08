package br.com.borurio.app.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Produto {

    private Long id;
    private Long empresaId;

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

    /** Origem da mercadoria: 0=Nacional, 1–8=Importada (AT 1/2013). */
    private Integer origem;

    /** CSOSN para Simples Nacional (CRT=1): 102,103,300,400,500,900… */
    private String csosn;

    /** Quantidade em estoque (baixa após NF-e autorizada). */
    private java.math.BigDecimal estoque;

    private LocalDateTime criadoEm;
    private LocalDateTime atualizadoEm;
}
