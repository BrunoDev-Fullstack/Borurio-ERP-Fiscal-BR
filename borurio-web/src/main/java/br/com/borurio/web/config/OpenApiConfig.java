package br.com.borurio.web.config;

/**
 * =============================================================================
 * CONFIGURAÇÃO LEGADA — OpenApiConfig (DESATIVADA / SOMENTE DOCUMENTAÇÃO)
 * =============================================================================
 * Esta classe representava a configuração original do Swagger/OpenAPI.
 *
 * Situação atual:
 *  - NÃO registra nenhum bean no contexto Spring.
 *  - Mantida apenas para referência documental e futura expansão.
 *
 * A configuração oficial e única válida do OpenAPI é:
 *      → br.com.borurio.web.config.SwaggerConfig
 *
 * Motivo:
 *  - SpringDoc 2.x exige um único bean OpenAPI.
 *  - Evitamos o erro:
 *        "expected single bean of type OpenAPI but found 2"
 *
 * =============================================================================
 * Projeto: ERP Fiscal Borurio BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Última revisão: 02/12/2025
 * =============================================================================
 */
public class OpenApiConfig {
    // Classe propositalmente vazia.
    // Nenhum @Configuration e nenhum @Bean devem existir aqui.
}
