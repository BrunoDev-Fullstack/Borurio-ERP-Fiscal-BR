package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeEventoIdempotencia;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface NfeEventoIdempotenciaMapper {

    String SELECT_COLUMNS = "SELECT id, idempotency_key AS idempotencyKey, empresa_id AS empresaId, "
            + "pedido_id AS pedidoId, tipo_evento AS tipoEvento, conteudo_hash AS conteudoHash, "
            + "nfe_evento_id AS nfeEventoId, estado_resultado AS estadoResultado, "
            + "cstat_resultado AS cstatResultado, xmotivo_resultado AS xmotivoResultado, "
            + "nprot_resultado AS nprotResultado, dh_reg_evento_resultado AS dhRegEventoResultado, "
            + "resolvido_em AS resolvidoEm, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM nfe_evento_idempotencia ";

    @Select(SELECT_COLUMNS + "WHERE idempotency_key = #{idempotencyKey}")
    NfeEventoIdempotencia buscarPorIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    NfeEventoIdempotencia buscarPorId(@Param("id") Long id);

    // Rechecagem com lock -- usada DENTRO da transacao de reserva, depois do FOR UPDATE em
    // nfe_evento_sequencia, pra fechar a mesma corrida que OmsAuthorizationAdminService.
    // tentarRotacionar ja fecha: duas chamadas com a MESMA chave passando pela checagem inicial
    // (fora de transacao) antes de qualquer uma comitar.
    @Select(SELECT_COLUMNS + "WHERE idempotency_key = #{idempotencyKey} FOR UPDATE")
    NfeEventoIdempotencia buscarPorIdempotencyKeyParaAtualizar(@Param("idempotencyKey") String idempotencyKey);

    @Insert("""
            INSERT INTO nfe_evento_idempotencia (
                idempotency_key, empresa_id, pedido_id, tipo_evento, conteudo_hash,
                nfe_evento_id, estado_resultado
            ) VALUES (
                #{idempotencyKey}, #{empresaId}, #{pedidoId}, #{tipoEvento}, #{conteudoHash},
                #{nfeEventoId}, #{estadoResultado}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(NfeEventoIdempotencia idem);

    @Update("""
            UPDATE nfe_evento_idempotencia SET
                estado_resultado         = #{estadoResultado},
                cstat_resultado          = #{cstatResultado, jdbcType=INTEGER},
                xmotivo_resultado        = #{xmotivoResultado, jdbcType=VARCHAR},
                nprot_resultado          = #{nprotResultado, jdbcType=VARCHAR},
                dh_reg_evento_resultado  = #{dhRegEventoResultado, jdbcType=VARCHAR},
                resolvido_em             = #{resolvidoEm, jdbcType=TIMESTAMP}
            WHERE id = #{id}
            """)
    int atualizarResultado(NfeEventoIdempotencia idem);

    // Claim de transmissao -- so quem conseguir esta transicao pode de fato chamar a SEFAZ.
    @Update("""
            UPDATE nfe_evento_idempotencia SET estado_resultado = 'TRANSMITIDO'
            WHERE id = #{id} AND estado_resultado = 'PREPARADO'
            """)
    int marcarTransmitido(@Param("id") Long id);
}
