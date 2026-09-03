package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.Cliente;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface ClienteMapper {

    // =========================================================================
    // QUERIES REUTILIZÁVEIS
    // =========================================================================

    String SELECT_COLUMNS = """
            SELECT id,
                   empresa_id         AS empresaId,
                   tipo_pessoa        AS tipoPessoa,
                   cnpj,
                   cpf,
                   razao_social       AS razaoSocial,
                   nome_fantasia      AS nomeFantasia,
                   inscricao_estadual AS inscricaoEstadual,
                   nome,
                   email,
                   telefone,
                   logradouro,
                   numero,
                   complemento,
                   bairro,
                   codigo_municipio   AS codigoMunicipio,
                   municipio,
                   uf,
                   cep,
                   estado,
                   criado_em          AS criadoEm,
                   atualizado_em      AS atualizadoEm
            FROM cliente
            """;

    // =========================================================================
    // LEITURA
    // =========================================================================

    @Select(SELECT_COLUMNS)
    List<Cliente> listarTodos();

    @Select(SELECT_COLUMNS + "WHERE empresa_id = #{empresaId}")
    List<Cliente> listarPorEmpresa(@Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    Cliente buscarPorId(Long id);

    @Select(SELECT_COLUMNS + "WHERE cnpj = #{cnpj}")
    Cliente buscarPorCnpj(@Param("cnpj") String cnpj);

    // =========================================================================
    // ESCRITA
    // =========================================================================

    @Insert("""
            INSERT INTO cliente (
                empresa_id,
                tipo_pessoa, cnpj, cpf, razao_social, nome_fantasia,
                inscricao_estadual, nome, email, telefone,
                logradouro, numero, complemento, bairro,
                codigo_municipio, municipio, uf, cep,
                estado, criado_em, atualizado_em
            ) VALUES (
                #{empresaId, jdbcType=BIGINT},
                #{tipoPessoa}, #{cnpj}, #{cpf}, #{razaoSocial}, #{nomeFantasia},
                #{inscricaoEstadual}, #{nome}, #{email}, #{telefone},
                #{logradouro}, #{numero}, #{complemento}, #{bairro},
                #{codigoMunicipio}, #{municipio}, #{uf}, #{cep},
                #{estado}, NOW(), NOW()
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(Cliente cliente);

    @Update("""
            UPDATE cliente SET
                tipo_pessoa        = #{tipoPessoa},
                cnpj               = #{cnpj},
                cpf                = #{cpf},
                razao_social       = #{razaoSocial},
                nome_fantasia      = #{nomeFantasia},
                inscricao_estadual = #{inscricaoEstadual},
                nome               = #{nome},
                email              = #{email},
                telefone           = #{telefone},
                logradouro         = #{logradouro},
                numero             = #{numero},
                complemento        = #{complemento},
                bairro             = #{bairro},
                codigo_municipio   = #{codigoMunicipio},
                municipio          = #{municipio},
                uf                 = #{uf},
                cep                = #{cep},
                estado             = #{estado},
                atualizado_em      = NOW()
            WHERE id = #{id}
            """)
    int atualizar(Cliente cliente);

    @Update("UPDATE cliente SET estado = 0, atualizado_em = NOW() WHERE id = #{id}")
    int desativar(Long id);
}
