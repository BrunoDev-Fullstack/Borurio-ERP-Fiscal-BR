package br.com.borurio.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Classe principal do ERP Fiscal Borurio Brasil.
 *
 * Responsável por inicializar o contexto Spring Boot, realizar o
 * escaneamento dos componentes dos módulos integrados e registrar
 * os mapeadores MyBatis.
 *
 * Estrutura modular do sistema:
 *  - borurio-core   → Núcleo compartilhado (enums, DTOs, utilitários)
 *  - borurio-app    → Camada de negócio e regras de domínio
 *  - borurio-fiscal → Integração SEFAZ-SP / NF-e 4.00 / Auditoria Fiscal
 *  - borurio-web    → API REST principal e ponto de entrada unificado
 *
 * Boas práticas aplicadas:
 * - @SpringBootApplication: inicializa o contexto global e ativa o component scan;
 * - @MapperScan: registra os mappers MyBatis de múltiplos módulos;
 * - Modularização limpa e compatível com Java 17 / Spring Boot 3.3.x;
 * - Perfis de ambiente controlados via application-*.yml (ex.: dev, prd);
 * - Log de inicialização claro e informativo.
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Sprint: Fiscal 2.2 – Integração SEFAZ-SP / NF-e 4.00
 * Desde: Outubro/2025
 */
@SpringBootApplication(scanBasePackages = {
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
     * Ponto de entrada principal do ERP Fiscal Borurio Brasil.
     *
     * Execuções recomendadas:
     *
     * ▶ Via Maven:
     * mvn spring-boot:run -pl borurio-web "-Dspring-boot.run.profiles=dev"
     *
     * ▶ Via JAR:
     * java -jar borurio-web-1.0.0.jar --spring.profiles.active=dev
     *
     * O contexto carregará automaticamente os módulos Core, App e Fiscal.
     *
     * @param args argumentos de inicialização do Spring Boot.
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        System.out.println("\n==============================================================");
        System.out.println("ERP Fiscal Borurio Brasil iniciado com sucesso.");
        System.out.println("Perfil ativo: application-dev.yml");
        System.out.println("Módulos carregados: core | app | fiscal | web");
        System.out.println("==============================================================\n");
    }
}
