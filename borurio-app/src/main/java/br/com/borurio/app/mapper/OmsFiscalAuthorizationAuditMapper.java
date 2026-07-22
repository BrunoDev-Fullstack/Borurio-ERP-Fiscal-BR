package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.OmsFiscalAuthorizationAudit;
import org.apache.ibatis.annotations.*;

public interface OmsFiscalAuthorizationAuditMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   auth_id                  AS authId,
                   evento,
                   jti_anterior             AS jtiAnterior,
                   jti_novo                 AS jtiNovo,
                   emitido_em_novo          AS emitidoEmNovo,
                   token_expira_em_novo     AS tokenExpiraEmNovo,
                   versao_anterior          AS versaoAnterior,
                   versao_nova              AS versaoNova,
                   motivo_codigo            AS motivoCodigo,
                   motivo_detalhe           AS motivoDetalhe,
                   executado_por_usuario_id AS executadoPorUsuarioId,
                   idempotency_key          AS idempotencyKey,
                   request_id               AS requestId,
                   criado_em                AS criadoEm
            FROM oms_fiscal_authorization_audit
            """;

    /** Localiza por Idempotency-Key — chave única, usada para detectar replay administrativo. */
    @Select(SELECT_COLUMNS + "WHERE idempotency_key = #{idempotencyKey}")
    OmsFiscalAuthorizationAudit buscarPorIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO oms_fiscal_authorization_audit
                (auth_id, evento, jti_anterior, jti_novo, emitido_em_novo, token_expira_em_novo,
                 versao_anterior, versao_nova, motivo_codigo, motivo_detalhe,
                 executado_por_usuario_id, idempotency_key, request_id)
            VALUES
                (#{authId}, #{evento}, #{jtiAnterior}, #{jtiNovo}, #{emitidoEmNovo}, #{tokenExpiraEmNovo},
                 #{versaoAnterior}, #{versaoNova}, #{motivoCodigo}, #{motivoDetalhe, jdbcType=VARCHAR},
                 #{executadoPorUsuarioId}, #{idempotencyKey}, #{requestId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(OmsFiscalAuthorizationAudit audit);
}
