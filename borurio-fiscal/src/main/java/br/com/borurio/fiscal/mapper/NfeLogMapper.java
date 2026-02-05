package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper responsável pelas operações SQL da tabela <b>nfe_log</b>.
 *
 * Este componente implementa o mapeamento entre a entidade {@link NfeLog}
 * e a base de dados MySQL, utilizando o MyBatis oficial.
 *
 * Tabela: nfe_log
 */
@Mapper
public interface NfeLogMapper {

    @Insert("""
        INSERT INTO nfe_log
            (chave_nfe, tipo_evento, descricao, status, xml_envio, xml_retorno,
             data_evento, cnpj_emitente, usuario)
        VALUES
            (#{chaveNfe}, #{tipoEvento}, #{descricao}, #{status}, #{xmlEnvio}, #{xmlRetorno},
             #{dataEvento}, #{cnpjEmitente}, #{usuario})
        """)
    int insertLog(NfeLog log);

    @Select("""
        SELECT id, chave_nfe, tipo_evento, descricao, status,
               xml_envio, xml_retorno, data_evento, cnpj_emitente, usuario
        FROM nfe_log
        ORDER BY data_evento DESC
        """)
    List<NfeLog> findAll();

    @Select("""
        SELECT id, chave_nfe, tipo_evento, descricao, status,
               xml_envio, xml_retorno, data_evento, cnpj_emitente, usuario
        FROM nfe_log
        WHERE chave_nfe = #{chaveNfe}
        ORDER BY data_evento DESC
        """)
    List<NfeLog> findByChave(@Param("chaveNfe") String chaveNfe);

    @Select("""
        SELECT id, chave_nfe, tipo_evento, descricao, status,
               xml_envio, xml_retorno, data_evento, cnpj_emitente, usuario
        FROM nfe_log
        WHERE id = #{id}
        """)
    NfeLog findById(@Param("id") Long id);

    @Delete("""
        DELETE FROM nfe_log
        WHERE data_evento < (NOW() - INTERVAL #{diasAntigos} DAY)
        """)
    int deleteAntigos(@Param("diasAntigos") int diasAntigos);
}
