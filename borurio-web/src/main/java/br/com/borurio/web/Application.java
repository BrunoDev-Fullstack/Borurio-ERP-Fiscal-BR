package br.com.borurio.web;

import br.com.borurio.web.config.properties.AppProperties;
import br.com.borurio.web.config.properties.SefazProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * =============================================================================
 * APLICAÇÃO PRINCIPAL — BORURIO ERP FISCAL BRASIL
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
@EnableConfigurationProperties({
        AppProperties.class,
        SefazProperties.class
})
public class Application implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    /**
     * Executado SOMENTE após o Spring estar 100% inicializado:
     * - Datasource OK
     * - Flyway OK
     * - Redis OK (autoconfiguração Spring Boot)
     * - Contexto estável
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {

        Environment env = event.getApplicationContext().getEnvironment();

        String profile = String.join(",", env.getActiveProfiles());
        if (profile.isBlank()) {
            profile = "default";
        }

        String appName = env.getProperty("spring.application.name", "borurio-web");
        String port = env.getProperty("server.port", "8080");

        String startedAt = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        log.info("###################################################################################################");
        log.info("#                                                                                                 #");
        log.info("#   BORURIO ERP FISCAL BRASIL — API PRINCIPAL                                                      #");
        log.info("#                                                                                                 #");
        log.info("#   Projeto: ERP Fiscal Borurio BR                                                                #");
        log.info("#   Módulos ativos: core | app | fiscal | web                                                     #");
        log.info("#   Aplicação: {}                                                                                #", appName);
        log.info("#   Perfil ativo: {}                                                                             #", profile);
        log.info("#   Porta interna: {}                                                                            #", port);
        log.info("#   Data de inicialização: {}                                                                    #", startedAt);
        log.info("#                                                                                                 #");
        log.info("#   Desenvolvedor responsável: Bruno Ribeiro — Fullstack / DevSecOps                              #");
        log.info("#                                                                                                 #");
        log.info("###################################################################################################");

        log.info("Aplicação '{}' iniciada com perfil '{}' na porta {}", appName, profile, port);
        log.info("Módulos integrados: CORE | APP | FISCAL | WEB");
        log.info("Status: Inicialização concluída com sucesso.");
    }
}
