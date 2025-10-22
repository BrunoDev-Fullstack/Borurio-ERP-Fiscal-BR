package br.com.borurio.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * =============================================================================
 * COMPONENTE: StartupListener
 * =============================================================================
 * Responsável por exibir informações essenciais de inicialização
 * logo após o carregamento completo do contexto Spring Boot.
 *
 * Funções:
 *   • Registrar no log os endpoints principais do sistema
 *   • Confirmar o perfil ativo (dev, hom, prd)
 *   • Garantir visibilidade operacional conforme boas práticas DevSecOps
 *
 * Ambiente de uso:
 *   - Executado automaticamente no evento {@link ApplicationReadyEvent}
 *   - Módulo: borurio-web
 * -----------------------------------------------------------------------------
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Projeto: ERP Fiscal Borurio Brasil
 * =============================================================================
 */
@Component
public class StartupListener {

    private static final Logger log = LoggerFactory.getLogger(StartupListener.class);
    private final Environment environment;

    public StartupListener(Environment environment) {
        this.environment = environment;
    }

    /**
     * Método invocado automaticamente após a aplicação estar pronta para uso.
     * Exibe os principais endpoints de monitoramento e documentação.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String activeProfile = String.join(", ", environment.getActiveProfiles());
        log.info("""
                ==============================================================
                ✅  SISTEMA ERP FISCAL BORURIO BRASIL INICIADO
                --------------------------------------------------------------
                🔗  ENDPOINTS PRINCIPAIS DISPONÍVEIS:
                    • Swagger UI:      http://localhost:8080/swagger-ui/index.html
                    • OpenAPI JSON:    http://localhost:8080/v3/api-docs
                    • Actuator Health: http://localhost:8080/actuator/health
                --------------------------------------------------------------
                🌐  Perfil ativo: {}
                📅  Data de inicialização: {}
                --------------------------------------------------------------
                📦  Módulos carregados: core | app | fiscal | web
                🧩  Padrão DevSecOps: segurança • automação • observabilidade
                ==============================================================
                """, activeProfile, java.time.LocalDateTime.now());
    }
}
