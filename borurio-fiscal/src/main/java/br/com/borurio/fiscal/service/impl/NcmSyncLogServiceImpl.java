package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NcmSyncLog;
import br.com.borurio.fiscal.mapper.NcmSyncLogMapper;
import br.com.borurio.fiscal.service.NcmSyncLogService;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * Implementação do serviço de logs de sincronização NCM.
 */
@Service
public class NcmSyncLogServiceImpl implements NcmSyncLogService {

    private final NcmSyncLogMapper mapper;

    public NcmSyncLogServiceImpl(NcmSyncLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<NcmSyncLog> listarUltimos() {
        return mapper.listarUltimos();
    }

    @Override
    public void registrar(NcmSyncLog log) {
        mapper.registrarLog(log);
    }
}
