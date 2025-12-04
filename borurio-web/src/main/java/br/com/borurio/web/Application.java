package br.com.borurio.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * =============================================================================
 * APLICAÇÃO PRINCIPAL — BORURIO ERP FISCAL BRASIL
 * =============================================================================
 * Responsável por inicializar o contexto Spring Boot, integrar os módulos
 * principais (core, app, fiscal e web) e registrar os scanners globais de
 * componentes e mappers.
 *
 * Módulos integrados:
 *   • borurio-core   → Núcleo compartilhado
 *   • borurio-app    → Regras de domínio do galpão
 *   • borurio-fiscal → NF-e 4.00 / SEFAZ / Auditoria
 *   • borurio-web    → API principal (REST + JWT + Swagger)
 *
 * Tecnologias:
 *   • Java 17
 *   • Spring Boot 3.3.x
 *   • MyBatis / MySQL 8.4 / Redis / MinIO
 *   • Flyway / OpenAPI 3 / Logback
 *
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * Revisão: 02/12/2025
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

    public static void main(String[] args) {

        // Garantir UTF-8 em ambientes Windows + Docker
        System.setProperty("file.encoding", StandardCharsets.UTF_8.name());

        var context = SpringApplication.run(Application.class, args);
        Environment env = context.getEnvironment();

        String profile = String.join(",", env.getActiveProfiles());
        if (profile.isBlank()) profile = "default";

        String appName = env.getProperty("spring.application.name", "borurio-web-dev");
        String port = env.getProperty("server.port", "8080");

        String startedAt = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        // =========================================================================
        // BANNER DEVSECOPS — Inicialização padronizada e amigável para containers
        // =========================================================================
        System.out.println();
        System.out.println("#####################################################################################################");
        System.out.println("#                                                                                                   #");
        System.out.printf ("#   %-94s #%n", "BORURIO ERP FISCAL BRASIL — API PRINCIPAL");
        System.out.println("#                                                                                                   #");
        System.out.println("#   Projeto: ERP Fiscal Borurio BR                                                                  #");
        System.out.println("#   Módulos ativos: core | app | fiscal | web                                                       #");
        System.out.printf ("#   Aplicação: %-89s #%n", appName);
        System.out.printf ("#   Perfil ativo: %-87s #%n", profile);
        System.out.printf ("#   Porta interna: %-86s #%n", port);
        System.out.printf ("#   Data de inicialização: %-79s #%n", startedAt);
        System.out.println("#                                                                                                   #");
        System.out.println("#   Desenvolvedor responsável: Bruno Ribeiro — Fullstack / DevSecOps                                #");
        System.out.println("#   Repositório: github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR                                #");
        System.out.println("#                                                                                                   #");
        System.out.println("#   \"Segurança e automação são pilares da confiabilidade fiscal.\"                                   #");
        System.out.println("#                                                                                                   #");
        System.out.println("#####################################################################################################");
        System.out.println();

        // Painel de inicialização (stdout + logs)
        System.out.printf(">> Aplicação '%s' iniciada com perfil '%s' na porta %s.%n", appName, profile, port);
        System.out.println(">> Módulos integrados: CORE | APP | FISCAL | WEB");
        System.out.println(">> Data/hora: " + startedAt);
        System.out.println(">> Status: Inicialização concluída sem erros críticos.");
    }
}
