package br.com.borurio.web.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import java.time.LocalDateTime;

/**
 * =============================================================================
 * STARTUP LISTENER – BORURIO ERP FISCAL BR
 * -----------------------------------------------------------------------------
 * Exibe informações detalhadas no log ao inicializar a aplicação.
 * Ajustado para detectar a porta mapeada via Docker Compose (HOST/CONTAINER).
 * =============================================================================
 */
@Slf4j
@Configuration
public class StartupListener {

    private final WebServerApplicationContext webServerAppContext;

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${spring.application.name:borurio-web}")
    private String appName;

    public StartupListener(WebServerApplicationContext webServerAppContext) {
        this.webServerAppContext = webServerAppContext;
    }

    @PostConstruct
    public void onStartup() {
        int port = webServerAppContext.getWebServer().getPort();

        // Detecta a porta externa (host) se estiver em Docker
        String mappedPort = System.getenv("SERVER_PORT_EXTERNAL");
        String effectivePort = (mappedPort != null && !mappedPort.isBlank()) ? mappedPort : String.valueOf(port);

        String startupBanner = String.format("""
                ==============================================================
                ✅  SISTEMA ERP FISCAL BORURIO BRASIL INICIADO
                --------------------------------------------------------------
                🔗  ENDPOINTS PRINCIPAIS DISPONÍVEIS:
                    • Swagger UI:      http://localhost:%s/swagger-ui/index.html
                    • OpenAPI JSON:    http://localhost:%s/v3/api-docs
                    • Actuator Health: http://localhost:%s/actuator/health
                --------------------------------------------------------------
                🌐  Perfil ativo: %s
                📅  Data de inicialização: %s
                --------------------------------------------------------------
                📦  Módulos carregados: core | app | fiscal | web
                🧩  Padrão DevSecOps: segurança • automação • observabilidade
                ==============================================================

                ERP Fiscal Borurio Brasil iniciado com sucesso.
                Perfil ativo: application-dev.yml
                Porta interna: %d | Porta externa: %s
                ==============================================================
                """,
                effectivePort, effectivePort, effectivePort,
                activeProfile, LocalDateTime.now(), port, effectivePort);

        log.info(startupBanner);
    }
}
