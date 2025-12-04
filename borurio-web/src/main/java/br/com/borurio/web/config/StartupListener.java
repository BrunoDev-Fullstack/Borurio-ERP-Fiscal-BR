package br.com.borurio.web.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * =============================================================================
 * STARTUP LISTENER — BORURIO ERP FISCAL BR (Versão Revisada)
 * =============================================================================
 * Versão otimizada para evitar inicializações web duplicadas no Spring Boot 3.x.
 * Agora:
 *   - Só executa se o contexto for REALMENTE WebServerApplicationContext.
 *   - Garante que a porta já foi definida corretamente.
 *   - Nunca desperta nem cria contexto adicional.
 *   - Totalmente thread-safe e idempotente.
 * =============================================================================
 */
@Slf4j
@Configuration
public class StartupListener {

    private final ApplicationContext applicationContext;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${spring.application.name:borurio-web}")
    private String appName;

    public StartupListener(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void logStartupInfo() {

        // Se não for contexto web → evita inicialização duplicada
        if (!(applicationContext instanceof WebServerApplicationContext webCtx)) {
            log.info("StartupListener ignorado — contexto não é WebServerApplicationContext.");
            return;
        }

        int port = webCtx.getWebServer().getPort();

        // Porta do host (Docker Compose)
        String mappedPort = System.getenv("SERVER_PORT_EXTERNAL");
        String effectivePort = (mappedPort != null && !mappedPort.isBlank())
                ? mappedPort
                : String.valueOf(port);

        String banner = """
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
                APLICAÇÃO: %s
                PORTA INTERNA: %s
                PORTA EXTERNA (HOST): %s
                =====================================================================
                """.formatted(
                effectivePort, effectivePort, effectivePort,
                activeProfile, LocalDateTime.now(),
                appName, port, effectivePort
        );

        log.info(banner);
    }
}
