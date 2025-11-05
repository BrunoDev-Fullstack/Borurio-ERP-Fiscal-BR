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
 * STARTUP LISTENER — BORURIO ERP FISCAL BR
 * -----------------------------------------------------------------------------
 * Exibe informações detalhadas de inicialização da aplicação no log,
 * incluindo portas internas e externas, perfil ativo e data/hora do startup.
 *
 * Características:
 * - Compatível com qualquer tipo de contexto (Servlet, CLI, Test, etc.).
 * - Detecta porta mapeada via Docker Compose (SERVER_PORT_EXTERNAL).
 * - Padrão DevSecOps: rastreabilidade, segurança e observabilidade.
 *
 * Projeto: ERP Fiscal Borurio BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Última revisão: 04/11/2025
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

    /**
     * Executa automaticamente após a inicialização do contexto Spring Boot.
     * Exibe informações completas sobre o ambiente e configuração atual.
     */
    @PostConstruct
    public void onStartup() {
        int port = -1;
        String mappedPort = System.getenv("SERVER_PORT_EXTERNAL");
        String effectivePort;

        // Tenta detectar a porta se o contexto for Web
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
