package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.OmsFiscalAuthorization;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

public interface OmsFiscalAuthorizationMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   empresa_id       AS empresaId,
                   integrator_id    AS integratorId,
                   codigo_oms       AS codigoOms,
                   jti,
                   token_expira_em  AS tokenExpiraEm,
                   emitido_em       AS emitidoEm,
                   atualizado_em    AS atualizadoEm,
                   revogado_em      AS revogadoEm,
                   motivo_revogacao AS motivoRevogacao
            FROM oms_fiscal_authorization
            """;

    /** Localiza o slot de autorização de uma empresa por integrador. */
    @Select(SELECT_COLUMNS + """
            WHERE empresa_id    = #{empresaId}
              AND integrator_id = #{integratorId}
              AND codigo_oms    = #{codigoOms}
            """)
    OmsFiscalAuthorization buscarPorSlot(@Param("empresaId")    Long empresaId,
                                         @Param("integratorId") Long integratorId,
                                         @Param("codigoOms")    String codigoOms);

    /** Localiza por jti — usado na validação de token e verificação de revogação. */
    @Select(SELECT_COLUMNS + "WHERE jti = #{jti}")
    OmsFiscalAuthorization buscarPorJti(@Param("jti") String jti);

    @Insert("""
            INSERT INTO oms_fiscal_authorization
                (empresa_id, integrator_id, codigo_oms, jti, token_expira_em)
            VALUES
                (#{empresaId}, #{integratorId}, #{codigoOms}, #{jti}, #{tokenExpiraEm})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(OmsFiscalAuthorization auth);

    /** Atualiza jti e validade do token na reautorização. */
    @Update("""
            UPDATE oms_fiscal_authorization
               SET jti             = #{jti},
                   token_expira_em = #{tokenExpiraEm},
                   revogado_em     = NULL,
                   motivo_revogacao = NULL
             WHERE id = #{id}
            """)
    int atualizarToken(@Param("id")            Long id,
                       @Param("jti")           String jti,
                       @Param("tokenExpiraEm") LocalDateTime tokenExpiraEm);

    /** Revoga administrativamente um token sem emitir novo. */
    @Update("""
            UPDATE oms_fiscal_authorization
               SET revogado_em      = #{revogadoEm},
                   motivo_revogacao = #{motivo, jdbcType=VARCHAR}
             WHERE id = #{id}
            """)
    int revogar(@Param("id")         Long id,
                @Param("revogadoEm") LocalDateTime revogadoEm,
                @Param("motivo")     String motivo);
}
