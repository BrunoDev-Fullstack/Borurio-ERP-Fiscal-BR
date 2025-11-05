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
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
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
                        - /nfe/status → Consulta de status SEFAZ-SP
                        - /nfe/enviar → Envio da NF-e 4.00
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

        // Exemplo XML NF-e 4.00 (Homologação)
        Example xmlExample = new Example()
                .summary("Exemplo NF-e 4.00 — Ambiente Homologação")
                .value("""
<?xml version="1.0" encoding="UTF-8"?>
<NFe xmlns="http://www.portalfiscal.inf.br/nfe">
  <infNFe Id="NFe35251199999999999999550010000000011000000010" versao="4.00">
    <ide>
      <cUF>35</cUF><cNF>00000001</cNF><natOp>VENDA DE MERCADORIA</natOp>
      <mod>55</mod><serie>1</serie><nNF>1</nNF>
      <dhEmi>2025-11-04T09:59:00-03:00</dhEmi>
      <tpNF>1</tpNF><idDest>1</idDest><cMunFG>3550308</cMunFG>
      <tpImp>1</tpImp><tpEmis>1</tpEmis><cDV>0</cDV><tpAmb>2</tpAmb>
      <finNFe>1</finNFe><indFinal>1</indFinal><indPres>1</indPres>
      <procEmi>0</procEmi><verProc>BorurioERP-3.3.0</verProc>
    </ide>
    <emit>
      <CNPJ>12345678000199</CNPJ>
      <xNome>BORURIO BRASIL IMPORTAÇÃO E DISTRIBUIÇÃO LTDA</xNome>
      <xFant>BORURIO BR</xFant>
    </emit>
    <dest>
      <CNPJ>98765432000199</CNPJ>
      <xNome>CLIENTE TESTE LTDA</xNome>
    </dest>
  </infNFe>
</NFe>
""");

        // Respostas padrão
        ApiResponses okResponse = new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("Operação bem-sucedida"));

        // /auth/login
        Operation authOp = new Operation()
                .summary("Autenticação do usuário")
                .description("Realiza o login e retorna um token JWT válido (expiração: 1h)")
                .responses(okResponse);

        // /nfe/status
        Operation statusOp = new Operation()
                .summary("Consulta de Status da SEFAZ-SP")
                .description("Verifica se o serviço NF-e da SEFAZ-SP está operacional.")
                .responses(okResponse);

        // /nfe/enviar
        Operation enviarOp = new Operation()
                .summary("Envio de NF-e 4.00 (Homologação SEFAZ-SP)")
                .description("""
                        Recebe o XML assinado da NF-e 4.00 e valida contra o schema oficial.
                        Ambiente: tpAmb=2 (HOMOLOGAÇÃO)
                        Requer header 'CNPJ-Emitente' e token JWT válido.
                        """)
                .addParametersItem(new Parameter()
                        .name("CNPJ-Emitente")
                        .in("header")
                        .example("12345678000199")
                        .required(true)
                        .description("CNPJ do emitente da NF-e"))
                .requestBody(new io.swagger.v3.oas.models.parameters.RequestBody()
                        .description("XML assinado da NF-e (modelo 55).")
                        .content(new Content().addMediaType(
                                "application/xml",
                                new MediaType().addExamples("ExemploNF", xmlExample)
                        )))
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
                .path("/nfe/status", new PathItem().get(statusOp))
                .path("/nfe/enviar", new PathItem().post(enviarOp))
                .path("/api/test/ping", new PathItem().get(pingOp));
    }
}
