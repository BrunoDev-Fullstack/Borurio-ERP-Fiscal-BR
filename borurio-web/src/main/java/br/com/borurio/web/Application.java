package br.com.borurio.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * =============================================================================
 * APLICAÇÃO PRINCIPAL — BORURIO ERP FISCAL BRASIL
 * =============================================================================
 * Responsável por inicializar o contexto Spring Boot, integrar os módulos
 * principais (core, app, fiscal e web) e configurar o escaneamento global de
 * componentes e mappers.
 *
 * Estrutura Modular:
 *   • borurio-core   → Núcleo compartilhado (enums, DTOs, utilitários)
 *   • borurio-app    → Camada de negócio e regras de domínio
 *   • borurio-fiscal → Integração SEFAZ-SP / NF-e 4.00 / Auditoria Fiscal
 *   • borurio-web    → API REST principal (ponto de entrada oficial)
 *
 * Tecnologias:
 *   • Java 17 / Spring Boot 3.3.x
 *   • MyBatis (mappers SQL)
 *   • MySQL 8.4 / Redis / MinIO
 *   • Flyway / Actuator / Swagger-OpenAPI
 *   • Logback (log estruturado e amigável para containers)
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Última revisão: 13/11/2025
 * =============================================================================
 */
@SpringBootApplication
@ComponentScan(basePackages = {
        "br.com.borurio.core",
        "br.com.borurio.app",
        "br.com.borurio.fiscal",
        "br.com.borurio.web"
})
@MapperScan(basePackages = {
        "br.com.borurio.app.mapper",
        "br.com.borurio.fiscal.mapper"
})
public class Application {

    /**
     * Método principal da aplicação Borurio ERP Fiscal BR.
     * Inicializa o contexto Spring Boot, registra os módulos e exibe um banner
     * padronizado no console, facilitando análise em runtime e em containers.
     *
     * Execução recomendada:
     *   • Via Maven:
     *       mvn spring-boot:run -pl borurio-web "-Dspring-boot.run.profiles=dev"
     *
     *   • Via JAR:
     *       java -jar borurio-web-1.0.0.jar --spring.profiles.active=dev
     */
    public static void main(String[] args) {
        var context = SpringApplication.run(Application.class, args);
        Environment env = context.getEnvironment();

        String profile = String.join(",", env.getActiveProfiles());
        if (profile.isEmpty()) profile = "default";

        String appName = env.getProperty("spring.application.name", "borurio-web-dev");
        String port = env.getProperty("server.port", "8080");

        String startedAt = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        // =========================================================================
        // BANNER DE INICIALIZAÇÃO — Formato padronizado DevSecOps
        // =========================================================================
        System.out.println();
        System.out.println("###################################################################################################");
        System.out.println("#                                                                                                 #");
        System.out.printf ("#   %-90s #%n", "BORURIO ERP FISCAL BRASIL — API PRINCIPAL");
        System.out.println("#                                                                                                 #");
        System.out.println("#   Projeto: ERP Fiscal Borurio BR                                                                #");
        System.out.println("#   Módulos ativos: core | app | fiscal | web                                                     #");
        System.out.printf ("#   Aplicação: %-85s #%n", appName);
        System.out.printf ("#   Perfil ativo: %-83s #%n", profile);
        System.out.printf ("#   Porta interna: %-82s #%n", port);
        System.out.printf ("#   Data de inicialização: %-75s #%n", startedAt);
        System.out.println("#                                                                                                 #");
        System.out.println("#   Desenvolvedor responsável: Bruno Ribeiro — Fullstack / DevSecOps                              #");
        System.out.println("#   Repositório: github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR                              #");
        System.out.println("#                                                                                                 #");
        System.out.println("#   \"Segurança e automação são pilares da confiabilidade fiscal.\"                                 #");
        System.out.println("#                                                                                                 #");
        System.out.println("###################################################################################################");
        System.out.println();

        // Painel de log para containers e monitoramento
        System.out.printf(">> Aplicação '%s' iniciada com perfil '%s' na porta %s.%n", appName, profile, port);
        System.out.println(">> Módulos integrados: CORE | APP | FISCAL | WEB");
        System.out.println(">> Data/hora: " + startedAt);
        System.out.println(">> Status: Inicialização concluída sem erros críticos.");
    }
}
