package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.OmsIntegrator;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface OmsIntegratorMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   codigo,
                   nome,
                   ativo,
                   criado_em      AS criadoEm,
                   desativado_em  AS desativadoEm
            FROM oms_integrator
            """;

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    OmsIntegrator buscarPorId(@Param("id") Long id);

    @Select(SELECT_COLUMNS + "WHERE codigo = #{codigo}")
    OmsIntegrator buscarPorCodigo(@Param("codigo") String codigo);

    @Select(SELECT_COLUMNS + "WHERE ativo = 1 ORDER BY nome")
    List<OmsIntegrator> listarAtivos();

    @Insert("""
            INSERT INTO oms_integrator (codigo, nome, ativo)
            VALUES (#{codigo}, #{nome}, #{ativo})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(OmsIntegrator integrator);

    @Update("""
            UPDATE oms_integrator
               SET ativo         = #{ativo},
                   desativado_em = #{desativadoEm, jdbcType=TIMESTAMP}
             WHERE id = #{id}
            """)
    int atualizar(OmsIntegrator integrator);
}
