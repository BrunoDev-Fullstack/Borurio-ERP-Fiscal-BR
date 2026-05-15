package br.com.borurio.web.scheduler;

import br.com.borurio.fiscal.service.NfeLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NfeLogRetencaoScheduler {

    private static final Logger log = LoggerFactory.getLogger(NfeLogRetencaoScheduler.class);

    private final NfeLogService nfeLogService;

    @Value("${nfe.log.retencao-dias:365}")
    private int retencaoDias;

    public NfeLogRetencaoScheduler(NfeLogService nfeLogService) {
        this.nfeLogService = nfeLogService;
    }

    @Scheduled(cron = "${nfe.log.retencao.cron:0 0 2 * * ?}")
    public void executar() {
        log.info("[NfeLogRetencao] Iniciando limpeza de logs com mais de {} dias", retencaoDias);
        try {
            int removidos = nfeLogService.deleteAntigos(retencaoDias);
            log.info("[NfeLogRetencao] Limpeza concluída | registros removidos={}", removidos);
        } catch (Exception e) {
            log.error("[NfeLogRetencao] Falha na limpeza de logs | erro={}", e.getMessage());
        }
    }
}
