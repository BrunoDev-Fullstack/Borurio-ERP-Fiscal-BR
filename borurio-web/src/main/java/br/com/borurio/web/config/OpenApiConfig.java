package br.com.borurio.web.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * CONFIGURAÇÃO LEGADA — OpenApiConfig (DESATIVADA)
 * -----------------------------------------------------------------------------
 * Esta classe fazia a configuração inicial do Swagger / OpenAPI 3.0.
 * Atualmente, foi substituída pelo arquivo:
 *    → {@link br.com.borurio.web.config.SwaggerConfig}
 *
 * Motivo da desativação:
 *   - Evitar conflito de múltiplos @OpenAPIDefinition no projeto.
 *   - Centralizar o esquema de autenticação JWT no SwaggerConfig.
 *
 * Mantida apenas como referência documental e para extensão futura.
 * -----------------------------------------------------------------------------
 * Projeto: ERP Fiscal Borurio BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Última revisão: 05/11/2025
 * =============================================================================
 */
@Configuration
public class OpenApiConfig {

    /**
     * Bean auxiliar para manter o link de documentação externa
     * (repositório GitHub do projeto Borurio).
     *
     * @return instância configurada de {@link OpenAPI}
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .externalDocs(new ExternalDocumentation()
                        .description("Portal do Projeto — ERP Fiscal Borurio Brasil")
                        .url("https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR"));
    }
}
