package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.Produto;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ProdutoMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   empresa_id    AS empresaId,
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
                   criado_em     AS criadoEm,
                   atualizado_em AS atualizadoEm
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

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    Produto buscarPorId(Long id);

    @Select(SELECT_COLUMNS + "WHERE id = #{id} AND empresa_id = #{empresaId}")
    Produto buscarPorIdEEmpresa(@Param("id") Long id, @Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE codigo = #{codigo}")
    Produto buscarPorCodigo(@Param("codigo") String codigo);

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

    @Update("UPDATE produto SET estoque = estoque - #{qtd}, atualizado_em = NOW() WHERE id = #{id} AND estoque >= #{qtd}")
    int baixarEstoque(@Param("id") Long id, @Param("qtd") java.math.BigDecimal qtd);

    @Update("UPDATE produto SET estado = 0, atualizado_em = NOW() WHERE id = #{id}")
    int desativar(Long id);
}
