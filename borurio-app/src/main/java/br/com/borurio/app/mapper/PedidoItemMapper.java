package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.PedidoItem;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface PedidoItemMapper {

    @Insert("""
            INSERT INTO pedido_item (
                pedido_id, produto_id,
                quantidade, valor_unitario, valor_total,
                codigo_produto, descricao, ncm, cfop, unidade, origem, csosn
            ) VALUES (
                #{pedidoId}, #{produtoId},
                #{quantidade}, #{valorUnitario}, #{valorTotal},
                #{codigoProduto}, #{descricao}, #{ncm}, #{cfop}, #{unidade},
                #{origem, jdbcType=INTEGER}, #{csosn}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(PedidoItem item);

    @Select("""
            SELECT id,
                   pedido_id       AS pedidoId,
                   produto_id      AS produtoId,
                   quantidade,
                   valor_unitario  AS valorUnitario,
                   valor_total     AS valorTotal,
                   codigo_produto  AS codigoProduto,
                   descricao,
                   ncm,
                   cfop,
                   unidade,
                   origem,
                   csosn
            FROM pedido_item
            WHERE pedido_id = #{pedidoId}
            """)
    List<PedidoItem> listarPorPedido(@Param("pedidoId") Long pedidoId);

    @Delete("DELETE FROM pedido_item WHERE pedido_id = #{pedidoId}")
    int deletarPorPedido(@Param("pedidoId") Long pedidoId);

    /**
     * Correção controlada (03-09-2026, V1) — só a descrição do item é editável nesta versão.
     * {@code pedido_id} no WHERE garante que o item pertence mesmo ao pedido informado (evita
     * corrigir item de outro pedido por id incorreto/malicioso). rowsAffected=0 significa item
     * inexistente ou não pertencente a este pedido — o chamador decide o erro.
     */
    @Update("""
            UPDATE pedido_item SET
                descricao = #{descricao}
            WHERE id = #{itemId}
            AND pedido_id = #{pedidoId}
            """)
    int atualizarDescricao(@Param("itemId") Long itemId,
                           @Param("pedidoId") Long pedidoId,
                           @Param("descricao") String descricao);
}
