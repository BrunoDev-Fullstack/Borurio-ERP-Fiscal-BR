package br.com.borurio.web.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Borurio ERP Fiscal BR — API REST",
                version = "v3.3.0",
                description = """
                        API do sistema ERP Fiscal Borurio BR com suporte completo a NF-e 4.00 (SEFAZ-SP).

                        Módulos disponíveis:
                        - /auth/login                        → Autenticação JWT
                        - /api/app/empresas                  → Empresas emitentes (multi-tenant) [ADMIN]
                        - /api/app/produtos                  → Produtos com snapshot fiscal congelado
                        - /api/app/clientes                  → Clientes por empresa (isolamento multi-tenant)
                        - /api/app/pedidos                   → Pedidos de venda + ciclo fiscal NF-e completo
                        - /api/app/pedidos/{id}/emitir       → Emissão NF-e 4.00 → SEFAZ
                        - /api/app/pedidos/{id}/situacao     → Consulta situação fiscal (consSitNFe)
                        - /api/app/pedidos/{id}/cancelar     → Cancelamento NF-e (Evento 110111)
                        - /api/app/pedidos/{id}/cce          → Carta de Correção Eletrônica (Evento 110110)
                        - /api/app/usuarios                  → Gestão de usuários do sistema [ADMIN]
                        - /api/fiscal/nfe/logs               → Auditoria de eventos fiscais
                        - /api/fiscal/nfe/cancelar           → Cancelamento por chave (fiscal direto)
                        - /api/fiscal/nfe/inutilizar         → Inutilização de faixa de numeração

                        Autenticação: clique em "Authorize" e informe o Bearer token retornado pelo /auth/login.
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
        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Desenvolvimento (DEV)"),
                        new Server().url("http://localhost:8081").description("Homologação SEFAZ-SP (HOM)")
                ));
    }
}
