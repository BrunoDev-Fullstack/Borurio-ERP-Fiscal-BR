package br.com.borurio.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Classe principal do ERP Fiscal Borurio Brasil.
 *
 * Responsável por inicializar o contexto Spring Boot e realizar
 * o escaneamento de componentes e mapeadores dos módulos ativos.
 *
 * Estrutura modular:
 *  - borurio-core
 *  - borurio-app
 *  - borurio-fiscal
 *  - borurio-web
 *
 * Boas práticas aplicadas:
 * - @SpringBootApplication: inicializa o contexto principal e realiza o scan automático;
 * - @MapperScan: registra todos os mapeadores MyBatis dos módulos App e Fiscal;
 * - Modularização limpa e compatível com Java 17 / Spring Boot 3.3.x;
 * - Carregamento controlado via perfis (ex.: application-dev.yml).
 *
 * Autor: Bruno Ribeiro
 * Desde: Sprint Fiscal 2.2 – Integração SEFAZ-SP / NF-e 4.00
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
     * Execução via Maven:
     * mvn spring-boot:run -Dspring-boot.run.profiles=dev
     *
     * Execução via JAR:
     * java -jar borurio-web-1.0.0.jar --spring.profiles.active=dev
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        System.out.println("ERP Fiscal Borurio Brasil iniciado com sucesso (perfil ativo: application-dev.yml).");
    }
}
