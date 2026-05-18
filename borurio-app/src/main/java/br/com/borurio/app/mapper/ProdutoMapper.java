package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.Produto;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ProdutoMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   empresa_id         AS empresaId,
                   codigo,
                   descricao,
                   ncm,
                   cfop,
                   unidade,
                   preco,
                   estado,
                   origem,
                   csosn,
                   estoque,
                   estoque_reservado  AS estoqueReservado,
                   criado_em          AS criadoEm,
                   atualizado_em      AS atualizadoEm
            FROM produto
            """;

    @Select(SELECT_COLUMNS)
    List<Produto> listarTodos();

    @Select(SELECT_COLUMNS + "WHERE estado = 1")
    List<Produto> listarAtivos();

    @Select(SELECT_COLUMNS + "WHERE empresa_id = #{empresaId} ORDER BY descricao")
    List<Produto> listarPorEmpresa(@Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE empresa_id = #{empresaId} AND estado = 1 ORDER BY descricao")
    List<Produto> listarAtivosPorEmpresa(@Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "ORDER BY descricao LIMIT #{limit} OFFSET #{offset}")
    List<Produto> listarTodosPaginado(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM produto")
    long countTodos();

    @Select(SELECT_COLUMNS + "WHERE empresa_id = #{empresaId} ORDER BY descricao LIMIT #{limit} OFFSET #{offset}")
    List<Produto> listarPorEmpresaPaginado(@Param("empresaId") Long empresaId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM produto WHERE empresa_id = #{empresaId}")
    long countPorEmpresa(@Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    Produto buscarPorId(Long id);

    @Select(SELECT_COLUMNS + "WHERE id = #{id} AND empresa_id = #{empresaId}")
    Produto buscarPorIdEEmpresa(@Param("id") Long id, @Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE codigo = #{codigo}")
    Produto buscarPorCodigo(@Param("codigo") String codigo);

    @Select(SELECT_COLUMNS + "WHERE codigo = #{codigo} AND empresa_id = #{empresaId}")
    Produto buscarPorCodigoEEmpresa(@Param("codigo") String codigo, @Param("empresaId") Long empresaId);

    @Insert("""
            INSERT INTO produto (empresa_id, codigo, descricao, ncm, cfop, unidade, preco, estado,
                                 origem, csosn, estoque, criado_em, atualizado_em)
            VALUES (#{empresaId, jdbcType=BIGINT}, #{codigo}, #{descricao}, #{ncm}, #{cfop},
                    #{unidade}, #{preco}, #{estado},
                    #{origem, jdbcType=INTEGER}, #{csosn, jdbcType=VARCHAR},
                    #{estoque, jdbcType=DECIMAL}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(Produto produto);

    @Update("""
            UPDATE produto SET
                codigo        = #{codigo},
                descricao     = #{descricao},
                ncm           = #{ncm},
                cfop          = #{cfop},
                unidade       = #{unidade},
                preco         = #{preco},
                estado        = #{estado},
                origem        = #{origem, jdbcType=INTEGER},
                csosn         = #{csosn, jdbcType=VARCHAR},
                estoque       = #{estoque, jdbcType=DECIMAL},
                atualizado_em = NOW()
            WHERE id = #{id}
            """)
    int atualizar(Produto produto);

    @Update("""
            UPDATE produto
            SET estoque_reservado = estoque_reservado + #{qtd}, atualizado_em = NOW()
            WHERE id = #{id} AND empresa_id = #{empresaId}
              AND (estoque - estoque_reservado) >= #{qtd}
            """)
    int reservarEstoque(@Param("id") Long id,
                        @Param("qtd") java.math.BigDecimal qtd,
                        @Param("empresaId") Long empresaId);

    @Update("""
            UPDATE produto
            SET estoque_reservado = estoque_reservado - #{qtd}, atualizado_em = NOW()
            WHERE id = #{id} AND empresa_id = #{empresaId}
              AND estoque_reservado >= #{qtd}
            """)
    int desfazerReserva(@Param("id") Long id,
                        @Param("qtd") java.math.BigDecimal qtd,
                        @Param("empresaId") Long empresaId);

    @Update("""
            UPDATE produto
            SET estoque           = estoque - #{qtd},
                estoque_reservado = estoque_reservado - #{qtd},
                atualizado_em     = NOW()
            WHERE id = #{id} AND empresa_id = #{empresaId}
              AND estoque >= #{qtd} AND estoque_reservado >= #{qtd}
            """)
    int baixaDefinitiva(@Param("id") Long id,
                        @Param("qtd") java.math.BigDecimal qtd,
                        @Param("empresaId") Long empresaId);

    @Update("""
            UPDATE produto
            SET estoque = estoque + #{qtd}, atualizado_em = NOW()
            WHERE id = #{id} AND empresa_id = #{empresaId}
            """)
    int estornarBaixa(@Param("id") Long id,
                      @Param("qtd") java.math.BigDecimal qtd,
                      @Param("empresaId") Long empresaId);

    @Update("UPDATE produto SET estado = 0, atualizado_em = NOW() WHERE id = #{id}")
    int desativar(Long id);
}
