package br.com.borurio.fiscal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * CONFIGURAÇÃO PRINCIPAL — Módulo Fiscal (NF-e, NCM, Auditoria)
 * =============================================================================
 * Finalidade:
 *   - Registrar mappers do MyBatis exclusivos do módulo fiscal;
 *   - Habilitar escaneamento de services, utils e validadores;
 *   - Integrar o módulo Fiscal ao contexto principal (borurio-web);
 *   - Garantir que o módulo NÃO inicializa seu próprio servidor Web.
 *
 * Observação:
 *   Não contém método main().
 *   O ponto de entrada único da aplicação é:
 *       br.com.borurio.web.Application
 *
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * Revisão: 02/12/2025
 * =============================================================================
 */
@Configuration
@ComponentScan(basePackages = "br.com.borurio.fiscal")
@MapperScan(basePackages = "br.com.borurio.fiscal.mapper")
public class FiscalApplication {
    // Classe de configuração do módulo Fiscal.
    // Todos os beans são carregados no contexto principal do ERP.
}
