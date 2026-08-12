package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeEmissao;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface NfeEmissaoMapper {

    String SELECT_COLUMNS = "SELECT id, pedido_id AS pedidoId, empresa_id AS empresaId, "
            + "cnpj_emitente AS cnpjEmitente, modelo, serie, numero_nfe AS numeroNfe, "
            + "chave_nfe AS chaveNfe, estado, cstat, xmotivo, nprot, request_id AS requestId, "
            + "tentativas, transmitido_em AS transmitidoEm, resolvido_em AS resolvidoEm, "
            + "ultima_consulta_em AS ultimaConsultaEm, tentativas_consulta AS tentativasConsulta, "
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

    // Claim atomico da janela de reconciliacao (Gate 3) — UPDATE condicional unico, sem SELECT
    // FOR UPDATE previo, mesmo padrao ja usado em PedidoMapper.reivindicarParaEmissao (P0.1).
    // O backoff exponencial e calculado inline em SQL a partir de tentativas_consulta ja
    // persistido, com teto em backoffMaximoSegundos — evita ler-decidir-escrever em passos
    // separados, que permitiria duas chamadas concorrentes lerem "expirou" antes de qualquer
    // uma escrever. affectedRows==1 (retorno > 0): esta chamada venceu e pode consultar a SEFAZ.
    // affectedRows==0: estado ja nao e mais pendente, OU o backoff ainda nao venceu, OU outra
    // chamada concorrente venceu a janela um instante antes — nos tres casos a resposta e a
    // mesma, nunca tocar a rede.
    @Update("""
            UPDATE nfe_emissao SET
                ultima_consulta_em  = #{agora},
                tentativas_consulta = tentativas_consulta + 1
            WHERE id = #{id}
              AND estado IN ('TRANSMITIDO', 'PENDENTE_CONFIRMACAO')
              AND (
                    ultima_consulta_em IS NULL
                    OR ultima_consulta_em <= DATE_SUB(
                        #{agora},
                        INTERVAL LEAST(
                            #{backoffMaximoSegundos},
                            #{backoffInicialSegundos} * POW(#{backoffMultiplicador}, tentativas_consulta)
                        ) SECOND
                    )
              )
            """)
    int tentarAdquirirJanelaConsulta(@Param("id") Long id,
                                      @Param("agora") LocalDateTime agora,
                                      @Param("backoffInicialSegundos") int backoffInicialSegundos,
                                      @Param("backoffMultiplicador") double backoffMultiplicador,
                                      @Param("backoffMaximoSegundos") int backoffMaximoSegundos);

    // Gate de cancelamento (12-08-2026): projecao do estado fiscal apos evento homologado --
    // SO transiciona a partir de AUTORIZADO, e NUNCA toca cstat/xmotivo/nprot/resolvido_em (a
    // evidencia da autorizacao original fica intocada; a evidencia do cancelamento em si vive em
    // nfe_evento). Guard WHERE estado='AUTORIZADO' torna a chamada idempotente por natureza --
    // uma segunda chamada sobre uma linha ja CANCELADO nao afeta nada (affectedRows=0).
    @Update("""
            UPDATE nfe_emissao SET
                estado = 'CANCELADO'
            WHERE id = #{id} AND estado = 'AUTORIZADO'
            """)
    int marcarCancelado(@Param("id") Long id);
}
