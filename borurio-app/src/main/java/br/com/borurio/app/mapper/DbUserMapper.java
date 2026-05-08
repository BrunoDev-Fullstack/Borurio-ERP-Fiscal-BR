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

    /**
     * Retorna todos os usuários cadastrados.
     *
     * @return lista de usuários.
     */
    @Select("SELECT id, empresa_id AS empresaId, nome, email, senha, ativo, data_criacao AS dataCriacao, data_atualizacao AS dataAtualizacao FROM db_user")
    List<DbUser> findAll();

    /**
     * Busca um usuário por e-mail.
     *
     * @param email e-mail do usuário.
     * @return objeto DbUser correspondente ou null se não encontrado.
     */
    @Select("SELECT id, empresa_id AS empresaId, nome, email, senha, ativo, data_criacao AS dataCriacao, data_atualizacao AS dataAtualizacao FROM db_user WHERE email = #{email}")
    DbUser findByEmail(@Param("email") String email);

    /**
     * Insere um novo usuário no banco.
     *
     * @param user entidade DbUser a ser persistida.
     */
    @Insert("""
            INSERT INTO db_user (empresa_id, nome, email, senha, ativo, data_criacao, data_atualizacao)
            VALUES (#{empresaId, jdbcType=BIGINT}, #{nome}, #{email}, #{senha}, #{ativo}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(DbUser user);

    @Select("SELECT COUNT(*) FROM db_user")
    int count();

    /**
     * Atualiza os dados de um usuário existente.
     *
     * @param user entidade DbUser atualizada.
     */
    @Update("""
            UPDATE db_user
               SET nome = #{nome},
                   senha = #{senha},
                   ativo = #{ativo},
                   data_atualizacao = NOW()
             WHERE id = #{id}
            """)
    void update(DbUser user);

    /**
     * Remove um usuário pelo ID.
     *
     * @param id identificador único do usuário.
     */
    @Delete("DELETE FROM db_user WHERE id = #{id}")
    void deleteById(@Param("id") Long id);
}
