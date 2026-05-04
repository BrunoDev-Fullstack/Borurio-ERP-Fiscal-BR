package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeSequencia;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NfeSequenciaMapper {

    // Bloqueia a linha para atualização atômica dentro de @Transactional
    @Select("SELECT id, cnpj_emitente AS cnpjEmitente, serie, ultimo_numero AS ultimoNumero, " +
            "data_atualizacao AS dataAtualizacao " +
            "FROM nfe_sequencia " +
            "WHERE cnpj_emitente = #{cnpjEmitente} AND serie = #{serie} FOR UPDATE")
    NfeSequencia buscarParaAtualizar(@Param("cnpjEmitente") String cnpjEmitente,
                                     @Param("serie") String serie);

    @Insert("INSERT INTO nfe_sequencia (cnpj_emitente, serie, ultimo_numero) VALUES (#{cnpjEmitente}, #{serie}, #{ultimoNumero})")
    void inserir(NfeSequencia seq);

    @Update("UPDATE nfe_sequencia SET ultimo_numero = #{ultimoNumero} " +
            "WHERE cnpj_emitente = #{cnpjEmitente} AND serie = #{serie}")
    void atualizarNumero(NfeSequencia seq);
}
