package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NcmSyncLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * Mapper responsável pelos registros de log da sincronização de NCM.
 */
public interface NcmSyncLogMapper {

    @Select("SELECT id, arquivo, registros_importados AS registrosImportados, data_execucao AS dataExecucao, usuario_responsavel AS usuarioResponsavel " +
            "FROM ncm_sync_log ORDER BY data_execucao DESC LIMIT 50")
    List<NcmSyncLog> listarUltimos();

    @Insert("INSERT INTO ncm_sync_log (arquivo, registros_importados, data_execucao, usuario_responsavel) " +
            "VALUES (#{arquivo}, #{registrosImportados}, #{dataExecucao}, #{usuarioResponsavel})")
    void registrarLog(NcmSyncLog log);
}
