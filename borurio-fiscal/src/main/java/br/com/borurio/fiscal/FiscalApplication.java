package br.com.borurio.fiscal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Classe de configuração principal do módulo Fiscal do ERP Borurio Brasil.
 *
 * Finalidade:
 *  - Registrar automaticamente os mappers do MyBatis;
 *  - Habilitar o escaneamento de componentes (services, utils, handlers);
 *  - Integrar o módulo Fiscal ao contexto principal do ERP (borurio-web);
 *  - Evitar inicialização isolada (sem criar um contexto Spring Boot próprio).
 *
 * Boas práticas aplicadas:
 *  - Clean Architecture: separação clara entre camadas fiscal e web;
 *  - DevSecOps: suporte a configurações externas via YAML e variáveis .env;
 *  - Compatibilidade com Spring Boot 3.3.x e Java 17;
 *  - Modularidade: cada módulo define apenas sua configuração e dependências diretas.
 *
 * Observação:
 *  Este módulo é carregado automaticamente pelo Spring Boot através do
 *  `ComponentScan` e `MapperScan` quando importado pelo módulo `borurio-web`.
 *  Ele não contém método main(), pois o ponto de entrada oficial é
 *  `br.com.borurio.web.Application`.
 */
@Configuration
@ComponentScan(basePackages = "br.com.borurio.fiscal")
@MapperScan(basePackages = "br.com.borurio.fiscal.mapper")
public class FiscalApplication {
    // Configuração central do módulo fiscal.
    // Todos os beans, services e mappers são registrados automaticamente
    // no contexto principal do ERP (borurio-web).
}
