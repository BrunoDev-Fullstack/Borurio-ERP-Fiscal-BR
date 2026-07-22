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
                   motivo_revogacao AS motivoRevogacao,
                   versao
            FROM oms_fiscal_authorization
            """;

    /** Localiza o slot de autorização por cliente OMS — sem empresa_id (V028: token por cliente OMS). */
    @Select(SELECT_COLUMNS + """
            WHERE integrator_id = #{integratorId}
              AND codigo_oms    = #{codigoOms}
            """)
    OmsFiscalAuthorization buscarPorSlot(@Param("integratorId") Long integratorId,
                                         @Param("codigoOms")    String codigoOms);

    /** Localiza por jti — usado na validação de token e verificação de revogação. */
    @Select(SELECT_COLUMNS + "WHERE jti = #{jti}")
    OmsFiscalAuthorization buscarPorJti(@Param("jti") String jti);

    /** Localiza por id, sem lock — uso administrativo (ex.: comparação em replay de idempotência). */
    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    OmsFiscalAuthorization buscarPorId(@Param("id") Long id);

    /** Localiza por id com SELECT ... FOR UPDATE — serializa revogação/rotação concorrentes na mesma linha. */
    @Select(SELECT_COLUMNS + "WHERE id = #{id} FOR UPDATE")
    OmsFiscalAuthorization buscarPorIdParaAtualizar(@Param("id") Long id);

    @Insert("""
            INSERT INTO oms_fiscal_authorization
                (empresa_id, integrator_id, codigo_oms, jti, token_expira_em, emitido_em)
            VALUES
                (#{empresaId}, #{integratorId}, #{codigoOms}, #{jti}, #{tokenExpiraEm}, #{emitidoEm})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(OmsFiscalAuthorization auth);

    /** Atualiza jti e validade do token na reautorização (POST /fiscal-authorizations, casos B/C/D). */
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

    /**
     * Revoga administrativamente — só transiciona se ainda não estava revogada (WHERE revogado_em
     * IS NULL). Revogação repetida retorna 0 linhas afetadas: o serviço interpreta isso como
     * já-revogada e responde 200 idempotente, sem incrementar versao novamente.
     */
    @Update("""
            UPDATE oms_fiscal_authorization
               SET revogado_em      = #{revogadoEm},
                   motivo_revogacao = #{motivo, jdbcType=VARCHAR},
                   versao           = versao + 1
             WHERE id = #{id}
               AND revogado_em IS NULL
            """)
    int revogar(@Param("id")         Long id,
                @Param("revogadoEm") LocalDateTime revogadoEm,
                @Param("motivo")     String motivo);

    /**
     * Rotaciona (novo jti/validade, limpa revogado_em/motivo) condicionado à versao esperada —
     * concorrência otimista. 0 linhas afetadas = versao divergente (AUTHORIZATION_CHANGED).
     * Reativa uma autorização revogada quando expectedVersion corresponder à versao pós-revogação.
     */
    @Update("""
            UPDATE oms_fiscal_authorization
               SET jti              = #{jtiNovo},
                   emitido_em       = #{emitidoEmNovo},
                   token_expira_em  = #{tokenExpiraEmNovo},
                   revogado_em      = NULL,
                   motivo_revogacao = NULL,
                   versao           = versao + 1
             WHERE id = #{id}
               AND versao = #{expectedVersion}
            """)
    int rotacionar(@Param("id")                Long id,
                   @Param("jtiNovo")           String jtiNovo,
                   @Param("emitidoEmNovo")     LocalDateTime emitidoEmNovo,
                   @Param("tokenExpiraEmNovo") LocalDateTime tokenExpiraEmNovo,
                   @Param("expectedVersion")   Long expectedVersion);
}
