package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.Produto;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ProdutoMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   codigo,
                   descricao,
                   ncm,
                   cfop,
                   unidade,
                   preco,
                   estado,
                   criado_em     AS criadoEm,
                   atualizado_em AS atualizadoEm
            FROM produto
            """;

    @Select(SELECT_COLUMNS)
    List<Produto> listarTodos();

    @Select(SELECT_COLUMNS + "WHERE estado = 1")
    List<Produto> listarAtivos();

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    Produto buscarPorId(Long id);

    @Select(SELECT_COLUMNS + "WHERE codigo = #{codigo}")
    Produto buscarPorCodigo(@Param("codigo") String codigo);

    @Insert("""
            INSERT INTO produto (codigo, descricao, ncm, cfop, unidade, preco, estado, criado_em, atualizado_em)
            VALUES (#{codigo}, #{descricao}, #{ncm}, #{cfop}, #{unidade}, #{preco}, #{estado}, NOW(), NOW())
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
                atualizado_em = NOW()
            WHERE id = #{id}
            """)
    int atualizar(Produto produto);

    @Update("UPDATE produto SET estado = 0, atualizado_em = NOW() WHERE id = #{id}")
    int desativar(Long id);
}
