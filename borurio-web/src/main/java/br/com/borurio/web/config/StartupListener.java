package br.com.borurio.web.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * =============================================================================
 * STARTUP LISTENER — BORURIO ERP FISCAL BR
 * =============================================================================
 * Listener responsável apenas por exibir informações de inicialização
 * após o contexto Spring Boot estar totalmente pronto.
 *
 * Padrão correto:
 *  • Executa somente após ApplicationReadyEvent
 *  • Não interfere no ciclo de vida do servidor
 *  • Seguro para ambientes DEV / PRD / Docker
 *
 * =============================================================================
 */
@Slf4j
@Component
public class StartupListener implements ApplicationListener<ApplicationReadyEvent> {

    private final ApplicationContext applicationContext;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${spring.application.name:borurio-web}")
    private String appName;

    public StartupListener(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int port = -1;
        String mappedPort = System.getenv("SERVER_PORT_EXTERNAL");
        String effectivePort;

        if (applicationContext instanceof WebServerApplicationContext webCtx) {
            port = webCtx.getWebServer().getPort();
        }

        effectivePort = (mappedPort != null && !mappedPort.isBlank())
                ? mappedPort
                : (port > 0 ? String.valueOf(port) : "8080");

        String startupBanner = String.format("""
                =====================================================================
                SISTEMA ERP FISCAL BORURIO BRASIL INICIADO
                ---------------------------------------------------------------------
                ENDPOINTS DISPONÍVEIS:
                    - Swagger UI:      http://localhost:%s/swagger-ui/index.html
                    - OpenAPI JSON:    http://localhost:%s/v3/api-docs
                    - Actuator Health: http://localhost:%s/actuator/health
                ---------------------------------------------------------------------
                PERFIL ATIVO: %s
                DATA DE INICIALIZAÇÃO: %s
                ---------------------------------------------------------------------
                MÓDULOS CARREGADOS: core | app | fiscal | web
                PADRÃO DEVSECOPS: segurança | automação | observabilidade
                ---------------------------------------------------------------------
                NOME DA APLICAÇÃO: %s
                PORTA INTERNA: %s
                PORTA EXTERNA (HOST): %s
                =====================================================================
                """,
                effectivePort, effectivePort, effectivePort,
                activeProfile, LocalDateTime.now(),
                appName, (port > 0 ? port : "N/A"), effectivePort
        );

        log.info(startupBanner);
    }
}
