package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeEmissao;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NfeEmissaoMapper {

    String SELECT_COLUMNS = "SELECT id, pedido_id AS pedidoId, empresa_id AS empresaId, "
            + "cnpj_emitente AS cnpjEmitente, modelo, serie, numero_nfe AS numeroNfe, "
            + "chave_nfe AS chaveNfe, estado, cstat, xmotivo, nprot, request_id AS requestId, "
            + "tentativas, transmitido_em AS transmitidoEm, resolvido_em AS resolvidoEm, "
            + "created_at AS createdAt, updated_at AS updatedAt "
            + "FROM nfe_emissao ";

    @Select(SELECT_COLUMNS + "WHERE id = #{id} FOR UPDATE")
    NfeEmissao buscarPorIdParaAtualizar(@Param("id") Long id);

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    NfeEmissao buscarPorId(@Param("id") Long id);

    // ORDER BY id DESC LIMIT 1: retorna o ciclo de nNF mais recente do pedido, seja qual for
    // o estado (inclusive terminal) — a decisao do que fazer com o resultado (retomar,
    // abrir novo ciclo, ou recusar) e do chamador (NfeEmissaoService.abrirCiclo).
    @Select(SELECT_COLUMNS + "WHERE pedido_id = #{pedidoId} ORDER BY id DESC LIMIT 1")
    NfeEmissao buscarUltimaPorPedido(@Param("pedidoId") Long pedidoId);

    @Insert("""
            INSERT INTO nfe_emissao (
                pedido_id, empresa_id, cnpj_emitente, modelo, serie, numero_nfe,
                estado, tentativas
            ) VALUES (
                #{pedidoId}, #{empresaId}, #{cnpjEmitente}, #{modelo}, #{serie}, #{numeroNfe},
                #{estado}, #{tentativas}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(NfeEmissao emissao);

    // Retomada legitima de AGUARDANDO_CORRECAO (retry do mesmo pedido, mesmo numero_nfe) —
    // volta para RESERVADO e incrementa tentativas. Nao mexe em chave_nfe/cstat/xmotivo/nprot:
    // ficam com o valor da tentativa anterior ate a proxima resolucao os sobrescrever.
    @Update("""
            UPDATE nfe_emissao SET
                estado     = 'RESERVADO',
                tentativas = tentativas + 1
            WHERE id = #{id}
            """)
    int retomarComoReservado(@Param("id") Long id);

    // Chave calculada e persistida ANTES da chamada SEFAZ — congela a chave da tentativa em
    // voo, para que uma reconciliacao futura (Gate 3) saiba qual chave consultar.
    @Update("""
            UPDATE nfe_emissao SET
                estado         = 'TRANSMITIDO',
                chave_nfe      = #{chaveNfe},
                transmitido_em = NOW()
            WHERE id = #{id}
            """)
    int marcarTransmitido(@Param("id") Long id, @Param("chaveNfe") String chaveNfe);

    // Reverte uma marcacao de TRANSMITIDO feita por engano — so quando ha certeza local e
    // sincrona de que nada foi de fato transmitido a SEFAZ (ver NfeEmissaoService
    // .reverterParaReservadoPorFalhaLocal). Nao mexe em tentativas (nao e uma tentativa nova,
    // e a mesma tentativa que nunca saiu do Borurio) nem em chave_nfe (sobrescrita na proxima
    // tentativa real).
    @Update("""
            UPDATE nfe_emissao SET
                estado = 'RESERVADO'
            WHERE id = #{id} AND estado = 'TRANSMITIDO'
            """)
    int reverterTransmitidoParaReservado(@Param("id") Long id);

    // resolvidoEm fica a cargo do chamador: preenchido (NOW()) so quando o novo estado e
    // terminal (AUTORIZADO/DENEGADO); null para AGUARDANDO_CORRECAO/PENDENTE_CONFIRMACAO.
    @Update("""
            UPDATE nfe_emissao SET
                estado       = #{estado},
                cstat        = #{cstat, jdbcType=INTEGER},
                xmotivo      = #{xmotivo, jdbcType=VARCHAR},
                nprot        = #{nprot, jdbcType=VARCHAR},
                resolvido_em = #{resolvidoEm, jdbcType=TIMESTAMP}
            WHERE id = #{id}
            """)
    int atualizarResultado(NfeEmissao emissao);
}
