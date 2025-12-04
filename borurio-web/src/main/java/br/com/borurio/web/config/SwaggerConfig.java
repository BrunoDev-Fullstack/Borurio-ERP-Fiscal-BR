package br.com.borurio.web.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * SWAGGER / OPENAPI — BORURIO ERP FISCAL BR
 * Compatível com springdoc-openapi v1.7.x
 * =============================================================================
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Borurio ERP Fiscal BR — API REST (DEV + HOM)",
                version = "v3.3.0",
                description = "Documentação oficial da API Fiscal NF-e 4.00.",
                contact = @Contact(
                        name = "Bruno Ribeiro — DevSecOps / Fullstack Java",
                        email = "contato@borurio.com.br"
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
    public OpenAPI baseOpenAPI() {
        return new OpenAPI()
                .externalDocs(new ExternalDocumentation()
                        .description("Repositório Oficial — Borurio ERP Fiscal BR")
                        .url("https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR"));
    }

    /**
     * Customização compatível com springdoc-openapi 1.7.x
     */
    @Bean
    public OpenApiCustomizer nfeCustomizer() {

        return openApi -> {

            Example xmlExample = new Example()
                    .summary("Exemplo NF-e 4.00 (HOMOLOGAÇÃO)")
                    .value("""
<?xml version="1.0" encoding="UTF-8"?>
<NFe xmlns="http://www.portalfiscal.inf.br/nfe">
  <infNFe Id="NFe352511999999..." versao="4.00">
    <ide>
      <cUF>35</cUF>
      <natOp>VENDA TESTE</natOp>
      <mod>55</mod>
      <serie>1</serie>
      <nNF>1</nNF>
      <tpAmb>2</tpAmb>
    </ide>
  </infNFe>
</NFe>
""");

            // Ajusta apenas o endpoint existente: /nfe/enviar
            if (openApi.getPaths() != null) {
                openApi.getPaths().forEach((path, item) -> {

                    if ("/nfe/enviar".equals(path) && item.getPost() != null) {

                        // Header obrigatório
                        item.getPost().addParametersItem(
                                new Parameter()
                                        .name("CNPJ-Emitente")
                                        .in("header")
                                        .required(true)
                                        .example("12345678000199")
                                        .description("CNPJ do emitente da NF-e")
                        );

                        // Exemplo do XML
                        if (item.getPost().getRequestBody() != null) {
                            Content content = new Content();
                            content.addMediaType(
                                    "application/xml",
                                    new MediaType().example(xmlExample.getValue())
                            );
                            item.getPost().getRequestBody().setContent(content);
                        }
                    }
                });
            }
        };
    }
}
