package br.com.borurio.web.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * =============================================================================
 * EVENTO DE INICIALIZAÇÃO DO SISTEMA
 * -----------------------------------------------------------------------------
 * Exibe no log as URLs principais (Swagger, Actuator, Health, etc)
 * após o servidor Tomcat estar completamente iniciado.
 * =============================================================================
 */
@Component
public class StartupListener {

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        System.out.println("""
                ==============================================================
                ✅ Sistema ERP Fiscal Borurio Brasil iniciado
                --------------------------------------------------------------
                🔗 Endpoints principais disponíveis:
                   • Swagger UI:      http://localhost:8080/swagger-ui/index.html
                   • OpenAPI JSON:    http://localhost:8080/v3/api-docs
                   • Actuator Health: http://localhost:8080/actuator/health
                --------------------------------------------------------------
                Perfil ativo: dev
                ==============================================================
                """);
    }
}
