# Borurio ERP Logístico + Fiscal BR

ERP modular em Java 17 com motor fiscal NF-e 4.00, integração com SEFAZ, autenticação JWT e organização técnica orientada a ambientes DEV, HOM e PRD.

## Visão geral

O Borurio ERP Logístico + Fiscal BR foi concebido como uma plataforma integrada para gestão operacional e fiscal, unindo domínio ERP com um motor fiscal especializado em emissão de NF-e.

A arquitetura do projeto busca combinar:

- cadastro e regras de negócio
- processamento fiscal
- comunicação com SEFAZ
- rastreabilidade técnica e operacional
- evolução gradual para fluxo completo de ERP logístico

## Objetivo do sistema

O sistema tem como objetivo oferecer uma base robusta para:

- gerenciar clientes, emitentes e entidades de negócio
- gerenciar produtos com classificação fiscal
- suportar futuras operações logísticas, pedidos e movimentações
- gerar, validar, assinar e transmitir NF-e
- persistir e rastrear o ciclo fiscal completo
- sustentar evolução controlada entre desenvolvimento, homologação e produção

## Arquitetura modular

O projeto está organizado em módulos Maven com separação clara de responsabilidades:

```text
borurio-erp-br
├── borurio-core    → núcleo compartilhado, enums, utilitários e resposta padrão
├── borurio-app     → camada de domínio e regras de negócio ERP
├── borurio-fiscal  → motor fiscal NF-e, XML, assinatura, SEFAZ, NCM e auditoria
└── borurio-web     → API REST, autenticação JWT, controllers e integração app ↔ fiscal
borurio-core

Núcleo compartilhado da aplicação:

enums
utilitários
padrão de resposta
componentes comuns
borurio-app

Camada de domínio e regras de negócio:

entidades ERP
serviços de aplicação
base evolutiva para clientes, produtos e operações
borurio-fiscal

Motor fiscal NF-e:

geração de XML
validação XSD
assinatura XMLDSIG
comunicação SOAP 1.2
certificado A1
NCM
auditoria fiscal
borurio-web

Camada de exposição da aplicação:

API REST
autenticação JWT
controllers
Swagger / OpenAPI
integração entre domínio e motor fiscal
Stack tecnológica
Java 17
Spring Boot 3.3.x
Maven multi-module
MyBatis
MySQL 8.4
Redis 7.2
MinIO
Flyway
Docker / Docker Compose
JWT
Swagger / OpenAPI
SOAP 1.2
XMLDSIG
GitHub Actions
Escopo funcional
Núcleo já estruturado
autenticação JWT
API REST principal
domínio inicial de clientes
base de produtos e classificação fiscal
módulo NCM
geração de XML NF-e
validação XSD
assinatura digital com certificado A1
transmissão fiscal via SOAP
logs e rastreabilidade fiscal
Evolução prevista do ERP
pedidos / ordens
estoque / logística
vínculo entre operação e documento fiscal
relatórios operacionais
multiempresa
expansão fiscal avançada
Fluxo operacional do sistema

A visão arquitetural do projeto segue a lógica:

Cliente → Operação → Itens → Processamento Fiscal → NF-e → SEFAZ

Na trilha fiscal, o fluxo técnico central é:

recepção da requisição
montagem do XML NF-e
validação contra XSD
assinatura digital XMLDSIG
montagem do lote fiscal
transmissão via SOAP 1.2 / TLS
recebimento e rastreamento do retorno
persistência de eventos, status e evidências
Motor fiscal NF-e

O módulo fiscal concentra os componentes de emissão eletrônica e comunicação com SEFAZ.

Capacidades do motor fiscal
montagem de XML NF-e 4.00
validação estrutural por XSD
assinatura digital com certificado A1
integração SOAP 1.2 / TLS
suporte a auditoria fiscal
base para eventos fiscais e evolução regulatória
Integrações fiscais
SEFAZ homologação
SEFAZ produção
truststore / ICP-Brasil
certificado digital A1
tabela NCM oficial
Ambientes

O projeto foi modelado para operar com separação de ambientes.

DEV

Ambiente de validação técnica e desenvolvimento local:

execução controlada
Swagger / API Client
debug e observabilidade
validação técnica do pipeline
HOM

Ambiente destinado à homologação funcional e fiscal:

validações integradas
testes controlados
preparação de operação
PRD

Ambiente destinado à operação real, com promoção controlada após homologação.

Segurança

O projeto segue uma linha de endurecimento técnico com foco em segurança aplicada ao ciclo fiscal.

Medidas incorporadas na arquitetura:

autenticação JWT
proteção de endpoints fiscais
leitura de credenciais por variável de ambiente
certificado A1 fora do código-fonte
proteção contra XXE
padrão stateless
separação por ambiente
governança de branch principal com pull request obrigatório
CI/CD

O repositório possui automação de pipeline organizada em GitHub Actions.

CI - Build e Segurança

Fluxo de integração contínua voltado para:

build
testes
validação técnica do pipeline
verificação de segurança em escopo controlado
CD - Homologação Manual

Fluxo de deploy manual orientado ao ambiente de homologação:

sem promoção automática para produção
execução controlada
alinhado à estratégia de homologação técnica
Topologias arquiteturais

O projeto possui um pacote arquitetural com diagramas que documentam a visão atual da solução, o fluxo fiscal e a organização dos ambientes.

1. Topologia geral do sistema

2. Topologia alvo funcional com motor fiscal NF-e

3. Fluxo técnico de emissão NF-e / SEFAZ

4. Topologia de ambientes DEV / HOM / PRD

5. Topologia de segurança, certificado e comunicação fiscal

6. Topologia de CI/CD, versionamento e evidências de deploy

Documentação técnica

O projeto possui documentação técnica complementar para apoiar entendimento arquitetural, onboarding e rastreabilidade de evolução.

Estrutura documental
docs/
├── architecture/
│   ├── drawio/
│   └── exports/
├── data/
├── report/
└── xml/
Estrutura do repositório
.github/
borurio-app/
borurio-core/
borurio-fiscal/
borurio-web/
docker/
docs/
scripts/
sql/
Roadmap de evolução

A evolução do sistema foi desenhada para ocorrer de forma incremental e controlada.

Curto prazo
consolidação do motor fiscal atual
estabilização técnica dos fluxos centrais
amadurecimento da documentação operacional
refinamento do pipeline de homologação
Médio prazo
expansão do domínio ERP
pedidos
estoque / logística
integração mais forte entre operação e emissão fiscal
Longo prazo
fiscal avançado
multiempresa
DANFE
relatórios operacionais e gerenciais
expansão funcional do ERP
Observações

Este repositório representa uma base arquitetural e funcional em evolução contínua, com foco em solidez técnica, separação de responsabilidades e crescimento incremental do ERP Logístico + Fiscal BR.

A estratégia do projeto prioriza:

consistência arquitetural
rastreabilidade técnica
segurança
homologação controlada
evolução gradual do domínio de negócio
Autor

Bruno Ribeiro