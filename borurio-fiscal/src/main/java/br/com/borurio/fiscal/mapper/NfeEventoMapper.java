package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeEvento;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface NfeEventoMapper {

    String SELECT_COLUMNS = "SELECT id, pedido_id AS pedidoId, emissao_id AS emissaoId, "
            + "empresa_id AS empresaId, cnpj_emitente AS cnpjEmitente, chave_nfe AS chaveNfe, "
            + "tipo_evento AS tipoEvento, n_seq_evento AS nSeqEvento, id_evento AS idEvento, "
            + "estado, cstat, xmotivo, nprot, fora_do_prazo AS foraDoPrazo, justificativa, "
            + "dh_evento AS dhEvento, payload_hash AS payloadHash, resolucao_origem AS resolucaoOrigem, "
            + "transmitido_em AS transmitidoEm, resolvido_em AS resolvidoEm, "
            + "ultima_consulta_em AS ultimaConsultaEm, tentativas_consulta AS tentativasConsulta, "
            + "created_at AS createdAt, updated_at AS updatedAt "
            + "FROM nfe_evento ";

    @Select(SELECT_COLUMNS + "WHERE id = #{id} FOR UPDATE")
    NfeEvento buscarPorIdParaAtualizar(@Param("id") Long id);

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    NfeEvento buscarPorId(@Param("id") Long id);

    // Linha atual do evento logico para esta chave+tipo. Para cancelamento (110111), nSeqEvento e
    // FIXO em 1 -- a identidade fiscal e sempre chaveNfe+110111+1, nunca incrementada (regra
    // oficial distinta de CC-e/110110, que permite multiplas correcoes sequenciais). ORDER BY
    // n_seq_evento DESC LIMIT 1 mantido generico para tipos de evento futuros que legitimamente
    // incrementem nSeqEvento (CC-e) -- decisao de incrementar ou nao e do chamador (NfeEventoService),
    // nunca deste mapper.
    @Select(SELECT_COLUMNS + "WHERE chave_nfe = #{chaveNfe} AND tipo_evento = #{tipoEvento} "
            + "ORDER BY n_seq_evento DESC LIMIT 1")
    NfeEvento buscarUltimaTentativa(@Param("chaveNfe") String chaveNfe, @Param("tipoEvento") String tipoEvento);

    @Select(SELECT_COLUMNS + "WHERE pedido_id = #{pedidoId} AND tipo_evento = #{tipoEvento} "
            + "ORDER BY n_seq_evento DESC LIMIT 1")
    NfeEvento buscarUltimaTentativaPorPedido(@Param("pedidoId") Long pedidoId, @Param("tipoEvento") String tipoEvento);

    // Claim estrutural: a UNIQUE KEY (chave_nfe, tipo_evento, n_seq_evento) e quem decide --
    // duas chamadas concorrentes tentando abrir a MESMA identidade de evento pela primeira vez
    // colidem aqui (uma insere, a outra recebe violacao de constraint e trata como "ja existe/
    // esta em andamento", nunca como erro de dado). Nunca usar INSERT ... ON DUPLICATE KEY --
    // a intencao e falhar explicitamente para o chamador decidir, nao mesclar silenciosamente.
    @Insert("""
            INSERT INTO nfe_evento (
                pedido_id, emissao_id, empresa_id, cnpj_emitente, chave_nfe, tipo_evento,
                n_seq_evento, id_evento, estado, justificativa
            ) VALUES (
                #{pedidoId}, #{emissaoId, jdbcType=BIGINT}, #{empresaId}, #{cnpjEmitente}, #{chaveNfe}, #{tipoEvento},
                #{nSeqEvento}, #{idEvento}, #{estado}, #{justificativa, jdbcType=VARCHAR}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserirPreparado(NfeEvento evento);

    // Claim de transmissao: so quem conseguir esta transicao (affectedRows==1) pode de fato
    // chamar a SEFAZ. Cobre o caso em que duas threads leem a MESMA linha ja existente (retomada
    // pos-crash de um PREPARADO anterior) e tentam transmitir ao mesmo tempo -- a UNIQUE KEY do
    // insert nao protege esse caso, porque a linha ja existe.
    @Update("""
            UPDATE nfe_evento SET
                estado         = 'TRANSMITIDO',
                dh_evento      = #{dhEvento},
                payload_hash   = #{payloadHash},
                transmitido_em = NOW()
            WHERE id = #{id} AND estado = 'PREPARADO'
            """)
    int marcarTransmitido(@Param("id") Long id, @Param("dhEvento") String dhEvento,
                           @Param("payloadHash") String payloadHash);

    // Reabre a MESMA linha (mesma identidade fiscal, mesmo nSeqEvento) para uma nova tentativa
    // apos rejeicao -- nunca insere linha nova para cancelamento (nSeqEvento e fixo em 1). A
    // evidencia da tentativa anterior (cstat/xmotivo/nprot/xml de envio e retorno) ja esta
    // preservada em NfeLog no momento em que cada SOAP e enviado (mesmo padrao ja usado por
    // nfe_emissao -- "historico bruto por tentativa continua em NfeLog"); esta linha e o estado
    // ATUAL do evento logico, nao um diario. cstat/xmotivo/nprot da rejeicao anterior sao
    // sobrescritos apenas quando a nova tentativa for resolvida (atualizarResultado), nunca antes.
    // Reabre a linha para uma nova tentativa: persiste a justificativa CORRENTE (nunca deixa a
    // antiga da tentativa rejeitada) e zera todos os campos de resolucao/reconciliacao/transporte
    // da tentativa anterior -- nunca deixa cstat/xmotivo/nprot de uma REJEICAO passada visivel numa
    // linha que voltou a ficar PREPARADO/TRANSMITIDO/PENDENTE_CONFIRMACAO (achado de banca,
    // 12-08-2026: sem isso, uma tentativa nova ainda nao resolvida mostraria dados da rejeicao
    // anterior como se fossem o estado atual). Seguro apagar: a evidencia da tentativa rejeitada
    // ja esta preservada em NfeLog (gravado a cada round-trip real por
    // NfeCancelamentoServiceImpl.transmitirEvento) antes desta linha ser reaberta.
    // dh_evento/payload_hash/transmitido_em TAMBEM sao zerados (2a rodada de banca, 12-08-2026):
    // marcarTransmitido() os sobrescreve no caminho feliz, mas se houver crash entre esta UPDATE e
    // marcarTransmitido(), a linha ficaria PREPARADO carregando dados de transporte da tentativa
    // ANTERIOR -- nunca deixar essa janela existir, mesmo que hoje nada leia esses campos em
    // PREPARADO.
    @Update("""
            UPDATE nfe_evento SET
                estado               = 'PREPARADO',
                justificativa        = #{justificativa},
                cstat                = NULL,
                xmotivo              = NULL,
                nprot                = NULL,
                fora_do_prazo        = 0,
                resolucao_origem     = NULL,
                resolvido_em         = NULL,
                ultima_consulta_em   = NULL,
                tentativas_consulta  = 0,
                dh_evento            = NULL,
                payload_hash         = NULL,
                transmitido_em       = NULL
            WHERE id = #{id} AND estado = 'REJEITADO'
            """)
    int retomarAposRejeicao(@Param("id") Long id, @Param("justificativa") String justificativa);

    // resolvidoEm preenchido pelo chamador (NOW()) apenas quando o estado e terminal
    // (REGISTRADO/REJEITADO); null para PENDENTE_CONFIRMACAO. Idempotente por si so nao e --
    // quem garante "so aplica uma vez" e o chamador conferindo Estados.isTerminal(estadoAtual)
    // antes de chamar (mesmo padrao de NfeEmissaoService.aplicarNovoEstado).
    @Update("""
            UPDATE nfe_evento SET
                estado            = #{estado},
                cstat             = #{cstat, jdbcType=INTEGER},
                xmotivo           = #{xmotivo, jdbcType=VARCHAR},
                nprot             = #{nprot, jdbcType=VARCHAR},
                fora_do_prazo     = #{foraDoPrazo},
                resolucao_origem  = #{resolucaoOrigem, jdbcType=VARCHAR},
                resolvido_em      = #{resolvidoEm, jdbcType=TIMESTAMP}
            WHERE id = #{id}
            """)
    int atualizarResultado(NfeEvento evento);

    // Claim atomico da janela de reconciliacao -- mesmo desenho de
    // NfeEmissaoMapper.tentarAdquirirJanelaConsulta (Gate 3): UPDATE condicional unico, backoff
    // exponencial calculado inline em SQL, nunca ler-decidir-escrever em passos separados.
    @Update("""
            UPDATE nfe_evento SET
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
}
