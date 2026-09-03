package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeSequenciaAuditoria;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface NfeSequenciaAuditoriaMapper {

    @Insert("""
            INSERT INTO nfe_sequencia_auditoria (
                cnpj_emitente, serie_anterior, serie_atual,
                proximo_numero_anterior, proximo_numero_atual,
                origem, cliente_oms, request_id, aplicado
            ) VALUES (
                #{cnpjEmitente}, #{serieAnterior, jdbcType=VARCHAR}, #{serieAtual},
                #{proximoNumeroAnterior, jdbcType=INTEGER}, #{proximoNumeroAtual},
                #{origem}, #{clienteOms, jdbcType=VARCHAR}, #{requestId, jdbcType=VARCHAR}, #{aplicado}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void inserir(NfeSequenciaAuditoria auditoria);
}
