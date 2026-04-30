package br.com.borurio.fiscal;

import org.springframework.context.annotation.Configuration;

/**
 * Marcador de configuração do módulo Fiscal.
 *
 * Scanning de componentes e mappers é responsabilidade exclusiva de
 * br.com.borurio.web.Application, que já declara:
 *   @ComponentScan("br.com.borurio.fiscal")
 *   @MapperScan("br.com.borurio.fiscal.mapper")
 *
 * Declarar @ComponentScan ou @MapperScan aqui causaria registro duplicado
 * de MapperFactoryBean ("Skipping MapperFactoryBean... Bean already defined").
 */
@Configuration
public class FiscalApplication {
}
