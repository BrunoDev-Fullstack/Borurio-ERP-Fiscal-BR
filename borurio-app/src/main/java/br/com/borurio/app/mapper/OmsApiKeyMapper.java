package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.OmsApiKey;
import org.apache.ibatis.annotations.*;

public interface OmsApiKeyMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   integrator_id  AS integratorId,
                   descricao,
                   chave_hash     AS chaveHash,
                   ativo,
                   criado_em      AS criadoEm,
                   expira_em      AS expiraEm,
                   revogado_em    AS revogadoEm
            FROM oms_api_key
            """;

    /**
     * Retorna a chave se e somente se: ativo=1, não revogada, não expirada.
     * Todos os filtros ficam no SQL para não depender apenas da camada de serviço.
     */
    @Select(SELECT_COLUMNS + """
            WHERE chave_hash = #{chaveHash}
              AND ativo       = 1
              AND revogado_em IS NULL
              AND (expira_em IS NULL OR expira_em > NOW())
            """)
    OmsApiKey findAtivaPorHash(@Param("chaveHash") String chaveHash);

    @Insert("""
            INSERT INTO oms_api_key (integrator_id, descricao, chave_hash, ativo,
                                     expira_em)
            VALUES (#{integratorId}, #{descricao, jdbcType=VARCHAR}, #{chaveHash}, #{ativo},
                    #{expiraEm, jdbcType=TIMESTAMP})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(OmsApiKey apiKey);

    @Update("""
            UPDATE oms_api_key
               SET ativo       = 0,
                   revogado_em = #{revogadoEm}
             WHERE id = #{id}
            """)
    int revogar(@Param("id") Long id, @Param("revogadoEm") java.time.LocalDateTime revogadoEm);
}
