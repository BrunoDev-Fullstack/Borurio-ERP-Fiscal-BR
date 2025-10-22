package br.com.borurio.web.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * CONFIGURAÇÃO SWAGGER / OPENAPI 3.0 + AUTENTICAÇÃO JWT
 * =============================================================================
 * Módulo: borurio-web
 * Responsável: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * -----------------------------------------------------------------------------
 * Descrição:
 *   Fornece a documentação interativa da API Fiscal Borurio Brasil (NF-e 4.00),
 *   com integração ao padrão OpenAPI 3.0 e suporte ao esquema de segurança JWT.
 *
 * Recursos:
 *   • Exposição automática de endpoints REST (Swagger UI)
 *   • Metadados completos (autor, licença, portal)
 *   • Suporte ao botão “Authorize” com Bearer Token (JWT)
 *
 * URLs de acesso (ambiente DEV):
 *   ➜ Swagger UI:   http://localhost:8080/swagger-ui/index.html
 *   ➜ OpenAPI JSON: http://localhost:8080/v3/api-docs
 *   ➜ OpenAPI YAML: http://localhost:8080/v3/api-docs.yaml
 * =============================================================================
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Borurio ERP Fiscal BR — API REST",
                version = "v3.3.0",
                description = """
                        API central do ERP Fiscal Borurio Brasil (NF-e 4.00)
                        Ambiente: Desenvolvimento / Homologação SEFAZ-SP
                        """,
                contact = @Contact(
                        name = "Bruno Ribeiro — DevSecOps / Fullstack Java",
                        url = "https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR",
                        email = "contato@borurio.com.br"
                ),
                license = @License(
                        name = "Licença Proprietária Borurio",
                        url = "https://borurio.com.br/licenca"
                )
        )
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {

    /**
     * Bean principal do OpenAPI — configura documentação, links externos
     * e complementa os metadados exibidos no Swagger UI.
     *
     * @return Instância configurada de {@link OpenAPI}.
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .externalDocs(new ExternalDocumentation()
                        .description("Portal do Projeto — ERP Fiscal Borurio Brasil")
                        .url("https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR"));
    }
}
