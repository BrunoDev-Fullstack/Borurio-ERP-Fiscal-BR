package br.com.borurio.web.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * =============================================================================
 * STARTUP LISTENER — BORURIO ERP FISCAL BR
 * -----------------------------------------------------------------------------
 * Exibe informações de inicialização da aplicação de forma segura,
 * SEM interferir no ciclo de vida do servidor web.
 *
 * Princípios:
 * - Não acessa WebServer diretamente
 * - Não cria nem inicializa connectors
 * - Compatível com Spring Boot 3.x
 *
 * Projeto: ERP Fiscal Borurio BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * =============================================================================
 */
@Slf4j
@Configuration
public class StartupListener {

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${spring.application.name:borurio-web}")
    private String appName;

    @Value("${server.port:8080}")
    private String serverPort;

    @PostConstruct
    public void onStartup() {

        // Porta externa (Docker / Infra), se existir
        String externalPort = System.getenv("SERVER_PORT_EXTERNAL");
        String effectivePort = (externalPort != null && !externalPort.isBlank())
                ? externalPort
                : serverPort;

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
                PORTA CONFIGURADA (SPRING): %s
                PORTA EXTERNA (HOST): %s
                =====================================================================
                """,
                effectivePort,
                effectivePort,
                effectivePort,
                activeProfile,
                LocalDateTime.now(),
                appName,
                serverPort,
                effectivePort
        );

        log.info(startupBanner);
    }
}
