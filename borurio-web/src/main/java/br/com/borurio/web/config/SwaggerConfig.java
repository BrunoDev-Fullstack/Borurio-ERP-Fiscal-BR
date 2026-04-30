package br.com.borurio.web.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * CONFIGURAÇÃO SWAGGER — BORURIO ERP FISCAL BR
 * -----------------------------------------------------------------------------
 * Ambiente Unificado (DEV + HOMOLOGAÇÃO)
 * - JWT via botão "Authorize"
 * - Endpoints de NF-e 4.00 com exemplos automáticos e cabeçalhos pré-definidos
 * =============================================================================
 * Projeto: ERP Fiscal Borurio BR
 * Versão: 3.3.0
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Borurio ERP Fiscal BR — API REST (Ambiente DEV + HOMOLOGAÇÃO)",
                version = "v3.3.0",
                description = """
                        Documentação oficial da API Fiscal NF-e 4.00.
                        Ambiente unificado para Desenvolvimento e Homologação SEFAZ-SP.

                        Principais módulos:
                        - /auth/login → Autenticação JWT
                        - /api/fiscal/nfe/status → Consulta de status SEFAZ-SP
                        - /api/fiscal/nfe/enviar → Envio da NF-e 4.00
                        - /api/test/** → Testes de disponibilidade e integração
                        """,
                contact = @Contact(
                        name = "Bruno Ribeiro — DevSecOps / Fullstack Java",
                        email = "contato@borurio.com.br",
                        url = "https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR"
                ),
                license = @License(
                        name = "Licença Proprietária Borurio",
                        url = "https://borurio.com.br/licenca"
                )
        ),
        security = @SecurityRequirement(name = "bearerAuth")
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {

        // Respostas padrão
        ApiResponses okResponse = new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("Operação bem-sucedida"));

        // /auth/login
        Operation authOp = new Operation()
                .summary("Autenticação do usuário")
                .description("Realiza o login e retorna um token JWT válido (expiração: 1h)")
                .responses(okResponse);

        // /api/test/ping
        Operation pingOp = new Operation()
                .summary("Ping de disponibilidade da API")
                .description("Verifica se a API Borurio ERP Fiscal BR está online e funcional.")
                .responses(okResponse);

        // Construção do OpenAPI
        return new OpenAPI()
                .externalDocs(new ExternalDocumentation()
                        .description("Portal do Projeto — ERP Fiscal Borurio BR")
                        .url("https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR"))
                .path("/auth/login", new PathItem().post(authOp))
                .path("/api/test/ping", new PathItem().get(pingOp));
    }
}
