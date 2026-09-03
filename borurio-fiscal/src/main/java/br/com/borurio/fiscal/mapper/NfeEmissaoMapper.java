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
            + "tp_emis AS tpEmis, autorizador_destino AS autorizadorDestino, "
            + "emissao_origem_id AS emissaoOrigemId, dh_cont AS dhCont, "
            + "x_just_contingencia AS xJustContingencia, "
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

    // Recovery administrativo (02-09-2026): encerra um ciclo em AGUARDANDO_CORRECAO cujo dado
    // de origem nao pode mais ser corrigido. SO transiciona a partir de AGUARDANDO_CORRECAO e
    // apenas quando nao ha protocolo SEFAZ (nprot IS NULL) -- um ciclo com protocolo teve destino
    // real, nunca e abandonavel. NUNCA toca cstat/xmotivo (a evidencia da rejeicao fica intacta)
    // nem nfe_sequencia (o gate e liberado pelo servico, sem consumirNumero). Guard WHERE torna a
    // chamada idempotente por natureza: uma segunda chamada sobre uma linha ja ABANDONADO nao
    // afeta nada (affectedRows=0).
    @Update("""
            UPDATE nfe_emissao SET
                estado       = 'ABANDONADO',
                resolvido_em = NOW()
            WHERE id = #{id}
              AND estado = 'AGUARDANDO_CORRECAO'
              AND nprot IS NULL
            """)
    int marcarAbandonado(@Param("id") Long id);

    // Recovery administrativo (02-09-2026): a tentativa de transmissao foi comprovadamente
    // rejeitada no transporte/gateway antes de chegar ao autorizador (ex.: HTTP 403 do proxy,
    // resposta HTML em vez de SOAP) -- nenhuma NF-e existe fiscalmente. SO transiciona a partir de
    // TRANSMITIDO/PENDENTE_CONFIRMACAO, apenas quando nprot IS NULL e tentativas_consulta = 0
    // (se ja houve consulta a SEFAZ, existe um cStat real e este recovery nao pode sobrepor).
    // A checagem de evidencia em nfe_documento (n_prot/dh_recbto/xml_protocolo) e do servico,
    // nao daqui. NUNCA toca cstat/xmotivo/chave_nfe (evidencia do 403) nem nfe_sequencia (o gate
    // e liberado pelo servico, sem consumirNumero). Guard WHERE torna a chamada idempotente.
    @Update("""
            UPDATE nfe_emissao SET
                estado       = 'TRANSPORTE_NAO_ENTREGUE',
                resolvido_em = NOW()
            WHERE id = #{id}
              AND estado IN ('TRANSMITIDO', 'PENDENTE_CONFIRMACAO')
              AND nprot IS NULL
              AND tentativas_consulta = 0
            """)
    int marcarTransporteNaoEntregue(@Param("id") Long id);

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

    // -------------------------------------------------------------------------
    // Fase 1 SVC (17-08-2026) -- persistencia/ciclo de substituicao, sem transporte.
    // -------------------------------------------------------------------------

    // Insert dedicado da linha filha (Caminho B) -- nunca reaproveita inserir(), que continua
    // servindo so a abertura normal de ciclo (tp_emis/autorizador_destino ficam nos defaults da
    // coluna '1'/'NORMAL' quando inserir() e usado). emissao_origem_id aponta pra NORMAL
    // substituida -- uk_nfe_emissao_origem (V038) garante, no banco, que uma NORMAL so pode ser
    // substituida uma vez.
    @Insert("""
            INSERT INTO nfe_emissao (
                pedido_id, empresa_id, cnpj_emitente, modelo, serie, numero_nfe,
                estado, tentativas, tp_emis, autorizador_destino, emissao_origem_id,
                dh_cont, x_just_contingencia
            ) VALUES (
                #{pedidoId}, #{empresaId}, #{cnpjEmitente}, #{modelo}, #{serie}, #{numeroNfe},
                #{estado}, #{tentativas}, #{tpEmis}, #{autorizadorDestino}, #{emissaoOrigemId},
                #{dhCont}, #{xJustContingencia}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserirContingencia(NfeEmissao emissao);

    // Uso interno de NfeEmissaoService.aplicarNovoEstado -- checa, sob lock, se esta emissao foi
    // substituida (existe uma linha filha com emissao_origem_id = este id). SEMPRE chamado antes
    // de qualquer short-circuit de isTerminal -- a prova de substituicao nunca pode ser ofuscada
    // por um estado terminal ja gravado (ver banca 17-08-2026, achado de late-NORMAL).
    @Select(SELECT_COLUMNS + "WHERE emissao_origem_id = #{emissaoOrigemId} FOR UPDATE")
    NfeEmissao buscarPorOrigemIdParaAtualizar(@Param("emissaoOrigemId") Long emissaoOrigemId);

    // Leitura simples, sem lock -- uso operacional/futuro (ex.: localizar a filha de uma NORMAL
    // substituida fora de um fluxo transacional de escrita).
    @Select(SELECT_COLUMNS + "WHERE emissao_origem_id = #{emissaoOrigemId}")
    NfeEmissao buscarPorOrigemId(@Param("emissaoOrigemId") Long emissaoOrigemId);

    // Evidencia fiscal de uma NORMAL substituida (late-NORMAL) -- NUNCA toca nfe_sequencia/Pedido/
    // Estoque, essa e a garantia estrutural inteira deste metodo. Guard idempotente pelo mesmo
    // padrao de marcarCancelado: so escreve a partir de TRANSMITIDO/PENDENTE_CONFIRMACAO
    // (affectedRows=0 numa segunda chamada -- o chamador decide, comparando a tupla fiscal
    // completa, se e replay identico ou divergencia a auditar; este metodo nunca sobrescreve).
    @Update("""
            UPDATE nfe_emissao SET
                estado       = #{estado},
                cstat        = #{cstat, jdbcType=INTEGER},
                xmotivo      = #{xmotivo, jdbcType=VARCHAR},
                nprot        = #{nprot, jdbcType=VARCHAR},
                resolvido_em = NOW()
            WHERE id = #{id} AND estado IN ('TRANSMITIDO', 'PENDENTE_CONFIRMACAO')
            """)
    int aplicarEvidenciaSubstituida(@Param("id") Long id, @Param("estado") String estado,
                                     @Param("cstat") Integer cstat, @Param("xmotivo") String xmotivo,
                                     @Param("nprot") String nprot);
}
