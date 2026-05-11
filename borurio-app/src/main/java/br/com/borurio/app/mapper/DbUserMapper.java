package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.DbUser;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper responsável pelas operações sobre a tabela de usuários (db_user).
 *
 * Padrões aplicados:
 * - MyBatis oficial (org.apache.ibatis.annotations)
 * - SQL explícito via anotações (consultas simples)
 * - Consultas complexas devem ser definidas em XML separado
 *
 * Compatibilidade:
 * - Spring Boot 3.3.x
 * - MySQL 8.x
 * - Java 17
 *
 * Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack
 * Graduação: Cyber Security
 * Desde: Sprint Fiscal 2.2 (Refatoração e nacionalização ERP)
 */
public interface DbUserMapper {

    String SELECT_COLS = """
            SELECT id,
                   empresa_id        AS empresaId,
                   nome,
                   email,
                   senha,
                   role,
                   ativo,
                   data_criacao      AS dataCriacao,
                   data_atualizacao  AS dataAtualizacao
            FROM db_user
            """;

    @Select(SELECT_COLS)
    List<DbUser> findAll();

    @Select(SELECT_COLS + "WHERE empresa_id = #{empresaId}")
    List<DbUser> findByEmpresaId(@Param("empresaId") Long empresaId);

    @Select(SELECT_COLS + "WHERE id = #{id}")
    DbUser findById(@Param("id") Long id);

    @Select(SELECT_COLS + "WHERE email = #{email}")
    DbUser findByEmail(@Param("email") String email);

    @Select("SELECT COUNT(*) FROM db_user")
    int count();

    @Insert("""
            INSERT INTO db_user (empresa_id, nome, email, senha, role, ativo, data_criacao, data_atualizacao)
            VALUES (#{empresaId, jdbcType=BIGINT}, #{nome}, #{email}, #{senha}, #{role}, #{ativo}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(DbUser user);

    @Update("""
            UPDATE db_user
               SET nome             = #{nome},
                   role             = #{role},
                   ativo            = #{ativo},
                   empresa_id       = #{empresaId, jdbcType=BIGINT},
                   data_atualizacao = NOW()
             WHERE id = #{id}
            """)
    int updatePerfil(DbUser user);

    @Update("UPDATE db_user SET senha = #{senha}, data_atualizacao = NOW() WHERE id = #{id}")
    int updateSenha(@Param("id") Long id, @Param("senha") String senha);

    @Update("UPDATE db_user SET ativo = false, data_atualizacao = NOW() WHERE id = #{id}")
    int deactivate(@Param("id") Long id);

    @Delete("DELETE FROM db_user WHERE id = #{id}")
    void deleteById(@Param("id") Long id);
}
