package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeSequencia;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NfeSequenciaMapper {

    // Bloqueia a linha para atualização atômica dentro de @Transactional
    @Select("SELECT id, cnpj_emitente AS cnpjEmitente, serie, ultimo_numero AS ultimoNumero, " +
            "emissao_ativa_id AS emissaoAtivaId, data_atualizacao AS dataAtualizacao " +
            "FROM nfe_sequencia " +
            "WHERE cnpj_emitente = #{cnpjEmitente} AND serie = #{serie} FOR UPDATE")
    NfeSequencia buscarParaAtualizar(@Param("cnpjEmitente") String cnpjEmitente,
                                     @Param("serie") String serie);

    @Insert("INSERT INTO nfe_sequencia (cnpj_emitente, serie, ultimo_numero) VALUES (#{cnpjEmitente}, #{serie}, #{ultimoNumero})")
    void inserir(NfeSequencia seq);

    @Update("UPDATE nfe_sequencia SET ultimo_numero = #{ultimoNumero} " +
            "WHERE cnpj_emitente = #{cnpjEmitente} AND serie = #{serie}")
    void atualizarNumero(NfeSequencia seq);

    // Recovery "gap" (02-09-2026, ABANDONADO / TRANSPORTE_NAO_ENTREGUE) -- avanca ultimo_numero
    // ate EXATAMENTE #{numero}, e so quando ele ainda estiver abaixo disso. A guarda
    // "ultimo_numero < #{numero}" garante os dois invariantes de uma vez: nunca regride e nunca
    // ultrapassa. affectedRows: 1 = avancou; 0 = ja estava >= #{numero} (ou a linha nao existe).
    // Diferente de consumirNumero()/consolidarNumeroParaContingencia(), NAO exige
    // #{numero} == ultimo_numero + 1: um ciclo encerrado sem autorizacao ocupa seu slot
    // (cnpj, modelo, serie, nNF) para sempre via uk_nfe_emissao_numero, entao o gap entre
    // ultimo_numero e esse nNF e esperado (recovery repetido; ou o ciclo foi a primeira reserva
    // da serie e ultimo_numero nunca saiu de 0).
    @Update("UPDATE nfe_sequencia SET ultimo_numero = #{numero} " +
            "WHERE cnpj_emitente = #{cnpjEmitente} AND serie = #{serie} AND ultimo_numero < #{numero}")
    int avancarUltimoNumeroAte(@Param("cnpjEmitente") String cnpjEmitente,
                                @Param("serie") String serie,
                                @Param("numero") int numero);

    // Gate fiscal (V034/Gate 1) — ocupa/libera o "em voo" da serie. Chamado sempre dentro da
    // mesma transacao SERIALIZABLE que ja segura a linha via buscarParaAtualizar (FOR UPDATE),
    // nunca isoladamente.
    @Update("UPDATE nfe_sequencia SET emissao_ativa_id = #{emissaoAtivaId} " +
            "WHERE cnpj_emitente = #{cnpjEmitente} AND serie = #{serie}")
    void ocuparGate(@Param("cnpjEmitente") String cnpjEmitente, @Param("serie") String serie,
                     @Param("emissaoAtivaId") Long emissaoAtivaId);

    @Update("UPDATE nfe_sequencia SET emissao_ativa_id = NULL " +
            "WHERE cnpj_emitente = #{cnpjEmitente} AND serie = #{serie}")
    void liberarGate(@Param("cnpjEmitente") String cnpjEmitente, @Param("serie") String serie);

    // Fase 1 SVC (17-08-2026) -- CAS explicito da troca de gate NORMAL->SVC (Caminho B). Nunca um
    // ocuparGate() generico: a condicao "emissao_ativa_id = normalEsperada" garante que a troca so
    // acontece se o gate ainda pertencer exatamente a NORMAL que esta transacao acabou de travar
    // FOR UPDATE -- affectedRows=0 sinaliza corrida real (gate mudou entre a validacao e a troca),
    // nunca deveria acontecer dado o lock, mas o chamador (NfeContingenciaService) trata como
    // falha explicita em vez de assumir sucesso.
    @Update("UPDATE nfe_sequencia SET emissao_ativa_id = #{emissaoSvcNovaId} " +
            "WHERE cnpj_emitente = #{cnpjEmitente} AND serie = #{serie} " +
            "AND emissao_ativa_id = #{emissaoNormalEsperadaId}")
    int substituirGateParaContingencia(@Param("cnpjEmitente") String cnpjEmitente, @Param("serie") String serie,
                                        @Param("emissaoNormalEsperadaId") Long emissaoNormalEsperadaId,
                                        @Param("emissaoSvcNovaId") Long emissaoSvcNovaId);
}
