package br.com.borurio.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * =============================================================================
 * APLICAÇÃO PRINCIPAL — BORURIO ERP FISCAL BRASIL
 * =============================================================================
 * Responsável por inicializar o contexto Spring Boot, integrar os módulos
 * principais (core, app, fiscal, web) e configurar o mapeamento MyBatis.
 *
 * Estrutura Modular:
 *   - borurio-core   → Núcleo compartilhado (enums, DTOs, utilitários)
 *   - borurio-app    → Camada de negócio e regras de domínio
 *   - borurio-fiscal → Integração SEFAZ-SP / NF-e 4.00 / Auditoria Fiscal
 *   - borurio-web    → API REST principal e ponto de entrada unificado
 *
 * Padrões Técnicos:
 *   - Spring Boot 3.3.x / Java 17
 *   - MyBatis-Plus para integração de mappers
 *   - Component scan global e modular
 *   - Perfis controlados via application-*.yml (dev, hom, prd)
 *   - Log estruturado de inicialização (via STDOUT / Docker)
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Sprint: Fiscal 3.4 — Integração SEFAZ-SP Homologação Real
 * Data: Outubro/2025
 * =============================================================================
 */
@SpringBootApplication(scanBasePackages = {
        "br.com.borurio.core",
        "br.com.borurio.app",
        "br.com.borurio.fiscal",
        "br.com.borurio.web"
})
@MapperScan(basePackages = {
        "br.com.borurio.app.mapper"
})
public class Application {

    /**
     * Método principal da aplicação ERP Fiscal Borurio Brasil.
     * Inicializa o contexto Spring Boot, ativa os módulos integrados
     * e exibe um banner de inicialização no console.
     *
     * Modo de execução:
     *  - Via Maven:
     *      mvn spring-boot:run -pl borurio-web "-Dspring-boot.run.profiles=dev"
     *  - Via JAR:
     *      java -jar borurio-web-1.0.0.jar --spring.profiles.active=dev
     *
     * @param args Argumentos de inicialização do Spring Boot.
     */
    public static void main(String[] args) {
        var context = SpringApplication.run(Application.class, args);
        Environment env = context.getEnvironment();

        String profile = String.join(",", env.getActiveProfiles());
        if (profile.isEmpty()) profile = "default";

        String appName = env.getProperty("spring.application.name", "borurio-web");
        String port = env.getProperty("server.port", "8080");
        String startedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        System.out.println();
        System.out.println("###################################################################################################");
        System.out.println("#                                                                                                 #");
        System.out.printf ("#   %-90s #%n", "BORURIO ERP FISCAL BRASIL — API PRINCIPAL");
        System.out.println("#                                                                                                 #");
        System.out.println("#   Projeto: ERP Fiscal Borurio BR                                                                #");
        System.out.println("#   Módulos ativos: core | app | fiscal | web                                                     #");
        System.out.println("#   Aplicação: " + appName);
        System.out.println("#   Perfil ativo: " + profile);
        System.out.println("#   Porta interna: " + port);
        System.out.println("#   Data de inicialização: " + startedAt);
        System.out.println("#                                                                                                 #");
        System.out.println("#   Desenvolvedor responsável: Bruno Ribeiro — Fullstack / DevSecOps                              #");
        System.out.println("#   Repositório: github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR                              #");
        System.out.println("#                                                                                                 #");
        System.out.println("###################################################################################################");
        System.out.println();
    }
}
