package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.Empresa;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface EmpresaMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   cnpj,
                   razao_social      AS razaoSocial,
                   nome_fantasia     AS nomeFantasia,
                   ie,
                   crt,
                   uf,
                   logradouro,
                   numero,
                   bairro,
                   municipio,
                   codigo_municipio  AS codigoMunicipio,
                   cep,
                   serie_nfe_padrao  AS serieNfePadrao,
                   ativo,
                   criado_em         AS criadoEm,
                   atualizado_em     AS atualizadoEm
            FROM empresa
            """;

    @Select(SELECT_COLUMNS + "ORDER BY razao_social")
    List<Empresa> listarTodas();

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    Empresa buscarPorId(@Param("id") Long id);

    @Select(SELECT_COLUMNS + "WHERE cnpj = #{cnpj}")
    Empresa buscarPorCnpj(@Param("cnpj") String cnpj);

    @Insert("""
            INSERT INTO empresa (
                cnpj, razao_social, nome_fantasia, ie, crt, uf,
                logradouro, numero, bairro, municipio, codigo_municipio, cep,
                serie_nfe_padrao, ativo
            ) VALUES (
                #{cnpj}, #{razaoSocial}, #{nomeFantasia, jdbcType=VARCHAR},
                #{ie, jdbcType=VARCHAR}, #{crt}, #{uf},
                #{logradouro, jdbcType=VARCHAR}, #{numero, jdbcType=VARCHAR},
                #{bairro, jdbcType=VARCHAR}, #{municipio, jdbcType=VARCHAR},
                #{codigoMunicipio, jdbcType=VARCHAR}, #{cep, jdbcType=VARCHAR},
                #{serieNfePadrao}, #{ativo}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(Empresa empresa);

    @Update("""
            UPDATE empresa SET
                razao_social      = #{razaoSocial},
                nome_fantasia     = #{nomeFantasia, jdbcType=VARCHAR},
                ie                = #{ie, jdbcType=VARCHAR},
                crt               = #{crt},
                uf                = #{uf},
                logradouro        = #{logradouro, jdbcType=VARCHAR},
                numero            = #{numero, jdbcType=VARCHAR},
                bairro            = #{bairro, jdbcType=VARCHAR},
                municipio         = #{municipio, jdbcType=VARCHAR},
                codigo_municipio  = #{codigoMunicipio, jdbcType=VARCHAR},
                cep               = #{cep, jdbcType=VARCHAR},
                serie_nfe_padrao  = #{serieNfePadrao},
                ativo             = #{ativo},
                atualizado_em     = NOW()
            WHERE id = #{id}
            """)
    int atualizar(Empresa empresa);

    @Update("UPDATE db_user SET empresa_id = #{empresaId} WHERE empresa_id IS NULL")
    int backfillDbUser(@Param("empresaId") Long empresaId);

    @Update("UPDATE produto SET empresa_id = #{empresaId} WHERE empresa_id IS NULL")
    int backfillProduto(@Param("empresaId") Long empresaId);

    @Update("UPDATE pedido SET empresa_id = #{empresaId} WHERE empresa_id IS NULL")
    int backfillPedido(@Param("empresaId") Long empresaId);
}
