package br.com.borurio.fiscal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Configuração principal do módulo Fiscal do ERP Borurio Brasil.
 *
 * Função:
 * - Registrar os mappers MyBatis e componentes fiscais (NF-e, SEFAZ, certificados, logs);
 * - Permitir que o módulo `borurio-web` importe automaticamente o contexto fiscal;
 * - Centralizar o escaneamento de beans sem criar um contexto Spring Boot separado.
 *
 * Padrões aplicados:
 * - DevSecOps: configurações externas via YAML e variáveis de ambiente (.env);
 * - Clean Architecture: camada fiscal modularizada e desacoplada da API REST;
 * - Compatibilidade total com Spring Boot 3.3.x e Java 17+.
 *
 * Observação:
 * Esta classe NÃO possui método main(), pois o ponto de entrada oficial
 * é a classe `br.com.borurio.web.Application` no módulo `borurio-web`.
 */
@Configuration
@ComponentScan(basePackages = "br.com.borurio.fiscal")
@MapperScan(basePackages = "br.com.borurio.fiscal.mapper")
public class FiscalApplication {
    // Classe de configuração do módulo fiscal.
    // Todos os beans e mappers são registrados automaticamente.
}
