package br.com.borurio.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * CONFIGURAÇÃO: SefazProperties
 * -----------------------------------------------------------------------------
 * Responsável por mapear as URLs da SEFAZ conforme o ambiente ativo
 * (dev | hom | prd), utilizando application-*.yml.
 *
 * OBSERVAÇÃO IMPORTANTE:
 * - Esta classe NÃO resolve ambiente
 * - O ambiente é controlado via spring.profiles.active
 * - Cada profile define suas próprias URLs da SEFAZ
 *
 * Prefixo de configuração:
 * sefaz.urls.*
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sefaz.urls")
public class SefazProperties {

    /** URL do serviço de autorização de NF-e */
    private String autorizacao;

    /** URL do serviço de retorno de autorização */
    private String retorno;

    /** URL do serviço de consulta da NF-e */
    private String consulta;

    /** URL do serviço de status do serviço SEFAZ */
    private String status;

    /** URL do serviço de inutilização de numeração */
    private String inutilizacao;

    /** URL do serviço de recepção de eventos (cancelamento, CCe, etc.) */
    private String recepcaoEvento;
}
