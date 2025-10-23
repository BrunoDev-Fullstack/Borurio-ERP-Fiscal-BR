package br.com.borurio.web.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * =============================================================================
 * STARTUP LISTENER — BORURIO ERP FISCAL BR
 * =============================================================================
 * Componente responsável por exibir informações detalhadas de inicialização
 * da aplicação ao subir o container ou o contexto Spring Boot.
 *
 * Finalidades:
 *   - Confirmar o perfil ativo (dev, hom, prd).
 *   - Exibir as principais URLs de monitoramento (Swagger, Actuator).
 *   - Detectar e logar a porta efetiva de execução (Docker host/container).
 *
 * Boas práticas aplicadas:
 *   - Utiliza @PostConstruct para garantir execução após o contexto inicializar.
 *   - Compatível com execução local e via Docker Compose.
 *   - Evita caracteres não-ASCII para logs compatíveis com qualquer terminal.
 *
 * =============================================================================
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: 23/10/2025
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

    /**
     * Executado automaticamente após a inicialização do contexto Spring.
     * Exibe um banner informativo de status e endpoints principais.
     */
    @PostConstruct
    public void onStartup() {
        int internalPort = webServerAppContext.getWebServer().getPort();

        // Detecta a porta externa (HOST) mapeada pelo Docker Compose
        String mappedPort = System.getenv("SERVER_PORT_EXTERNAL");
        String effectivePort = (mappedPort != null && !mappedPort.isBlank())
                ? mappedPort
                : String.valueOf(internalPort);

        String banner = String.format("""
                =====================================================================
                 SISTEMA ERP FISCAL BORURIO BRASIL INICIADO
                ---------------------------------------------------------------------
                 APLICAÇÃO: %s
                 PERFIL ATIVO: %s
                 DATA DE INICIALIZAÇÃO: %s
                 PORTA INTERNA: %d
                 PORTA EXTERNA (HOST): %s
                ---------------------------------------------------------------------
                 ENDPOINTS PRINCIPAIS DISPONÍVEIS:
                   - Swagger UI:      http://localhost:%s/swagger-ui/index.html
                   - OpenAPI JSON:    http://localhost:%s/v3/api-docs
                   - Actuator Health: http://localhost:%s/actuator/health
                ---------------------------------------------------------------------
                 MÓDULOS CARREGADOS: core | app | fiscal | web
                 PADRÃO DEVSECOPS: segurança • automação • observabilidade
                =====================================================================
                """,
                appName,
                activeProfile,
                LocalDateTime.now(),
                internalPort,
                effectivePort,
                effectivePort,
                effectivePort,
                effectivePort
        );

        log.info(banner);
    }
}
