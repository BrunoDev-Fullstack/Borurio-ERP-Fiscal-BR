package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeEventoSequencia;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NfeEventoSequenciaMapper {

    String SELECT_COLUMNS = "SELECT id, chave_nfe AS chaveNfe, tipo_evento AS tipoEvento, "
            + "ultimo_nseq_registrado AS ultimoNSeqRegistrado, evento_ativo_id AS eventoAtivoId, "
            + "created_at AS createdAt, updated_at AS updatedAt "
            + "FROM nfe_evento_sequencia ";

    @Select(SELECT_COLUMNS + "WHERE chave_nfe = #{chaveNfe} AND tipo_evento = #{tipoEvento} FOR UPDATE")
    NfeEventoSequencia buscarParaAtualizar(@Param("chaveNfe") String chaveNfe, @Param("tipoEvento") String tipoEvento);

    // Leitura SEM lock -- usada so pra decidir se o bootstrap (rede) e necessario antes de
    // qualquer transacao de reserva. Nunca usada pra decidir o valor real da sequencia (isso e
    // sempre buscarParaAtualizar, sob FOR UPDATE, dentro da transacao de reserva).
    @Select(SELECT_COLUMNS + "WHERE chave_nfe = #{chaveNfe} AND tipo_evento = #{tipoEvento}")
    NfeEventoSequencia buscarSemLock(@Param("chaveNfe") String chaveNfe, @Param("tipoEvento") String tipoEvento);

    @Insert("""
            INSERT INTO nfe_evento_sequencia (chave_nfe, tipo_evento, ultimo_nseq_registrado)
            VALUES (#{chaveNfe}, #{tipoEvento}, #{ultimoNSeqRegistrado})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(NfeEventoSequencia seq);

    @Update("""
            UPDATE nfe_evento_sequencia SET ultimo_nseq_registrado = #{ultimoNSeqRegistrado}
            WHERE chave_nfe = #{chaveNfe} AND tipo_evento = #{tipoEvento}
            """)
    int atualizarUltimoNSeq(@Param("chaveNfe") String chaveNfe, @Param("tipoEvento") String tipoEvento,
                             @Param("ultimoNSeqRegistrado") int ultimoNSeqRegistrado);

    // Gate: ocupa/libera o "candidato ativo" da chave+tipo. Chamado sempre dentro da mesma
    // transacao SERIALIZABLE que ja segura a linha via buscarParaAtualizar, nunca isoladamente
    // (mesmo padrao de NfeSequenciaMapper.ocuparGate/liberarGate do Gate 1).
    @Update("""
            UPDATE nfe_evento_sequencia SET evento_ativo_id = #{eventoAtivoId}
            WHERE chave_nfe = #{chaveNfe} AND tipo_evento = #{tipoEvento}
            """)
    int ocuparGate(@Param("chaveNfe") String chaveNfe, @Param("tipoEvento") String tipoEvento,
                    @Param("eventoAtivoId") Long eventoAtivoId);

    @Update("""
            UPDATE nfe_evento_sequencia SET evento_ativo_id = NULL
            WHERE chave_nfe = #{chaveNfe} AND tipo_evento = #{tipoEvento}
            """)
    int liberarGate(@Param("chaveNfe") String chaveNfe, @Param("tipoEvento") String tipoEvento);
}
