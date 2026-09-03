package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NcmSyncLog;
import java.util.List;

/**
 * Serviço responsável pela manipulação de logs de sincronização NCM.
 */
public interface NcmSyncLogService {
    List<NcmSyncLog> listarUltimos();
    void registrar(NcmSyncLog log);
}
