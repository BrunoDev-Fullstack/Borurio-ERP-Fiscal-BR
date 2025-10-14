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
 * Padrões técnicos aplicados:
 * <ul>
 *   <li>SQL explícito com colunas nomeadas para maior legibilidade e segurança.</li>
 *   <li>Uso de {@link Param} para evitar injeção de SQL (SQL Injection).</li>
 *   <li>Sem dependências externas não auditadas.</li>
 *   <li>Codificação UTF-8 sem BOM.</li>
 *   <li>Compatível com auditoria fiscal NF-e 4.00.</li>
 * </ul>
 *
 * Tabela: nfe_log
 * Campos: id, chave_nfe, tipo_evento, descricao, status, xml_envio, xml_retorno, data_evento, cnpj_emitente, usuario
 */
@Mapper
public interface NfeLogMapper {

    /**
     * Insere um novo registro de log fiscal (evento NF-e) no banco de dados.
     *
     * @param log objeto {@link NfeLog} contendo os dados de auditoria fiscal.
     * @return número de linhas afetadas (1 em caso de sucesso).
     */
    @Insert("""
        INSERT INTO nfe_log
            (chave_nfe, tipo_evento, descricao, status, xml_envio, xml_retorno,
             data_evento, cnpj_emitente, usuario)
        VALUES
            (#{chaveNfe}, #{tipoEvento}, #{descricao}, #{status}, #{xmlEnvio}, #{xmlRetorno},
             #{dataEvento}, #{cnpjEmitente}, #{usuario})
        """)
    int insertLog(NfeLog log);

    /**
     * Retorna todos os registros de log fiscal, em ordem decrescente de data.
     *
     * @return lista de objetos {@link NfeLog} representando todos os logs armazenados.
     */
    @Select("""
        SELECT id, chave_nfe, tipo_evento, descricao, status,
               xml_envio, xml_retorno, data_evento, cnpj_emitente, usuario
        FROM nfe_log
        ORDER BY data_evento DESC
        """)
    List<NfeLog> findAll();

    /**
     * Busca todos os eventos fiscais associados a uma NF-e específica.
     *
     * @param chaveNfe chave única da NF-e (44 dígitos).
     * @return lista de registros de log vinculados à chave informada.
     */
    @Select("""
        SELECT id, chave_nfe, tipo_evento, descricao, status,
               xml_envio, xml_retorno, data_evento, cnpj_emitente, usuario
        FROM nfe_log
        WHERE chave_nfe = #{chaveNfe}
        ORDER BY data_evento DESC
        """)
    List<NfeLog> findByChave(@Param("chaveNfe") String chaveNfe);

    /**
     * Busca um log fiscal específico com base em seu identificador.
     *
     * @param id identificador único do registro.
     * @return objeto {@link NfeLog} correspondente ao ID informado.
     */
    @Select("""
        SELECT id, chave_nfe, tipo_evento, descricao, status,
               xml_envio, xml_retorno, data_evento, cnpj_emitente, usuario
        FROM nfe_log
        WHERE id = #{id}
        """)
    NfeLog findById(@Param("id") Long id);

    /**
     * Remove registros antigos de log, com base em um limite de retenção definido.
     * Essa operação é utilizada em rotinas de limpeza automática e conformidade com LGPD.
     *
     * @param diasAntigos número de dias a partir do qual os logs devem ser excluídos.
     * @return total de registros removidos.
     */
    @Delete("""
        DELETE FROM nfe_log
        WHERE data_evento < (NOW() - INTERVAL #{diasAntigos} DAY)
        """)
    int deleteAntigos(@Param("diasAntigos") int diasAntigos);
}
