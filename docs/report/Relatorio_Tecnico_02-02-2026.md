RELATÓRIO TÉCNICO — BORURIO ERP FISCAL BR

Data: 02/02/2026
Branch: baseline-hom-prd
Tag: v3.3.0-hom
Autor: Bruno Ribeiro — DevSecOps / Fullstack Java

1. CONTEXTO DO DIA

O objetivo do dia foi fechar o marco de homologação NF-e 4.00, garantindo que:

A API estivesse estável

A segurança JWT estivesse funcional em HOM

A infraestrutura Docker estivesse operacional

A integração com SEFAZ-SP (Homologação) estivesse pronta para testes reais

O marco estivesse versionado e publicado no GitHub

Esse objetivo foi integralmente atingido.

2. MARCO TÉCNICO ENTREGUE
   2.1 Versionamento e Controle

Branch principal de homologação: baseline-hom-prd

Tag criada e publicada:
v3.3.0-hom — Marco de Homologação NF-e 4.00

Commit principal:

feat(hom): marco de homologação NF-e 4.00 com SEFAZ-SP


Esse ponto permite:

Rollback seguro

Base sólida para PRD

Continuidade sem perda de contexto

3. STATUS DA APLICAÇÃO
   3.1 Aplicação

Aplicação iniciada com sucesso

Perfil ativo: hom

Porta exposta: 8082

Módulos integrados:

CORE

APP

FISCAL

WEB

Log confirmado:

Status: Inicialização concluída sem erros críticos.

3.2 Infraestrutura Docker (HOMOLOGAÇÃO)

Containers ativos e saudáveis:

borurio-web-hom

borurio-mysql-hom (MySQL 8.4)

borurio-redis-hom

borurio-minio-hom

borurio-mailpit-hom

Todos com status healthy.

4. SEGURANÇA E AUTENTICAÇÃO
   4.1 JWT

Segurança JWT ativa

Stateless (SessionCreationPolicy.STATELESS)

Filtro JWT aplicado corretamente

Swagger integrado com BearerAuth

4.2 Autenticação em HOM

Login funcional em homologação

Logs confirmam autenticação bem-sucedida:

[AUTH] Processando autenticação (profile=hom)
Autenticação realizada com sucesso


Endpoint validado:

POST /auth/login

5. OPENAPI / SWAGGER
   5.1 Swagger UI

Swagger carregando corretamente

Endpoints organizados por domínio:

Auth

NF-e

NCM

Clientes

Testes

Documento OpenAPI validado:

GET /v3/api-docs


Segurança JWT aplicada globalmente via bearerAuth

6. MÓDULO FISCAL (NF-e)
   6.1 Integração SEFAZ-SP

Ambiente configurado:

tpAmb = 2 (HOMOLOGAÇÃO)

Endpoints oficiais SP

Propriedades tipadas via SefazProperties

Serviço de transmissão:

SOAP 1.2

mTLS com certificado A1

Envelope NF-e 4.00 correto

Logs fiscais persistidos

6.2 Endpoints Fiscais Ativos

Consulta de status SEFAZ:

GET /nfe/status


Envio de NF-e:

POST /nfe/enviar


Testes fiscais:

GET /api/fiscal/nfe/test/ping
GET /api/fiscal/nfe/status


Todos respondendo corretamente.

7. CONFIGURAÇÕES IMPORTANTES CONSOLIDADAS

application-hom.yml revisado e validado

Logback específico para homologação ativo

Certificado A1 configurado via variáveis de ambiente

Flyway desativado no módulo WEB (correto)

Actuator ativo para healthcheck

Health confirmado:

status: UP
API Borurio ERP Fiscal BR está operacional

8. PROBLEMAS IDENTIFICADOS E RESOLVIDOS

Conflitos de segurança no Actuator → resolvidos

Erros de autenticação em HOM → resolvidos

Remoção correta de OpenApiConfig legado

Ajustes finos no JwtFilter, JwtUtil e SecurityConfig

Limpeza de artefatos de build antes do commit

9. SITUAÇÃO ATUAL DO PROJETO

Estado atual: PRONTO PARA TESTES REAIS DE NF-e EM HOMOLOGAÇÃO

Nada estrutural bloqueia:

Teste de envio

Teste de schema

Teste de certificado

Teste de comunicação SEFAZ

10. PRÓXIMOS PASSOS INTELIGENTES (FOCO NF-e HOMOLOGAÇÃO)
    ETAPA 1 — Preparação da NF-e de Teste

Gerar XML NF-e 4.00 válido:

Emitente real (CNPJ homologado)

Produtos reais

CFOP válido

tpAmb = 2

Assinar XML com certificado A1

ETAPA 2 — Validação Local

Validar XML contra:

XSD oficial NF-e 4.00

Garantir:

Assinatura correta

Namespace correto

Id da NF-e válido

ETAPA 3 — Envio para SEFAZ-SP

Autenticar via /auth/login

Enviar NF-e:

POST /nfe/enviar
Header: CNPJ-Emitente
Body: XML assinado

ETAPA 4 — Análise de Retorno

Interpretar:

cStat

xMotivo

ProtNFe

Persistir protocolo

Confirmar autorização ou rejeição

ETAPA 5 — Fluxos Complementares

Consulta de protocolo

Cancelamento (evento)

Inutilização (quando aplicável)

11. CONCLUSÃO

O dia encerra com:

Marco técnico sólido

Código limpo e versionado

Infra estável

Segurança funcional

Integração SEFAZ pronta

O projeto está em nível profissional de homologação fiscal, pronto para o passo mais crítico: primeira NF-e homologada com retorno SEFAZ-SP.