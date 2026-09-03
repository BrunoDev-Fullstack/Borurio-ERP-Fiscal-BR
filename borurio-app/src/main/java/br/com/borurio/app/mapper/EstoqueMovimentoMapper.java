package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.EstoqueMovimento;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface EstoqueMovimentoMapper {

    @Insert("""
            INSERT INTO estoque_movimento (
                produto_id, empresa_id, tipo, quantidade,
                referencia_tipo, referencia_id, observacao, criado_por, criado_em
            ) VALUES (
                #{produtoId}, #{empresaId}, #{tipo}, #{quantidade},
                #{referenciaTipo, jdbcType=VARCHAR}, #{referenciaId, jdbcType=BIGINT},
                #{observacao, jdbcType=VARCHAR}, #{criadoPor, jdbcType=VARCHAR}, NOW()
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(EstoqueMovimento movimento);

    @Select("""
            SELECT id,
                   produto_id      AS produtoId,
                   empresa_id      AS empresaId,
                   tipo,
                   quantidade,
                   referencia_tipo AS referenciaTipo,
                   referencia_id   AS referenciaId,
                   observacao,
                   criado_por      AS criadoPor,
                   criado_em       AS criadoEm
            FROM estoque_movimento
            WHERE produto_id = #{produtoId} AND empresa_id = #{empresaId}
            ORDER BY criado_em DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<EstoqueMovimento> listarPorProduto(@Param("produtoId") Long produtoId,
                                            @Param("empresaId") Long empresaId,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);
}
