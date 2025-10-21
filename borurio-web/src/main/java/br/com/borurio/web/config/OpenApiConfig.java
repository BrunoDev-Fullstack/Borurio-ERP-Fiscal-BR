package br.com.borurio.web.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * CONFIGURAÇÃO SWAGGER / OPENAPI 3.0
 * =============================================================================
 * Módulo: borurio-web
 * Responsável: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Descrição:
 *   Exposição da documentação interativa da API Fiscal (NF-e 4.00),
 *   gerando automaticamente descrições de endpoints, modelos e respostas
 *   REST padronizadas.
 * -----------------------------------------------------------------------------
 * URLs de acesso (ambiente DEV):
 *   ➜ Swagger UI: http://localhost:8080/swagger-ui/index.html
 *   ➜ OpenAPI JSON: http://localhost:8080/v3/api-docs
 *   ➜ OpenAPI YAML: http://localhost:8080/v3/api-docs.yaml
 * =============================================================================
 */
@Configuration
public class OpenApiConfig {

    /**
     * Bean principal responsável por configurar os metadados do Swagger/OpenAPI.
     *
     * @return objeto OpenAPI configurado para a documentação do ERP Fiscal BR.
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Borurio ERP Fiscal BR — API REST")
                        .description("""
                                API central do ERP Fiscal Borurio Brasil (NF-e 4.00)
                                Ambiente: Desenvolvimento / Homologação SEFAZ-SP
                                """)
                        .version("v3.2.0")
                        .contact(new Contact()
                                .name("Bruno Ribeiro — DevSecOps / Fullstack Java")
                                .url("https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR")
                                .email("contato@borurio.com.br"))
                        .license(new License()
                                .name("Licença Proprietária Borurio")
                                .url("https://borurio.com.br/licenca")))
                .externalDocs(new ExternalDocumentation()
                        .description("Portal do Projeto — ERP Fiscal Borurio Brasil")
                        .url("https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR"));
    }
}
