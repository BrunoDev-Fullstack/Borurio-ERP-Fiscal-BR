# MTF-001 — Manual Técnico: Motor Fiscal NF-e 4.00
## Borurio ERP Fiscal BR

---

**Documento:** MTF-001  
**Versão:** 2.8  
**Data de emissão:** 11-05-2026  
**Última atualização:** 10-07-2026  
**Autor:** Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps  
**Status:** VALIDADO EM HOMOLOGAÇÃO  
**Branch de referência:** `fix/sefaz-xml-structure`  

> **Histórico de versões:**
> - v1.0 (11-05-2026): documento inicial, fases 1–9 + fase 10 em elaboração
> - v2.0 (12-05-2026): sprint 3 concluído; RBAC atualizado; `/situacao` corrigido; Fase 10 encerrada; endpoints de integração e smoke test adicionados
> - v2.1 (15-05-2026): correções de revisão técnica; SecureRandom para cNF; rate limiting implementado; scheduler de retenção de logs implementado; gap de cache certificado corrigido no código; 57 testes passando; contrato de integração atualizado com referência de mensagens
> - v2.2 (18-05-2026): Fase 12-A — estoque mínimo fiscal implementado; reserva atômica antes da SEFAZ; baixa definitiva em AUTORIZADO; estorno em CANCELADO; tabela `estoque_movimento`; 61/61 testes; V023–V024 aplicados
> - v2.3 (18-05-2026): DANFE implementado — `DanfeXmlParser`, `DanfePdfGenerator`, `DanfeService`, `GET /api/fiscal/nfe/{chave}/danfe`; OpenPDF 1.3.30; watermark "SEM VALOR FISCAL" em HOM; 66/66 testes; V023–V024 aplicados em HOM
> - v2.4 (21-05-2026): 3 bugs corrigidos em `DanfePdfGenerator` — formatação monetária pt_BR nos totais, `DecimalFormat` thread-safe por chamada, label de protocolo condicional; testes borurio-web 66 → 75 (9 novos — estados fiscais, RBAC estoque, UsuarioController)
> - v2.5 (26-05-2026): Manifestação do Destinatário implementada (210200/210210/210220/210240); cOrgao=91 (AN); validação de cStat na resposta SEFAZ; xml_retorno capturado em erro; seção 12.5 adicionada; contrato de integração v1.3; testes borurio-web 75 → 82 (7 novos — NfeManifestacaoController)
> - v2.6 (22-06-2026): V028 Multi-CNPJ OMS — `POST /api/integration/fiscal-authorizations`; token por cliente OMS (`codigoEmpresaOms`); múltiplos CNPJs sob o mesmo token; auto-criação de empresa a partir do Subject X.509; token determinístico via `emitidoEm` truncado a segundos; validação `cnpjEmitente` OMS em `POST /pedidos` (fail-fast HTTP 403); `OmsCertificadoService.resolverPorJtiECnpj`; seções 3.1/3.2/9.5/10.6/16 atualizadas; contrato v1.7; 114/114 testes
> - v2.7 (30-06-2026): fix bug `PRODUCT_NOT_FOUND` no fluxo multi-CNPJ — `PedidoEmissaoService` separou `empresaId` para estoque (empresa-âncora do pedido) de `empresa` para NF-e (empresa fiscal do CNPJ emitente); DA-08 documentado; seção 9.5 atualizada com causa raiz e correção; 126/126 testes
> - v2.8 (10-07-2026): estoque opcional por empresa (`controleEstoqueAtivo`, commit 936e771); homologação ao vivo com CC — diagnosticado cStat=225 real (cadastro do emitente incompleto, causa 2 nova na seção 13.1) e corrigido; 3 melhorias solicitadas pelo CC implementadas e testadas: (1) reemissão de pedidos `REJEITADO`/`ERRO` no mesmo `pedidoId`; (2) endereço do emitente opcional em `POST /api/app/pedidos` completando o cadastro automaticamente; (3) `errorCode`/`retryable` padronizados, `SEFAZ_REJECTED` não retorna mais HTTP 200; revisão de código (8 agentes + 9 verificações) encontrou e corrigiu 4 bugs: perda de `chaveNfe` em retry, mutação de empresa sem rollback, `retryable=true` incorreto no fallback global e em `SEFAZ_REJECTED`; contrato de integração v1.9; 190/190 testes

---

## SUMÁRIO

1. [Escopo e Objetivo](#1-escopo-e-objetivo)
2. [Arquitetura de Módulos](#2-arquitetura-de-módulos)
3. [Modelo de Dados Fiscal](#3-modelo-de-dados-fiscal)
4. [Fluxo Completo de Emissão NF-e](#4-fluxo-completo-de-emissão-nf-e)
5. [Geração do XML NF-e](#5-geração-do-xml-nf-e)
6. [Assinatura Digital XMLDSIG](#6-assinatura-digital-xmldsig)
7. [Transmissão SOAP para SEFAZ](#7-transmissão-soap-para-sefaz)
8. [Persistência e Auditoria Fiscal](#8-persistência-e-auditoria-fiscal)
9. [Multiempresa](#9-multiempresa)
10. [Certificado Digital por Empresa](#10-certificado-digital-por-empresa)
11. [Segurança Base](#11-segurança-base)
12. [Operações Fiscais Pós-Emissão](#12-operações-fiscais-pós-emissão)
13. [Limitações Conhecidas do Ambiente HOM/SP](#13-limitações-conhecidas-do-ambiente-homsp)
14. [Checklists Operacionais](#14-checklists-operacionais)
15. [Decisões de Arquitetura](#15-decisões-de-arquitetura)
16. [Roadmap até Produção](#16-roadmap-até-produção)
17. [Referências Normativas](#17-referências-normativas)

---

## 1. ESCOPO E OBJETIVO

Este manual descreve a arquitetura técnica, os fluxos de processamento e os procedimentos operacionais do **Motor Fiscal NF-e 4.00** implementado no sistema Borurio ERP Fiscal BR.

O documento destina-se a:

- **Equipe técnica interna** — manutenção, evolução e debugging do motor
- **Time de integração parceiro** — integração do motor fiscal com o ERP logístico externo (ver também `INTEGRATION_CONTRACT_PT-BR.md`)
- **Operações / DevOps** — deploy, monitoramento e procedimentos de homologação
- **Auditores técnicos** — rastreabilidade das decisões de design e conformidade

### 1.1 O que está FECHADO (validado em HOM)

| Funcionalidade                                                               | Validação                                                     |
|------------------------------------------------------------------------------|---------------------------------------------------------------|
| Emissão NF-e 4.00 via SOAP HTTPS                                             | ✓ HOM/SP — 11-05-2026                                         |
| Assinatura XMLDSIG RSA-SHA256 + C14N                                         | ✓ HOM/SP — 11-05-2026                                         |
| Ciclo pedido → NF-e                                                          | ✓ HOM/SP — 08-05-2026                                         |
| Snapshot fiscal imutável no item                                             | ✓ HOM/SP — 08-05-2026                                         |
| Status semântico (AUTORIZADO / REJEITADO / AGUARDANDO / ERRO)                | ✓ HOM/SP — 08-05-2026                                         |
| Cancelamento NF-e (evento 110111)                                            | ✓ HOM/SP — 08-05-2026                                         |
| Carta de Correção Eletrônica (evento 110110)                                 | ✓ HOM/SP — 08-05-2026                                         |
| Consulta situação NF-e (consSitNFe) — resposta estruturada                   | ✓ HOM/SP — 08-05-2026                                         |
| Multiempresa — isolamento de dados por empresa_id                            | ✓ HOM/SP — 08-05-2026                                         |
| Certificado A1 por empresa com cache                                         | ✓ HOM/SP — 11-05-2026                                         |
| RBAC (roles ADMIN / OPERADOR) com restrição correta para `/api/app/usuarios` | ✓ HOM/SP — 12-05-2026                                         |
| Criptografia cert_senha AES-256-GCM                                          | ✓ Código validado; passthrough em HOM (chave não configurada) |
| Audit log com empresa_id e usuário autenticado                               | ✓ HOM/SP — 11-05-2026                                         |
| Swagger alinhado com todos os endpoints reais (10 tags)                      | ✓ HOM/SP — 12-05-2026                                         |
| Postman collection end-to-end (10 pastas, 49 requests)                       | ✓ Gerada e alinhada — 22-05-2026                              |
| `NfeEnvioController` deprecado — endpoints legados marcados e redirecionados | ✓ Código — 12-05-2026                                         |
| Contratos de integração PT-BR e EN gerados e validados                       | ✓ Código — 12-05-2026                                         |
| `MyBatisConfig`: `@ConditionalOnProperty` garante boot correto em HOM        | ✓ HOM/SP — 12-05-2026                                         |
| 57/57 testes passando (12 controllers cobertos + fiscal)                     | ✓ Código — 15-05-2026                                         |
| Rate limiting: `/auth/login` (10 req/min) e `/emitir` (30 req/min)          | ✓ Código — 15-05-2026                                         |
| Agendamento de retenção de `nfe_log` (`NfeLogRetencaoScheduler`)             | ✓ Código — 15-05-2026                                         |
| CORS restrito — `*` substituído por origins explícitas por ambiente          | ✓ Código — 15-05-2026                                         |
| `SecureRandom` para geração de `cNF` (substituiu `new Random()`)             | ✓ Código — 15-05-2026                                         |
| Estoque mínimo fiscal — reserva, baixa definitiva, desfazer reserva, estorno | ✓ Código — 18-05-2026                                         |
| `estoque_movimento` — auditoria atômica de todos os movimentos de estoque    | ✓ Código — 18-05-2026                                         |
| `estoque_reservado` em `produto` — saldo disponível = total − reservado      | ✓ Código — 18-05-2026                                         |
| `GET /api/app/produtos/{id}/estoque` — consulta de saldo em tempo real       | ✓ Código — 18-05-2026                                         |
| `POST /api/app/produtos/{id}/estoque/entrada` — entrada manual [ADMIN]       | ✓ Código — 18-05-2026                                         |
| 61/61 testes passando (Fase 12-A adicionou 4 testes de controller)           | ✓ Código — 18-05-2026                                         |
| DANFE — geração de PDF (`DanfePdfGenerator`, OpenPDF 1.3.30)                 | ✓ Código — 18-05-2026                                         |
| `GET /api/fiscal/nfe/{chave}/danfe` — endpoint REST para download do DANFE  | ✓ Código — 18-05-2026                                         |
| Watermark "SEM VALOR FISCAL" automática em DANFE quando `tpAmb=2`           | ✓ Código — 18-05-2026                                         |
| 66/66 testes passando (DANFE adicionou 5 testes de controller)               | ✓ Código — 18-05-2026                                         |
| V023–V024 aplicados em HOM (Flyway at v024)                                  | ✓ HOM/SP — 18-05-2026                                         |
| `DanfePdfGenerator` thread-safe — `DecimalFormat` recriado por chamada (substituiu `static final`) | ✓ Código — 20-05-2026                              |
| DANFE — formatação monetária pt_BR nos totais (`R$ 91,80` com vírgula decimal)                    | ✓ Código + HOM — 20-05-2026                                   |
| DANFE — label de protocolo condicional (`RETORNO SEFAZ — HOMOLOGAÇÃO` quando `cStat≠100`)         | ✓ Código + HOM — 20-05-2026                                   |
| 75/75 testes passando (borurio-web — 9 novos testes em 20-05-2026)                                | ✓ Código — 20-05-2026                                         |
| Manifestação do Destinatário (eventos 210200/210210/210220/210240) — `POST /api/fiscal/nfe/manifestar` | ✓ Código — 26-05-2026                               |
| Validação de cStat na resposta SEFAZ — rejeição correta quando cStat≠128/135                       | ✓ Código — 26-05-2026                                         |
| xml_retorno persistido em `nfe_log` mesmo em caso de erro de transmissão                           | ✓ Código — 26-05-2026                                         |
| 82/82 testes passando (borurio-web — +7 NfeManifestacaoController)                                 | ✓ Código — 26-05-2026                                         |
| Autorização Fiscal OMS — `POST /api/integration/fiscal-authorizations`                              | ✓ HOM — 22-06-2026                                            |
| Multi-CNPJ OMS — múltiplos CNPJs sob o mesmo token (`codigoEmpresaOms`)                            | ✓ HOM — 22-06-2026                                            |
| Auto-criação de empresa a partir do Subject X.509 do certificado                                    | ✓ HOM — 22-06-2026                                            |
| Token determinístico — `emitidoEm` truncado a segundos; token idêntico em cenários B/C/D           | ✓ HOM — 22-06-2026                                            |
| Validação `cnpjEmitente` OMS em `POST /pedidos` — fail-fast HTTP 403 antes de persistir            | ✓ HOM — 22-06-2026                                            |
| `OmsCertificadoService.resolverPorJtiECnpj` — seleciona cert pelo CNPJ na emissão                  | ✓ HOM — 22-06-2026                                            |
| Smoke test multi-CNPJ M1–M4/M6 — aprovados em HOM                                                  | ✓ HOM — 22-06-2026                                            |
| **126/126 testes passando** (borurio-web 93 + fiscal 33; +12 NfeEnvioControllerTest A-03)          | ✓ Código — 22-06-2026                                         |
| V025–V028 aplicados em HOM (Flyway em v028)                                                        | ✓ HOM — 22-06-2026                                            |
| Controle de estoque opcional por empresa (`controleEstoqueAtivo`)                                  | ✓ HOM — 10-07-2026                                            |
| Reemissão de pedidos `REJEITADO`/`ERRO` no mesmo `pedidoId` (`STATUS_EMISSIVEIS`)                  | ✓ Código — 10-07-2026                                         |
| Endereço do emitente opcional em `POST /api/app/pedidos` — completa cadastro incompleto automaticamente | ✓ Código — 10-07-2026                                    |
| `NfeGeracaoService.validarEnderecoEmitente()` — bloqueia emissão antes da SEFAZ se endereço incompleto | ✓ Código — 10-07-2026                                     |
| `errorCode`/`retryable` padronizados — `EMITTER_ADDRESS_INCOMPLETE`, `SEFAZ_REJECTED`, `SEFAZ_TIMEOUT`, `SEFAZ_UNAVAILABLE`, `XML_SCHEMA_INVALID` | ✓ Código — 10-07-2026                    |
| `POST /emitir` não retorna mais HTTP 200 quando a SEFAZ rejeita a NF-e                             | ✓ Código — 10-07-2026                                         |
| **190/190 testes passando** (revisão de código com 4 correções: perda de chaveNfe, mutação de empresa sem rollback, retryable incorreto) | ✓ Código — 10-07-2026                       |

### 1.2 O que está PENDENTE

| Funcionalidade                                                        | Fase    | Observação                          |
|-----------------------------------------------------------------------|---------|-------------------------------------|
| `CERT_ENCRYPTION_KEY` configurada em produção                         | Fase 11 | Passthrough ativo em HOM por design |
| CI/CD automatizado                                                    | Fase 11 | Deploy manual via docker cp         |
| Certificados A1 de produção com CNPJ real                             | Fase 11 | Fase 11 crítica                     |

---

## 2. ARQUITETURA DE MÓDULOS

### 2.1 Diagrama de dependências

```
borurio-core
    │
    ├── borurio-app
    │       └── entidades, mappers, services de negócio
    │
    ├── borurio-fiscal
    │       └── motor NF-e, XMLDSIG, SOAP, auditoria
    │
    └── borurio-web  (JAR executável Spring Boot)
            ├── depende de borurio-app
            ├── depende de borurio-fiscal
            └── controllers REST, auth JWT, bridges de negócio
```

### 2.2 Responsabilidades por módulo

| Módulo           | Pacote raiz             | Responsabilidade                                                                                                   |
|------------------|-------------------------|--------------------------------------------------------------------------------------------------------------------|
| `borurio-core`   | `br.com.borurio.core`   | DTOs compartilhados, `ResultUtil`, `PageResponse`, utilitários base                                                |
| `borurio-app`    | `br.com.borurio.app`    | Entidades de negócio, MyBatis mappers, services: Empresa, Produto, Pedido, DbUser                                  |
| `borurio-fiscal` | `br.com.borurio.fiscal` | Geração XML NF-e, assinatura XMLDSIG, transmissão SOAP, sequenciador, persistência fiscal, auditoria               |
| `borurio-web`    | `br.com.borurio.web`    | Spring Boot, controllers REST, JWT, bridges (`PedidoEmissaoService`, `NfeGeracaoService`), certificado por empresa |

### 2.3 Regra de fronteira de módulo

> **`borurio-fiscal` NÃO importa `borurio-app`.**

A bridge entre os dois domínios é exclusivamente o módulo `borurio-web`. Quando o módulo fiscal precisa de dados da empresa emitente, recebe o record `CertificadoContexto` (apenas tipos JDK) em vez de receber a entidade `Empresa`.

### 2.4 Stack tecnológica

| Componente            | Versão / Tecnologia                                              |
|-----------------------|------------------------------------------------------------------|
| Linguagem             | Java 17                                                          |
| Framework             | Spring Boot 3.3.2                                                |
| Persistência          | MyBatis (annotations)                                            |
| Banco de dados        | MySQL 8.4                                                        |
| Migrations            | Flyway (V001–V028)                                               |
| Auth                  | JWT stateless (HMAC-SHA256)                                      |
| Segurança             | Spring Security 6.x                                              |
| XML Signing           | Java XML Crypto API (`javax.xml.crypto.dsig`)                    |
| SOAP                  | HTTPS direto (sem CXF, sem wsimport)                             |
| Cache de certificados | `ConcurrentHashMap` em memória                                   |
| Container             | Docker (imagem interna); porta 8081 em HOM                       |
| API docs              | springdoc-openapi 2.6.0 — Swagger UI em `/swagger-ui/index.html` |

---

## 3. MODELO DE DADOS FISCAL

### 3.1 Migrations aplicadas (V001–V024)

| Migration   | Descrição                                                                     |
|-------------|-------------------------------------------------------------------------------|
| V001        | `cliente` (legado, não usado no fluxo fiscal principal)                       |
| V002        | `nfe_log` — auditoria de eventos fiscais                                      |
| V003        | Dados mock de referência                                                      |
| V008        | `db_user` — autenticação e autorização                                        |
| V009        | `produto`                                                                     |
| V011        | `nfe_sequencia` — controle de número por série/CNPJ                           |
| V012        | `nfe_documento` — estado fiscal de cada NF-e autorizada                       |
| V013        | `produto` — campos fiscais: origem, csosn, estoque                            |
| V014        | `pedido` + `pedido_item`                                                      |
| V015        | `pedido_item` — snapshot fiscal (ncm, cfop, csosn, origem, unidade)           |
| V016        | `empresa` — cadastro multiemitente                                            |
| V017        | `empresa_id` em `db_user`, `produto`, `pedido`                                |
| V018        | `empresa` — certificado A1 por empresa (cert_path, cert_senha, cert_tipo)     |
| V019        | `db_user.role` (ADMIN / OPERADOR) + `nfe_log.empresa_id`                      |
| V020        | `cliente.empresa_id` — isolamento multiempresa de clientes                    |
| V021        | `cliente.nome` e `cliente.email` nullable                                     |
| V022        | Foreign key constraints ausentes em `pedido_item`, `nfe_documento`, `nfe_log` |
| V023        | `estoque_movimento` — auditoria de reservas, baixas, estornos e entradas                              |
| V024        | `produto.estoque_reservado` DECIMAL(13,4) NOT NULL DEFAULT 0                                         |
| V025        | `oms_api_key` — chaves de API para integradores OMS (hash SHA-256, `integrator_id`)                  |
| V026        | `oms_fiscal_authorization` — slot de autorização por cliente OMS (`integrator_id`, `codigo_oms`, `jti`, `emitido_em`) |
| V027        | `oms_company_certificate` — certificado PKCS12 por `auth_id` com estrutura mono-CNPJ inicial         |
| V028        | Multi-CNPJ: `oms_fiscal_authorization` slot sem `empresa_id`; `oms_company_certificate` adiciona `cnpj`, `empresa_id`, coluna gerada `cnpj_ativo_unico` |

### 3.2 Tabelas fiscais principais

#### `nfe_documento`
Armazena o estado persistido de cada NF-e emitida. Fonte de verdade para consultas offline e reemissão de DANFE.

| Coluna          | Tipo          | Descrição                                                          |
|-----------------|---------------|--------------------------------------------------------------------|
| `chave_nfe`     | VARCHAR(44)   | Chave de acesso (44 dígitos)                                       |
| `numero`        | VARCHAR(9)    | Número da NF-e                                                     |
| `serie`         | VARCHAR(3)    | Série                                                              |
| `cnpj_emitente` | VARCHAR(14)   | CNPJ sem máscara                                                   |
| `cnpj_cpf_dest` | VARCHAR(14)   | Destinatário                                                       |
| `c_stat`        | VARCHAR(10)   | Código de status SEFAZ (`"100"` = autorizada, `"101"` = cancelada) |
| `x_motivo`      | VARCHAR(255)  | Motivo retornado pela SEFAZ                                        |
| `n_prot`        | VARCHAR(20)   | Número do protocolo de autorização (15 dígitos)                    |
| `valor_total`   | DECIMAL(13,2) | Valor total da NF-e                                                |
| `xml_nfe`       | LONGTEXT      | XML assinado sem protocolo                                         |
| `xml_protocolo` | LONGTEXT      | nfeProc completo (arquivamento fiscal — 5 anos)                    |
| `tp_amb`        | INT           | 1=produção / 2=homologação                                         |
| `dh_recbto`     | DATETIME      | Data/hora de recebimento pela SEFAZ                                |
| `data_emissao`  | DATETIME      | Data/hora da emissão (`dhEmi` do XML)                              |

#### `nfe_log`
Registro de auditoria de cada operação fiscal.

| Coluna        | Tipo         | Descrição                                                               |
|---------------|--------------|-------------------------------------------------------------------------|
| `chave_nfe`   | VARCHAR(44)  | Chave associada ao evento                                               |
| `tipo_evento` | VARCHAR(100) | `ENVIO_NFE` / `TRANSMISSAO_SEFAZ` / `CONSULTA` / `CANCELAMENTO` / `CCE` |
| `status`      | VARCHAR(20)  | `SUCCESS` / `ERROR` / `PENDING`                                         |
| `usuario`     | VARCHAR(100) | Usuário autenticado (`SecurityContextHolder`)                           |
| `empresa_id`  | BIGINT       | ID da empresa emitente (V019)                                           |
| `xml_envio`   | LONGTEXT     | XML transmitido                                                         |
| `xml_retorno` | LONGTEXT     | Resposta SOAP da SEFAZ                                                  |

#### `nfe_sequencia`
Garante unicidade atômica do número da NF-e por CNPJ + série.

| Coluna          | Descrição             |
|-----------------|-----------------------|
| `cnpj`          | CNPJ do emitente      |
| `serie`         | Série da NF-e         |
| `ultimo_numero` | Último número emitido |

#### `oms_api_key` (V025)
Chaves de API para integradores OMS. Autenticam o endpoint `/api/integration/fiscal-authorizations`.

| Coluna          | Tipo         | Descrição                                               |
|-----------------|--------------|---------------------------------------------------------|
| `integrator_id` | BIGINT       | ID do integrador (agrupa clientes OMS deste integrador) |
| `hash`          | VARCHAR(64)  | SHA-256 hex da chave plaintext                          |
| `descricao`     | VARCHAR(100) | Nome descritivo da chave                                |
| `ativo`         | TINYINT(1)   | 1 = ativa; 0 = revogada                                 |

#### `oms_fiscal_authorization` (V026/V028)
Slot de autorização por cliente OMS. Um registro por `(integrator_id, codigo_oms)`.

| Coluna           | Tipo         | Descrição                                                             |
|------------------|--------------|-----------------------------------------------------------------------|
| `empresa_id`     | BIGINT       | Empresa âncora — primeira empresa vinculada ao cliente OMS            |
| `integrator_id`  | BIGINT       | FK → `oms_api_key.integrator_id`                                      |
| `codigo_oms`     | VARCHAR(100) | Identificador único do cliente no sistema OMS                         |
| `jti`            | VARCHAR(36)  | UUID do JWT — estável durante toda a vida do token                    |
| `token_expira_em`| DATETIME     | Data de expiração do token (data `notAfter` do certificado mais recente) |
| `emitido_em`     | DATETIME     | Timestamp de emissão (precisão de segundos — garantia de determinismo do JWT) |
| `revogado_em`    | DATETIME     | NULL enquanto ativo; preenchido na revogação manual                   |

#### `oms_company_certificate` (V027/V028)
Certificado PKCS12 por CNPJ por autorização OMS. Múltiplas linhas por `auth_id`.

| Coluna             | Tipo         | Descrição                                                          |
|--------------------|--------------|--------------------------------------------------------------------|
| `auth_id`          | BIGINT       | FK → `oms_fiscal_authorization.id`                                 |
| `empresa_id`       | BIGINT       | FK → `empresa.id` — empresa correspondente ao CNPJ                 |
| `cnpj`             | VARCHAR(14)  | CNPJ do certificado (14 dígitos, sem formatação)                   |
| `thumbprint`       | VARCHAR(64)  | SHA-256 hex do certificado X.509 — usado para detectar troca de cert |
| `cert_pfx_enc`     | LONGBLOB     | PFX criptografado via AES-256-GCM                                  |
| `cert_senha_enc`   | VARCHAR(512) | Senha criptografada via AES-256-GCM                                |
| `ativo`            | TINYINT(1)   | 1 = certificado ativo para este CNPJ; 0 = desativado (histórico)  |
| `cnpj_ativo_unico` | VARCHAR(14)  | Coluna gerada: `IF(ativo=1, cnpj, NULL)` — UNIQUE por `(auth_id, cnpj_ativo_unico)` |

---

## 4. FLUXO COMPLETO DE EMISSÃO NF-e

### 4.1 Sequência de chamadas (pedido → NF-e)

```
Cliente HTTP
    │
    │  POST /api/app/pedidos/{id}/emitir
    │
    ▼
PedidoController (borurio-web)
    │
    │  pedidoEmissaoService.emitir(id)
    │
    ▼
PedidoEmissaoService (borurio-web)
    │  ├─ pedidoService.buscarComItens(id)      → valida status ∈ {RASCUNHO, REJEITADO, ERRO} (v1.9)
    │  ├─ montarRequest(pedido)                  → NfeEmissaoRequest com snapshot fiscal
    │  ├─ resolverEmpresa(empresaId)              → Empresa do contexto JWT
    │  │
    │  │  nfeGeracaoService.gerar(req, empresa)
    │  │
    ▼  ▼
NfeGeracaoService (borurio-web)
    │  ├─ validarEnderecoEmitente(empresa)        → (v1.9) lança EMITTER_ADDRESS_INCOMPLETE ANTES de montar XML, se endereço incompleto
    │  ├─ montarIde / montarEmit / montarDest / montarDet / montarTotal
    │  ├─ nfeXmlBuilder.build(nfe)               → XML sem assinatura
    │  ├─ sequenciaService.proximoNumero()        → número atômico por série/CNPJ
    │  ├─ empresaCertificadoService.resolverPorEmpresa() → CertificadoContexto ou null
    │  │
    │  │  nfeOrquestradorService.processar(xml, cnpj, certCtx)
    │  │
    ▼  ▼
NfeOrquestradorService (borurio-fiscal)
    │  ├─ [1] converterParaDocument()             → parse XML com namespace-aware
    │  ├─ [2] xsdValidator.validate()             → contra xsd/custom/nfe_v4.00_consolidado.xsd — lança XmlSchemaValidationException (v1.9)
    │  ├─ [3] assinaturaXmlService.assinar()      → XMLDSIG RSA-SHA256 + C14N
    │  └─ [4] nfeTransmitService.transmitirXml()  → SOAP HTTPS → SEFAZ
    │
    ▼
NfeTransmitServiceImpl (borurio-fiscal)
    │  ├─ criarEnvelopeEnviNFe()                  → lote com 1 NF-e
    │  ├─ enviarSoap(url, envelope, sslContext)    → HTTPS POST (retry via SefazRetryConfig/resilience4j)
    │  └─ salvarLogSeguro(nfeLog)                  → nfe_log com usuario real
    │
    ▼
NfeGeracaoService (retorno)
    │  ├─ retornoParser.parse(soap)                → NfeSefazRetorno { cStat, xMotivo, nProt }
    │  ├─ documentoService.salvarComRetorno()      → nfe_documento persistido
    │  └─ registrarLog()                           → nfe_log com empresa_id
    │
    ▼
PedidoEmissaoService (pós-emissão)
    │  ├─ resolverStatus(retorno)                  → AUTORIZADO / REJEITADO / AGUARDANDO
    │  ├─ pedidoService.atualizarStatus()          → pedido.status + chave_nfe (preserva chaveNfe existente se a nova tentativa falhar, v1.9)
    │  ├─ baixarEstoque()                          → somente se AUTORIZADO (cStat=100)
    │  └─ (v1.9) se REJEITADO: lança BusinessException.sefazRejected(cStat, xMotivo) — HTTP 422, não retorna 200
```

Se uma exceção for lançada durante `nfeGeracaoService.gerar()`, o serviço chama `traduzirFalhaTransmissao()` (v1.9) pra classificar a causa antes de relançar: `XmlSchemaValidationException` → `XML_SCHEMA_INVALID` (422); `SocketTimeoutException` → `SEFAZ_TIMEOUT` (503, retryable); `ConnectException`/`UnknownHostException` → `SEFAZ_UNAVAILABLE` (503, retryable); qualquer outra exceção não classificada → HTTP 500 genérico (retryable=false). Em todos os casos o pedido fica em `ERRO`, preservando a `chaveNfe` que já existia (se houver).

### 4.2 Status semântico do pedido

| Status       | Condição                                                             | Estoque baixado?   |
|--------------|----------------------------------------------------------------------|--------------------|
| `RASCUNHO`   | Pedido criado, ainda não emitido                                     | Não                |
| `AUTORIZADO` | `cStat = 100` da SEFAZ                                               | Sim                |
| `AGUARDANDO` | Lote aceito (`cStat = 104`) sem infProt; ou falha ao parsear retorno | Não                |
| `REJEITADO`  | `cStat >= 200` — HTTP 422 `SEFAZ_REJECTED` (v1.9, não mais HTTP 200) | Não — reserva desfeita |
| `ERRO`       | Exceção durante a transmissão — HTTP 422/500/503 conforme classificação (v1.9) | Não — reserva desfeita |
| `CANCELADO`  | Evento de cancelamento autorizado                                    | N/A                |

> **v1.9:** `REJEITADO` e `ERRO` deixaram de ser terminais — `PedidoEmissaoService.STATUS_EMISSIVEIS = {RASCUNHO, REJEITADO, ERRO}` permite chamar `/emitir` de novo no mesmo `pedidoId`. Cada nova tentativa gera `nNF`/`chaveNfe` novos via `NfeSequenciaService`, sem risco de duplicidade na SEFAZ.

### 4.3 Snapshot fiscal imutável

Ao criar o pedido, o `PedidoServiceImpl` executa `preencherSnapshot()` que copia os campos fiscais do `Produto` para o `PedidoItem`:

```
PedidoItem.codigoProduto ← Produto.codigo
PedidoItem.descricao     ← Produto.descricao
PedidoItem.ncm           ← Produto.ncm
PedidoItem.cfop          ← Produto.cfop
PedidoItem.unidade       ← Produto.unidade
PedidoItem.origem        ← Produto.origem  (default: 0)
PedidoItem.csosn         ← Produto.csosn   (default: "400")
```

**Invariante:** após a criação do pedido, qualquer alteração posterior no cadastro do produto não afeta os dados fiscais do pedido. A emissão sempre usa o snapshot congelado no `pedido_item`.

---

## 5. GERAÇÃO DO XML NF-e

### 5.1 Componentes envolvidos

| Classe                   | Módulo         | Responsabilidade                                        |
|--------------------------|----------------|---------------------------------------------------------|
| `NfeGeracaoService`      | borurio-web    | Monta os blocos da NF-e a partir de `NfeEmissaoRequest` |
| `NfeXmlBuilder`          | borurio-fiscal | Serializa o objeto `NFe` em XML via JAXB                |
| `NfeOrquestradorService` | borurio-fiscal | Orquestra validação + assinatura + transmissão          |

### 5.2 Cálculo da chave de acesso (44 dígitos)

```
chave43 = cUF(2) + aaaMM(4) + CNPJ(14) + mod(2=55) + serie(3) + nNF(9) + tpEmis(1) + cNF(8)
cDV     = módulo 11 sobre chave43
chave   = chave43 + cDV
```

O `cNF` (código numérico) é gerado com `SecureRandom.nextInt(100_000_000)` — uso de `java.security.SecureRandom` para garantir imprevisibilidade criptográfica.

O número da NF-e (`nNF`) é obtido de forma atômica via `NfeSequenciaService.proximoNumero(cnpj, serie)` — usa `SELECT ... FOR UPDATE` (ou equivalente) para garantir unicidade mesmo em ambientes concorrentes.

### 5.3 Estrutura XML gerada (simplificada)

```xml
<NFe versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
  <infNFe Id="NFe{44-digitos}" versao="4.00">
    <ide>     <!-- identificação -->
    <emit>    <!-- emitente: CNPJ, razão social, endereço, IE, CRT -->
    <dest>    <!-- destinatário: CPF/CNPJ, razão, endereço -->
    <det>     <!-- itens: produto, NCM, CFOP, ICMS(CSOSN), PIS, COFINS -->
    <total>   <!-- ICMSTot com 20 campos obrigatórios -->
    <transp>  <!-- modFrete=9 (sem frete) -->
    <pag>     <!-- detPag: indPag=0, tPag=01, vPag=total -->
  </infNFe>
  <Signature>  <!-- inserido pelo AssinaturaXmlService após validação XSD -->
</NFe>
```

### 5.4 Campos fiscais por produto

Cada item do XML (`<det>`) utiliza os dados congelados no `PedidoItem`:

| Bloco NF-e           | Campo   | Origem                                                     |
|----------------------|---------|------------------------------------------------------------|
| `<prod>`             | `cProd` | `item.codigoProduto`                                       |
| `<prod>`             | `NCM`   | `item.ncm` (8 dígitos, validado contra tabela NCM oficial) |
| `<prod>`             | `CFOP`  | `item.cfop`                                                |
| `<prod>`             | `uCom`  | `item.unidade`                                             |
| `<ICMS>`             | `orig`  | `item.origem`                                              |
| `<ICMS>`             | `CSOSN` | `item.csosn` (ex: `400` = CRT 1 sem tributação ICMS)       |
| `<PIS>` / `<COFINS>` | `CST`   | `07` (operação isenta)                                     |

### 5.5 Validação XSD pré-assinatura

Antes de assinar, o `NfeOrquestradorService` valida o XML contra:

```
borurio-fiscal/src/main/resources/xsd/custom/nfe_v4.00_consolidado.xsd
```

Este schema consolida `leiauteNFe_v4.00.xsd` + `tiposBasico_v4.00.xsd` em um único arquivo para resolver dependências de classpath. A validação usa a API JAXP (`javax.xml.validation`).

---

## 6. ASSINATURA DIGITAL XMLDSIG

### 6.1 Algoritmos (NT 2019.001 — obrigatórios NF-e 4.00)

| Algoritmo       | URI                                                                |
|-----------------|--------------------------------------------------------------------|
| Assinatura      | `http://www.w3.org/2001/04/xmldsig-more#rsa-sha256` (RSA-SHA256)   |
| Digest          | `http://www.w3.org/2001/04/xmlenc#sha256` (SHA-256)                |
| Canonicalização | `http://www.w3.org/TR/2001/REC-xml-c14n-20010315` (C14N Inclusivo) |
| Transform 1     | `ENVELOPED` (remove o próprio elemento Signature do digest)        |
| Transform 2     | C14N Inclusivo                                                     |

### 6.2 Fluxo de assinatura

```
AssinaturaXmlService.assinar(xmlNfe, ctx?)
    │
    ├─ 1. parse XML → Document (namespace-aware, anti-XXE)
    ├─ 2. localizarElementoPorTag("infNFe") → Element
    ├─ 3. infNFe.setIdAttribute("Id", true)  → registra Id como tipo ID no DOM
    ├─ 4. XMLSignatureFactory.getInstance("DOM")
    ├─ 5. Reference("#" + id, SHA-256, [ENVELOPED, C14N])
    ├─ 6. SignedInfo(C14N, RSA-SHA256, [reference])
    ├─ 7. KeyInfo(X509Data(cert))
    ├─ 8. XMLSignature.sign(DOMSignContext(privateKey, nfeElement))
    └─ 9. serializar(doc) → UTF-8, sem declaração XML, sem INDENT
```

### 6.3 Posição do elemento Signature

O `<Signature>` é inserido como filho direto do elemento `<NFe>`, **após** o `<infNFe>`:

```xml
<NFe>
  <infNFe Id="NFe...">...</infNFe>
  <Signature xmlns="http://www.w3.org/2000/09/xmldsig#">
    <SignedInfo>...</SignedInfo>
    <SignatureValue>...</SignatureValue>
    <KeyInfo><X509Data><X509Certificate>...</X509Certificate></X509Data></KeyInfo>
  </Signature>
</NFe>
```

O mesmo padrão é usado para eventos (`infEvento`) e inutilização (`infInut`), com `assinarEvento()` e `assinarInutilizacao()`.

### 6.4 Proteções contra XXE

O `DocumentBuilderFactory` usado em toda a assinatura tem as seguintes proteções:

```java
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
```

---

## 7. TRANSMISSÃO SOAP PARA SEFAZ

### 7.1 Endpoint de homologação (SP)

| Serviço           | URL HOM                                                                   |
|-------------------|---------------------------------------------------------------------------|
| Autorização NF-e  | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeautorizacao4.asmx`       |
| Status Serviço    | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfestatusservico4.asmx`     |
| Consulta Situação | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeconsultaprotocolo4.asmx` |

### 7.2 Envelope SOAP 1.2

O `NfeTransmitServiceImpl` monta o envelope diretamente (sem gerador WSDL):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
  <soap12:Body>
    <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
      <enviNFe versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
        <idLote>{15 dígitos}</idLote>
        <indSinc>1</indSinc>
        {xmlAssinado}
      </enviNFe>
    </nfeDadosMsg>
  </soap12:Body>
</soap12:Envelope>
```

`indSinc=1` → processamento síncrono (retorno imediato). Lote com 1 NF-e.

### 7.3 Autenticação mTLS

A conexão com a SEFAZ usa **autenticação mútua TLS** (mTLS):

- O sistema apresenta o certificado A1 do emitente no `KeyManagerFactory`
- `SSLContext` configurado com `TLSv1.2`
- Certificado carregado do KeyStore PKCS12

Quando não há certificado por empresa configurado, usa o certificado global do `CertificadoServiceImpl`.

### 7.4 Interpretação do retorno

A SEFAZ retorna um envelope SOAP com `retEnviNFe`. O `NfeSefazRetornoParser` extrai:

| Campo     | Descrição                                                              |
|-----------|------------------------------------------------------------------------|
| `cStat`   | Código de status (100 = autorizado, 104 = aguardando, 2xx+ = rejeição) |
| `xMotivo` | Descrição textual do status                                            |
| `nProt`   | Número do protocolo de autorização (presente apenas se cStat=100)      |
| `chNFe`   | Chave de acesso retornada pela SEFAZ                                   |

---

## 8. PERSISTÊNCIA E AUDITORIA FISCAL

### 8.1 nfe_documento

Após cada transmissão, o `NfeDocumentoService.salvarComRetorno()` persiste:

```
nfe_documento {
    chave_nfe, numero, serie, cnpj_emitente,
    cnpj_cpf_dest, razao_dest, valor_total,
    c_stat, x_motivo, n_prot,
    xml_nfe,          ← XML assinado sem protocolo
    xml_protocolo,    ← nfeProc completo (arquivamento 5 anos)
    tp_amb,           ← 1=produção / 2=homologação
    dh_recbto,        ← data/hora recebimento SEFAZ
    data_emissao
}
```

### 8.2 nfe_log (dupla camada de auditoria)

Dois eventos distintos são gravados por emissão:

| Tipo evento         | Gerado por               | empresa_id    | usuario                                       |
|---------------------|--------------------------|---------------|-----------------------------------------------|
| `ENVIO_NFE`         | `NfeTransmitServiceImpl` | NULL          | Usuário autenticado (`SecurityContextHolder`) |
| `TRANSMISSAO_SEFAZ` | `NfeGeracaoService`      | ID da empresa | NULL                                          |

> **Nota de design:** a dupla camada é intencional e é consequência de DA-01 (seção 15). O `NfeTransmitServiceImpl` pertence ao módulo `borurio-fiscal` (sem dependência de `Empresa`), por isso não grava `empresa_id`. O `NfeGeracaoService` pertence ao módulo `borurio-web` (com acesso a `Empresa`), por isso não tem acesso ao `SecurityContextHolder` no mesmo instante da transmissão.

### 8.3 Garantias de falha de log

Falhas de persistência no `nfe_log` nunca interrompem o fluxo fiscal:

```java
// NfeGeracaoService
try {
    nfeLogService.salvar(log);
} catch (Exception logEx) {
    log.error("[NfeGeracao] Falha ao persistir nfe_log | chave={} | erro={}",
              chave, logEx.getMessage());
}
```

A mesma proteção existe em `NfeTransmitServiceImpl.salvarLogSeguro()`.

---

## 9. MULTIEMPRESA

### 9.1 Modelo de isolamento

```
db_user.empresa_id  → empresa a que o usuário pertence
produto.empresa_id  → produtos isolados por empresa
pedido.empresa_id   → pedidos isolados por empresa
nfe_log.empresa_id  → auditoria segmentada por empresa
```

### 9.2 Propagação do contexto via JWT

```
Login (POST /auth/login)
    │
    │  AuthService.authenticate()
    │      └─ dbUserMapper.findByEmail(username)  ← username é o email do usuário
    │      └─ DbUser.empresaId → embutido no token como claim "eid"
    │      └─ jwtUtil.generateToken(email, empresaId)
    │               JWT payload: { "sub": "email@empresa.com", "eid": 1, "exp": ... }
    ▼
Toda requisição autenticada
    │
    │  JwtFilter.doFilterInternal()
    │      └─ jwtUtil.extractEmpresaId(token) → Long (claim "eid")
    │      └─ EmpresaContextHolder.set(empresaId)  ← ThreadLocal
    │      finally: EmpresaContextHolder.clear()   ← sem vazamento entre requests
    ▼
Controllers
    │
    │  Long empresaId = EmpresaContextHolder.get()
    │  → produto.setEmpresaId(empresaId)
    │  → pedido.setEmpresaId(empresaId)
    │  → listarPorEmpresa(empresaId)  vs  listarTodos()
```

### 9.3 Fallback de compatibilidade

Quando `empresaId == null` (usuário sem empresa associada, ou ambiente dev sem JWT):

- `listarTodos()` é usado no lugar de `listarPorEmpresa()`
- `EmitenteProperties` é usado como emitente no lugar de `Empresa`
- Dados legados (anteriores à V017) são backfillados para a empresa padrão no `StartupListener`

### 9.5 Multi-CNPJ OMS (V028)

O modelo V028 expande o multiempresa para integradores externos que possuem múltiplos CNPJs emitentes. Um cliente OMS (`codigoEmpresaOms`) pode autorizar múltiplos CNPJs sob o **mesmo token JWT**.

#### Fluxo de autorização

```
POST /api/integration/fiscal-authorizations
X-Api-Key: {chave-tecnica}
Body: { codigoEmpresaOms, cnpj, certBase64, certSenha }
    │
    ▼
OmsFiscalAuthorizationService.autorizar()
    │  ├─ Valida X-Api-Key (SHA-256 → oms_api_key)
    │  ├─ Decodifica e carrega PKCS12
    │  ├─ Extrai X509Certificate, valida validade e CNPJ do Subject
    │  ├─ Localiza ou cria empresa a partir do Subject X.509 (auto-criação)
    │  ├─ Busca slot em oms_fiscal_authorization por (integrator_id, codigo_oms)
    │  │
    │  ├─ CASO A — slot inexistente → INSERT auth + cert; gera JTI novo; emitidoEm=now().truncatedTo(SECONDS)
    │  ├─ CASO B — mesmo CNPJ, mesmo thumbprint → nenhuma alteração; retorna token existente
    │  ├─ CASO C — mesmo CNPJ, thumbprint diferente → desativa cert anterior; INSERT cert novo; atualiza tokenExpiraEm; mantém JTI
    │  └─ CASO D — CNPJ novo → INSERT cert; mantém JTI e token intactos
    │
    └─ jwtUtil.generateOmsToken(codigoOms, empresaId_âncora, jti, tokenExpiraEm, emitidoEm)
         payload: { "sub": codigoOms, "eid": empresaId_âncora, "jti": uuid, "tipo": "OMS", "iat": emitidoEm, "exp": tokenExpiraEm }
```

#### Token determinístico

O `iat` do token JWT é sempre `auth.getEmitidoEm()` — gravado no banco com precisão de segundos. Isso garante que nos cenários B/C/D, onde o JTI é reutilizado, o token string gerado é **identicamente igual** ao original. Sem essa garantia, diferenças de milissegundos entre `new Date()` e `CURRENT_TIMESTAMP` produziriam tokens distintos a cada chamada.

#### Resolução de CNPJ na emissão

Em `POST /api/app/pedidos`, o campo `cnpjEmitente` seleciona qual certificado OMS usar:

```
PedidoController.criar()
    │  ├─ extrai jti do JWT (claim "jti")
    │  ├─ valida cnpjAutorizadoParaJti(jti, cnpjEmitente) — fail-fast HTTP 403
    │  └─ persiste pedido com cnpjEmitente no snapshot

PedidoEmissaoService.emitir()
    │  └─ OmsCertificadoService.resolverPorJtiECnpj(jti, cnpjEmitente)
              └─ buscarAtivoPorAuthIdECnpj(authId, cnpjEmitente) → CertificadoContexto
```

#### Separação de responsabilidades: empresa fiscal vs. empresa do catálogo (DA-08)

O fluxo multi-CNPJ introduz dois conceitos de "empresa" com responsabilidades distintas que **não devem ser confundidos**:

| Conceito | Fonte | Usado para |
|---|---|---|
| **Empresa fiscal do emitente** | `empresaMapper.buscarPorCnpj(cnpjEmitente)` | XML NF-e (`<emit>`), certificado de assinatura |
| **Empresa-âncora do pedido** | `pedido.getEmpresaId()` (claim `eid` do token) | Estoque, reserva, baixa, busca de produto |

Os produtos são cadastrados com o token OMS, cujo `eid` aponta para a empresa-âncora (primeiro CNPJ autorizado do cliente OMS). Por isso, todas as operações de catálogo e estoque usam `pedido.getEmpresaId()` — e não o id da empresa fiscal resolvida pelo CNPJ emitente.

> **Bug corrigido em 30-06-2026 (commit `117a447`):** `PedidoEmissaoService.emitir()` anteriormente resolvia `empresaId = empresa.getId()` onde `empresa` era obtida via `buscarPorCnpj(cnpjEmitente)`. Para o segundo CNPJ (empresa fiscal `id=2`), isso fazia com que `estoqueService.reservarItens()` buscasse o produto com `empresa_id=2` — mas os produtos foram cadastrados com `empresa_id=1` (empresa-âncora). Resultado: `PRODUCT_NOT_FOUND`. A correção: `empresaId = pedido.getEmpresaId()` como valor primário para operações de estoque, com `empresa` (fiscal) usado exclusivamente para geração do XML e seleção do certificado.

### 9.4 Seed e backfill no startup

O `StartupListener` (`@PostConstruct`) executa na inicialização:

1. **Seed empresa padrão** — lê `fiscal.emitente.*` do `application.properties` e cria `empresa` se não existir
2. **Backfill** — atualiza `empresa_id` em `db_user`, `produto` e `pedido` onde `empresa_id IS NULL`
3. **Seed admin** — cria usuário `admin` com `role='ADMIN'` se `db_user` estiver vazio

---

## 10. CERTIFICADO DIGITAL POR EMPRESA

### 10.1 Campos no banco

```sql
ALTER TABLE empresa
    ADD COLUMN cert_path  VARCHAR(255) NULL,   -- caminho classpath ou filesystem
    ADD COLUMN cert_senha VARCHAR(255) NULL,   -- senha do KeyStore (criptografada)
    ADD COLUMN cert_tipo  VARCHAR(10)  NULL DEFAULT 'PKCS12';
```

### 10.2 Record CertificadoContexto

Define o contrato de passagem de credenciais entre os módulos sem expor a entidade `Empresa` ao módulo fiscal:

```java
// borurio-fiscal — apenas tipos JDK
public record CertificadoContexto(
    Long empresaId,
    PrivateKey privateKey,
    X509Certificate certificate,
    SSLContext sslContext
) {}
```

### 10.3 Fluxo de resolução de certificado

```
PedidoEmissaoService / NfeGeracaoService
    │
    │  empresaCertificadoService.resolverPorEmpresa(empresa)
    │
    ▼
EmpresaCertificadoService
    │  if empresa.certPath == null → Optional.empty()   ← fallback para cert global
    │  else → cache.computeIfAbsent(empresaId, ...)
    │              └─ carregarContexto(empresa)
    │                    ├─ CertSenhaEncryptor.decrypt(certSenha)
    │                    ├─ KeyStore.load(inputStream, senha)
    │                    ├─ resolverAlias() → isKeyEntry()
    │                    ├─ PrivateKey + X509Certificate
    │                    └─ SSLContext TLSv1.2 (KeyManagerFactory)
    │
    └─ CertificadoContexto { empresaId, privateKey, cert, sslContext }
```

### 10.4 Cache de certificados

O cache `ConcurrentHashMap<Long, CertificadoContexto>` persiste durante a vida do container. Para invalidar (ex: após atualização do certificado):

```
EmpresaCertificadoService.invalidar(empresaId)
```

> **Implementado:** o `EmpresaController.atualizar()` chama `empresaCertificadoService.invalidar(id)` após persistir a atualização. O cache é invalidado automaticamente toda vez que os dados da empresa são alterados via `PUT /api/app/empresas/{id}`.

### 10.5 Resolução de arquivo do certificado

O `EmpresaCertificadoService` tenta em ordem:

1. **Classpath** via `ClassPathResource(path)`
2. **Filesystem** via `new File(path)`

Caso nenhum dos dois encontre o arquivo, lança `IllegalStateException`.

### 10.6 Certificado OMS — carregamento por JTI e CNPJ (V028)

Complementa a resolução por empresa (`resolverPorEmpresa`) com resolução por CNPJ OMS:

```
OmsCertificadoService.resolverPorJtiECnpj(jti, cnpj)
    │  ├─ omsAuthMapper.buscarPorJti(jti)        → OmsFiscalAuthorization
    │  ├─ verifica revogado_em == null
    │  ├─ omsCertMapper.buscarAtivoPorAuthIdECnpj(authId, cnpj) → OmsCompanyCertificate
    │  └─ carregarContexto(certRow.getEmpresaId(), certRow)
              ├─ encryptor.decryptBytes(certPfxEnc) → PFX bytes
              ├─ encryptor.decrypt(certSenhaEnc)    → senha
              ├─ KeyStore.load(pfxBytes, senha)
              └─ CertificadoContexto { empresaId, privateKey, cert, sslContext }
```

Não há cache para certificados OMS — verificação de revogação ocorre a cada emissão.

---

## 11. SEGURANÇA BASE

### 11.1 Autenticação JWT

- Algoritmo: HMAC-SHA256
- Payload: `{ "sub": email, "eid": empresaId, "iat": ..., "exp": ... }`
- Validade configurável via `security.jwt.expiration-ms` (padrão: 3600000ms = 1 hora)
- Chave secreta via `security.jwt.secret` (Base64 ou string raw, ≥ 32 bytes)
- Filter: `JwtFilter extends OncePerRequestFilter` — extrai token do header `Authorization: Bearer <token>`, popula `EmpresaContextHolder`, limpa no `finally`

> **Nota:** o campo `username` no body do login (`POST /auth/login`) é semanticamente um e-mail — o `AuthService` chama `dbUserMapper.findByEmail(username)` internamente.

### 11.2 Controle de acesso (RBAC)

| Role          | Valor em `db_user.role`   | Permissões                                                                  |
|---------------|---------------------------|-----------------------------------------------------------------------------|
| Administrador | `ADMIN`                   | Todas as operações, incluindo criar/atualizar empresas e gerenciar usuários |
| Operador      | `OPERADOR`                | Operações de negócio (produtos, pedidos, emissão fiscal)                    |

Restrições aplicadas em `SecurityConfig` (Sprint 3 — validado em HOM 12-05-2026):

```java
// Rotas ADMIN-only
.requestMatchers(new AntPathRequestMatcher("/api/app/empresas", "POST")).hasRole("ADMIN")
.requestMatchers(new AntPathRequestMatcher("/api/app/empresas/**", "PUT")).hasRole("ADMIN")
.requestMatchers(new AntPathRequestMatcher("/api/app/usuarios")).hasRole("ADMIN")
.requestMatchers(new AntPathRequestMatcher("/api/app/usuarios/**")).hasRole("ADMIN")

// Tudo mais exige autenticação
.anyRequest().authenticated()
```

> **Atenção:** `/api/app/usuarios` (sem trailing slash) e `/api/app/usuarios/**` são matchers distintos e ambos necessários — `AntPathRequestMatcher("/api/app/usuarios/**")` não cobre o path raiz sem segmento adicional.

### 11.3 Endpoints públicos (sem autenticação)

Os seguintes paths são liberados pelo `SecurityConfig` e pulados pelo `JwtFilter`:

| Path                                   | Observação                             |
|----------------------------------------|----------------------------------------|
| `/auth/**`                             | Login e operações de autenticação                                      |
| `/api/test/**`                         | Health check — `GET /api/test/ping`                                    |
| `/api/fiscal/nfe/test/**`              | Testes internos do motor fiscal                                        |
| `/api/integration/**`                  | Autorização fiscal OMS — autenticado por `X-Api-Key`, não por JWT      |
| `/swagger-ui/**`, `/swagger-ui.html`   | Documentação Swagger                                                   |
| `/v3/api-docs/**`, `/v3/api-docs.yaml` | Especificação OpenAPI                                                  |
| `/ping`                                | Path sem controller mapeado — não usar                                 |

> **Nota operacional:** `/ping` está listado no `permitAll` e no `JwtFilter.PUBLIC_EXACT`, mas nenhum controller mapeia este path. O endpoint correto de health check é `GET /api/test/ping`.

### 11.4 Respostas de erro de segurança

Erros de autenticação e autorização são tratados diretamente pelo Spring Security (antes do `GlobalExceptionHandler`) e têm estrutura própria:

```json
{ "code": 401, "message": "Autenticação necessária", "success": false }
{ "code": 403, "message": "Acesso negado",           "success": false }
```

Todos os outros erros da aplicação retornam o envelope padrão `Result<>` com `"data": null`. Ver `GlobalExceptionHandler` para o mapeamento completo.

### 11.5 Criptografia de cert_senha (AES-256-GCM)

**Formato armazenado no banco:**

```
ENC(<base64(iv_12bytes + ciphertext)>)
```

**Configuração:**

```bash
# Gerar chave AES-256 (32 bytes, Base64)
openssl rand -base64 32

# Variável de ambiente no container
CERT_ENCRYPTION_KEY=<saída do openssl>

# application.properties
cert.encryption.key=${CERT_ENCRYPTION_KEY:}
```

**Modo passthrough:** se `CERT_ENCRYPTION_KEY` não estiver configurada, o `CertSenhaEncryptor` opera em modo transparente (sem criptografia) e emite `WARN` nos logs. Adequado para desenvolvimento local e HOM sem certificado configurado no banco.

**Migração graceful:** valores sem prefixo `ENC(` são tratados como texto claro pelo `decrypt()`, permitindo migração incremental.

### 11.6 Proteções XML (anti-XXE)

Todos os parsers XML do sistema são configurados com:

```java
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
```

---

## 12. OPERAÇÕES FISCAIS PÓS-EMISSÃO

### 12.1 Consulta de situação (consSitNFe)

```
GET /api/app/pedidos/{id}/situacao
    │
    └─ PedidoOperacaoService.consultarSituacao(id)
```

Requer: pedido com `chaveNfe` preenchida (estado diferente de `RASCUNHO`). Retorna HTTP 422 caso contrário.

**Estrutura da resposta** (`data` do envelope `Result<Object>`):

```json
{
  "pedidoId":      42,
  "numero":        "PED-00000042",
  "status":        "AUTORIZADO",
  "chaveNfe":      "35260512000000000000550010000000421000000424",

  "cStat":         "100",
  "xMotivo":       "Autorizado o uso da NF-e",
  "nProt":         "135260512345678",
  "dhRecbto":      "2026-05-12T10:10:00",

  "consultaSefaz": "<retConsSitNFe>...</retConsSitNFe>"
}
```

| Campo                                       | Presença     | Origem                                           |
|---------------------------------------------|--------------|--------------------------------------------------|
| `pedidoId`, `numero`, `status`, `chaveNfe`  | Sempre       | Banco de dados local (`pedidos`)                 |
| `cStat`, `xMotivo`, `nProt`, `dhRecbto`     | Condicional  | Tabela `nfe_documento` (se existir para a chave) |
| `consultaSefaz`                             | Sempre       | Chamada live `consSitNFe` à SEFAZ em tempo real  |

### 12.2 Cancelamento (evento 110111)

```
POST /api/app/pedidos/{id}/cancelar
Body: { "justificativa": "mínimo 15 caracteres" }
```

Condições verificadas por `PedidoOperacaoService.cancelar()`:
1. `status == "AUTORIZADO"` — lança `IllegalStateException` (HTTP 422) para qualquer outro status
2. `nfe_documento` existe para a chave e tem `nProt` preenchido — lança `IllegalStateException` (HTTP 422) se ausente

O serviço monta o XML do evento de cancelamento, assina com `AssinaturaXmlService.assinarEvento()`, transmite para o endpoint SEFAZ e atualiza o status do pedido para `"CANCELADO"`.

Resposta: XML bruto da SEFAZ em `data` do envelope `Result<String>`.

### 12.3 Carta de Correção Eletrônica (CC-e, evento 110110)

```
POST /api/app/pedidos/{id}/cce
Body: { "correcao": "mínimo 15 caracteres" }
```

Condição: `status == "AUTORIZADO"` — lança `IllegalStateException` (HTTP 422) caso contrário. A CC-e não altera o status do pedido. Limite SEFAZ: 20 CC-e por chave NF-e.

Resposta: XML bruto da SEFAZ em `data` do envelope `Result<String>`.

### 12.4 DANFE — Documento Auxiliar da Nota Fiscal Eletrônica

```
GET /api/fiscal/nfe/{chave}/danfe
```

Retorna o PDF do DANFE correspondente à chave informada. Autenticação JWT obrigatória (qualquer role).

**Resposta de sucesso:**
- HTTP 200
- `Content-Type: application/pdf`
- `Content-Disposition: attachment; filename="danfe-{chave}.pdf"`
- Body: bytes do PDF

**Respostas de erro:**

| Código | Causa                                                         |
|--------|---------------------------------------------------------------|
| 400    | Chave com comprimento diferente de 44 dígitos                 |
| 401    | Token JWT ausente ou inválido                                 |
| 404    | NF-e não encontrada na tabela `nfe_documento`                 |
| 422    | XML da NF-e ainda não disponível (emissão não processada)     |
| 500    | Falha interna na geração do PDF                               |

**Arquitetura DANFE:**

| Classe                    | Módulo            | Responsabilidade                                                   |
|---------------------------|-------------------|--------------------------------------------------------------------|
| `DanfeXmlParser`          | `borurio-fiscal`  | Extrai campos do XML NF-e assinado via XPath + namespace `nfe:`    |
| `DanfePdfGenerator`       | `borurio-fiscal`  | Gera PDF A4 com barcode128, tabela de itens, watermark em HOM      |
| `DanfeService`            | `borurio-fiscal`  | Interface; carrega `NfeDocumento` e orquestra parser + generator   |
| `DanfeServiceImpl`        | `borurio-fiscal`  | Implementação; busca XML por chave, formata dhRecbto               |
| `DanfeController`         | `borurio-web`     | REST controller `GET /api/fiscal/nfe/{chave}/danfe`                |

**Fonte de dados:**
- `nfe_documento.xml_nfe` — XML assinado (sempre preenchido após transmissão)
- `nfe_documento.n_prot` — número do protocolo de autorização
- `nfe_documento.dh_recbto` — data/hora do recebimento SEFAZ
- `nfe_documento.c_stat` — código de status

**Requisito legal — watermark:**
- Quando `tpAmb=2` (homologação), o DANFE exibe marca d'água diagonal "SEM VALOR FISCAL" em cinza claro.
- Implementado via `PdfPageEventHelper.onEndPage()` (OpenPDF 1.3.30).

**Biblioteca:**
- OpenPDF 1.3.30 (LGPL) — fork do iText 5; compatível com uso comercial sem restrições AGPL.

**Correções aplicadas em 20-05-2026:**

| Bug corrigido | Solução |
|---|---|
| `static final DecimalFormat` — não thread-safe em singleton Spring | `dfMoeda()` / `dfQtde()` retornam nova instância por chamada |
| Totais formatados via `BigDecimal.toString()` — ignorava Locale pt_BR | Passados por `formatDecimal()` com `dfMoeda()` — resultado: `R$ 91,80` |
| Label de protocolo fixo mesmo para `cStat≠100` | Condicional: `PROTOCOLO DE AUTORIZAÇÃO DE USO` (cStat=100 + nProt presente); `RETORNO SEFAZ — HOMOLOGAÇÃO` (tpAmb=2, cStat≠100); `PROTOCOLO NÃO DISPONÍVEL` (outros) |

### 12.5 Manifestação do Destinatário (eventos 210200 / 210210 / 210220 / 210240)

```
POST /api/fiscal/nfe/manifestar
    │
    └─ NfeManifestacaoController.manifestar()
           └─ NfeManifestacaoServiceImpl.manifestar()
                  ├─ validar()                          → regras: 44 dígitos, tipo válido, CNPJ 14 dígitos, xJust p/ 210240
                  ├─ montarEnvEvento()                  → cOrgao=91 (AN), CNPJ destinatário, estrutura NT 2012.004
                  ├─ AssinaturaXmlService.assinarEvento() → RSA-SHA256 + C14N
                  ├─ montarSoap()                       → SOAP 1.2 NFeRecepcaoEvento4
                  ├─ enviarSoap()                       → endpoint AN (sefaz.urls.manifestacao-evento)
                  ├─ verificarERetornarResultado()       → valida cStat=128 (lote) + cStat=135/136 (evento)
                  └─ registrarLog()                     → nfe_log | tipoEvento=MANIFESTACAO_210200 | xml_retorno capturado
```

**Eventos suportados:**

| Código | Descrição | xJust |
|---|---|---|
| `210200` | Ciência da Operação | Não requerido |
| `210210` | Confirmação da Operação | Não requerido |
| `210220` | Desconhecimento da Operação | Não requerido |
| `210240` | Operação Não Realizada | **Obrigatório** · mín 15 / máx 255 chars |

**Regras arquiteturais:**

- `cOrgao` = **91** (Ambiente Nacional — obrigatório pela NT 2012.004 para todos os eventos de Manifestação)
- URL = `sefaz.urls.manifestacao-evento` (separada de `recepcao-evento` que é usada pelo CC-e)
- CNPJ no `<CNPJ>` = `cnpjDestinatario` (não o CNPJ do emitente — diferença fundamental em relação ao CC-e)
- `nSeqEvento` = `"01"` fixo (Manifestação não acumula sequências por NF-e)
- `idEvento` = `"ID"` + `tpEvento` (6) + `chNFe` (44) + `nSeqEvento` (2) = 54 caracteres

**Validação de cStat (implementada em `verificarERetornarResultado`):**

| cStat lote | cStat evento | Resultado |
|---|---|---|
| 128 | 135 ou 136 | Sucesso — retorna `"cStat - xMotivo"` |
| 128 | outro | Exceção: "SEFAZ rejeitou evento: cStat=X - Y" |
| outro (ex: 225) | — | Exceção: "SEFAZ rejeitou lote: cStat=X - Y" |

**Limitação HOM — endpoint AN:**

O endpoint AN HOM (`hom.nfe.fazenda.gov.br`) retorna HTTP 403 para requests de IPs residenciais/locais. Trata-se de controle de acesso da SEFAZ federal, não de erro no código. A implementação está arquiteturalmente correta conforme NT 2012.004.

---

## 13. LIMITAÇÕES CONHECIDAS DO AMBIENTE HOM/SP

> **IMPORTANTE:** esta seção descreve limitações do ambiente de homologação da SEFAZ SP, **não** do código. O código foi validado contra XSD oficial e está em conformidade com a NT 2019.001.

### 13.1 cStat=225 — "Rejeição: Falha no Schema XML do lote de NFe"

> **Correção v1.9 (10-07-2026):** esta seção descrevia cStat=225 como exclusivamente uma limitação de ambiente. Uma segunda causa raiz, distinta e real, foi identificada em homologação com um cliente OMS no mesmo dia — ver "Causa 2" abaixo. **`xMotivo` é sempre a fonte confiável do motivo** — não assumir automaticamente que é limitação de ambiente sem inspecionar `data.xMotivo`/`data.cStat` (expostos desde v1.9 via `errorCode: SEFAZ_REJECTED`, ver contrato de integração seção 8.2a).

**Causa 1 — divergência de processador SEFAZ SP (diagnóstico original, 08-05-2026):**

```
retEnviNFe.cStat   = 104   (lote aceito pelo processador PL009)
protNFe.cStat      = 225   (NF-e rejeitada pelo processador PL_008i2)
xMotivo            = "Rejeição: Falha no Schema XML do lote de NFe"
verAplic (lote)    = SP_NFE_PL009_V4
verAplic (infProt) = SP_NFE_PL_008i2
```

A SEFAZ SP usa dois processadores distintos: o `PL009` valida o lote, e o `PL_008i2` (versão mais antiga) valida cada NF-e individualmente. A hipótese mais provável é que o `PL_008i2` use internamente o schema `xmldsig-core-schema_v1.01.xsd` com `fixed="rsa-sha1"`, enquanto o código usa RSA-SHA256 (obrigatório pela NT 2019.001). O xmldsig foi corrigido para W3C puro em 08-05-2026, mas o `verAplic PL_008i2` continua aparecendo esporadicamente em HOM-SP.

**Causa 2 — cadastro do emitente incompleto (achado em homologação, 10-07-2026):**

Mesmo `xMotivo` ("Rejeição: Falha no Schema XML do lote de NFe") e mesmo `cStat=225`, mas causa completamente diferente: a empresa emitente (auto-criada via autorização OMS, que só recebe CNPJ/razão social/UF do certificado A1 — ver seção sobre multi-CNPJ) estava sem endereço (`logradouro`/`numero`/`bairro`/`codigoMunicipio`/`municipio`/`cep` ausentes), gerando um `enderEmit` incompleto no XML. Confirmado comparando o XML de envio real: o bloco `enderDest` (destinatário) estava completo, mas o `enderEmit` (emitente) só tinha `UF`/`cPais`/`xPais`.

**Correção:** endereço completado via `PUT /api/app/empresas/{id}` (ou automaticamente desde v1.9, via campos `emit*` em `POST /api/app/pedidos` — ver seção 4). Além disso, `NfeGeracaoService.validarEnderecoEmitente()` (v1.9) agora intercepta esse caso ANTES de montar o XML e chamar a SEFAZ, retornando `EMITTER_ADDRESS_INCOMPLETE` (422) em vez de deixar a SEFAZ rejeitar por schema.

**Status de investigação:** Causa 1 permanece como limitação de ambiente conhecida (sem ação corretiva possível sem violar a NT 2019.001). Causa 2 foi um bug de dado real, já corrigido — não deve mais ocorrer para empresas com cadastro completo, e agora é bloqueado preventivamente pelo `EMITTER_ADDRESS_INCOMPLETE`.

**Impacto em HOM/PRD (correção v1.9):** desde a v1.9, qualquer `cStat≥200` (incluindo 225) resulta em `REJEITADO` e `POST /emitir` retorna HTTP 422 `SEFAZ_REJECTED` com `data.cStat`/`data.xMotivo` — **não mais HTTP 200 com pedido em `AGUARDANDO`** (a afirmação anterior desta seção, de que o pedido ficava em `AGUARDANDO`, estava incorreta: cStat≥200 sempre resultou em `REJEITADO`, nunca em `AGUARDANDO`, mesmo antes da v1.9). Para validar o fluxo técnico, inspecionar `data.cStat`/`data.xMotivo` (quando `SEFAZ_REJECTED`) ou `data.soapRetorno` (quando HTTP 200) e verificar se a chave de acesso foi gerada (44 dígitos).

### 13.2 Invalidação de cache de certificado

O cache `EmpresaCertificadoService` é invalidado automaticamente pelo `EmpresaController.atualizar()` quando os dados da empresa são alterados via `PUT /api/app/empresas/{id}`. Ver seção 10.4.

---

## 14. CHECKLISTS OPERACIONAIS

### 14.1 Checklist de deploy HOM

```
□ 1. Build: mvn package -DskipTests -q
       └─ Verificar: BUILD SUCCESS

□ 2. Copy JAR para o container correto:
       docker cp borurio-web/target/borurio-web-1.0.0.jar \
                 borurio-web-hom:/app/app.jar
       └─ Atenção: o JAR interno é /app/app.jar (não /app/borurio-web.jar)

□ 3. Restart:
       docker restart borurio-web-hom

□ 4. Aguardar healthcheck:
       docker inspect borurio-web-hom --format "{{.State.Health.Status}}"
       └─ Aguardar: healthy

□ 5. Verificar Flyway nos logs:
       docker logs borurio-web-hom --since "..." 2>&1 | grep -i "migrat\|flyway"
       └─ Verificar: "Successfully applied N migration(s)" ou "is up to date"

□ 6. Smoke test login:
       POST http://localhost:8081/auth/login
       {"username":"admin@empresa.com","password":"admin123"}
       └─ Verificar: HTTP 200, token presente

□ 7. Smoke test actuator:
       GET http://localhost:8081/actuator/health
       └─ Verificar: {"status":"UP"}

□ 8. Smoke test API:
       GET http://localhost:8081/api/test/ping
       └─ Verificar: HTTP 200, status="UP"
```

### 14.2 Checklist de homologação NF-e

```
□ 1. Empresa configurada com CNPJ, razão social, UF, IE, CRT
□ 2. Certificado A1 PKCS12 disponível em /app/certificados/pfx/
□ 3. empresa.cert_path apontando para o arquivo do certificado
□ 4. sefaz.tpAmb=2 no application.properties (homologação)
□ 5. Produto cadastrado com NCM (8 dígitos), CFOP, origem, csosn
□ 6. Pedido criado com destCnpjCpf/destRazaoSocial válidos
□ 7. POST /{id}/emitir → HTTP 200, data.soapRetorno não vazio
□ 8. Verificar chaveNfe: 44 dígitos (confirma que SEFAZ aceitou o lote)
□ 9. Em HOM-SP: cStat=225 pode ocorrer — verificar `xMotivo` antes de assumir limitação de ambiente (v1.9: retorna HTTP 422 `SEFAZ_REJECTED`, não mais HTTP 200)
□ 10. Verificar nfe_documento no banco: c_stat, x_motivo, n_prot
□ 11. Verificar nfe_log: empresa_id, usuario preenchidos
```

### 14.3 Checklist de validação de nova empresa

```
□ 1. POST /api/app/empresas (requer role ADMIN)
       Body: { cnpj, razaoSocial, uf, ie, crt, logradouro, ... }
□ 2. Verificar empresa criada: GET /api/app/empresas/{id}
□ 3. Configurar certificado: PUT /api/app/empresas/{id}
       Body: { certPath: "/app/certificados/pfx/empresa-X.pfx",
               certSenha: "senha_plaintext",
               certTipo: "PKCS12" }
□ 4. Testar emissão com usuário cuja empresa_id == id da nova empresa
□ 5. Verificar log: [EmpresaCert] Certificado OK | empresaId=X | alias=...
```

### 14.4 Smoke test de integração end-to-end

Executar com Postman collection (`docs/postman/borurio-erp-collection.json`) ou sequência curl em HOM (`http://localhost:8081`). Ver `INTEGRATION_CONTRACT_PT-BR.md` para payloads completos.

```
□ 1. GET  /api/test/ping                     → HTTP 200, status="UP"
□ 2. POST /auth/login                        → HTTP 200, token não nulo
□ 3. POST /api/app/produtos                  → HTTP 200, data.id retornado
□ 4. GET  /api/app/produtos?page=0&size=5    → HTTP 200, totalElements ≥ 1
□ 5. POST /api/app/pedidos                   → HTTP 200, data.status="RASCUNHO"
□ 6. POST /api/app/pedidos/{id}/emitir       → HTTP 200, soapRetorno não vazio
□ 7. GET  /api/app/pedidos/{id}/situacao     → HTTP 200, chaveNfe preenchida
□ 8. GET  /api/app/pedidos/{id}              → HTTP 200, itens com snapshot

Verificações de segurança:
□ 9.  GET  /api/app/pedidos sem token        → HTTP 401, success=false
□ 10. GET  /api/app/usuarios com token OPERADOR  → HTTP 403, success=false
□ 11. GET  /api/app/pedidos token empresa B  → HTTP 200, content=[] (isolamento)
```

---

## 15. DECISÕES DE ARQUITETURA

### DA-01: borurio-fiscal não importa borurio-app

**Decisão:** o módulo fiscal não tem dependência de compilação no módulo de negócio.

**Motivação:** o motor fiscal deve ser reutilizável e testável independentemente do modelo de dados de negócio. Certificado e credenciais são passados via `CertificadoContexto` (tipos JDK puros).

**Consequência:** toda integração entre os dois domínios ocorre no módulo `borurio-web`, que é o único ponto de acoplamento.

---

### DA-02: Snapshot fiscal imutável no pedido_item

**Decisão:** ao criar o pedido, os dados fiscais do produto (NCM, CFOP, CSOSN, origem, unidade) são copiados para `pedido_item` e nunca mais atualizados.

**Motivação:** legislação fiscal exige que a NF-e reflita os dados no momento da venda. Alterações posteriores no cadastro do produto não podem retroativamente modificar pedidos já criados.

**Consequência:** a emissão sempre usa dados do `pedido_item`. O serviço não re-busca o produto no momento da emissão.

---

### DA-03: Dupla camada de log em nfe_log

**Decisão:** dois registros distintos são criados por emissão (`ENVIO_NFE` + `TRANSMISSAO_SEFAZ`).

**Motivação:** `NfeTransmitServiceImpl` (fiscal) tem acesso ao `SecurityContextHolder` (Spring Security) mas não tem acesso à `Empresa`. `NfeGeracaoService` (web) tem acesso à `Empresa` mas não está na mesma chamada que a transmissão. A duplicação é a consequência direta de DA-01.

---

### DA-04: ConcurrentHashMap para cache de certificados (sem TTL)

**Decisão:** cache em memória sem TTL. Invalidação explícita via `EmpresaCertificadoService.invalidar(empresaId)`.

**Motivação:** certificados A1 têm validade de 1 a 3 anos. Recarregar o KeyStore a cada emissão tem custo criptográfico desnecessário.

**Implementado:** `EmpresaController.atualizar()` chama `empresaCertificadoService.invalidar(empresaId)` após cada `PUT /api/app/empresas/{id}`.

---

### DA-05: SOAP direto sem CXF/wsimport

**Decisão:** o envelope SOAP é construído manualmente como string, sem cliente WSDL gerado.

**Motivação:** a SEFAZ bloqueia requisições automatizadas ao WSDL (HTTP 403 / HTTP/2). Um WSDL local gerado em tempo de build seria necessário, mas o envelope da NF-e é estável e bem documentado pela SEFAZ.

**Consequência:** mudanças no protocolo SOAP da SEFAZ exigem atualização manual do `NfeTransmitServiceImpl`.

---

### DA-06: Passthrough do CertSenhaEncryptor sem chave configurada

**Decisão:** sem `CERT_ENCRYPTION_KEY`, o encryptor opera em modo transparente (sem criptografia) com `WARN` no log.

**Motivação:** permite que o sistema rode em desenvolvimento local sem infraestrutura de gestão de segredos. A ativação da criptografia é um requisito de produção (Fase 11).

---

### DA-07: @ConditionalOnProperty em MyBatisConfig

**Decisão:** `MyBatisConfig` usa `@ConditionalOnProperty("spring.datasource.url")` em vez de `@ConditionalOnBean(DataSource.class)`.

**Motivação:** `@ConditionalOnBean` em uma classe `@Configuration` regular é avaliado antes das auto-configurações do Spring Boot (incluindo `DataSourceAutoConfiguration`), resultando em condição sempre falsa em produção — `@MapperScan` nunca é executado e os mappers MyBatis não são registrados. `@ConditionalOnProperty` avalia a propriedade, que está presente nos YAMLs de dev/hom e ausente nos contextos de teste `@WebMvcTest`.

---

### DA-08: Separação de empresaId para estoque vs. empresa para NF-e no fluxo multi-CNPJ

**Decisão:** em `PedidoEmissaoService.emitir()`, o `empresaId` usado para operações de estoque é derivado de `pedido.getEmpresaId()` (empresa-âncora registrada no pedido no momento da criação), não de `empresa.getId()` onde `empresa` é resolvida a partir do `cnpjEmitente`.

**Motivação:** no modelo multi-CNPJ, o catálogo de produtos pertence ao cliente OMS como um todo — não a cada CNPJ individualmente. Os produtos são cadastrados via batch com o token OMS cujo `eid` aponta para a empresa-âncora. Se o estoque fosse operado pela empresa fiscal do CNPJ emitente (que pode ter `id` diferente da âncora), a busca `buscarPorIdEEmpresa(produtoId, empresaFiscal.getId())` retornaria `null` porque o produto existe sob `empresa_id = âncora.getId()`.

**Consequência:** a variável `empresa` em `PedidoEmissaoService` tem duas responsabilidades distintas: (1) dados do emitente no XML NF-e e (2) seleção do certificado OMS por CNPJ — ambas corretas pela empresa fiscal. Apenas o `empresaId` para estoque é desacoplado e fixado na empresa-âncora.

**Implementado:** commit `117a447` em 30-06-2026 — 126/126 testes passando.

---

## 16. ROADMAP ATÉ PRODUÇÃO

### Fase 10 — Documentação e Swagger ✓ CONCLUÍDA (12-05-2026)

| Item                                          | Status                                                  |
|-----------------------------------------------|---------------------------------------------------------|
| Manual técnico PT                             | ✓ Este documento (v2.4)                                 |
| Manual técnico EN                             | ✓ `MTF-001_motor-fiscal-nfe_EN.md`                      |
| Contrato de integração PT-BR                  | ✓ `INTEGRATION_CONTRACT_PT-BR.md`                       |
| Contrato de integração EN                     | ✓ `INTEGRATION_CONTRACT_EN.md`                          |
| Swagger anotado (10 tags, deprecated marcado) | ✓ `SwaggerConfig.java` — Sprint 3                       |
| Postman collection end-to-end (49 requests)   | ✓ `docs/postman/borurio-erp-collection.json` — Sprint 3 |

### Fase 12-B — DANFE ✓ CONCLUÍDA (18-05-2026)

| Item                                                            | Status                                        |
|-----------------------------------------------------------------|-----------------------------------------------|
| `DanfeXmlParser` — XPath + namespace `nfe:` sobre XML assinado | ✓ `borurio-fiscal/danfe/` — 18-05-2026        |
| `DanfePdfGenerator` — OpenPDF 1.3.30, barcode128, watermark    | ✓ `borurio-fiscal/danfe/` — 18-05-2026        |
| `DanfeService` + `DanfeServiceImpl`                             | ✓ `borurio-fiscal/danfe/` — 18-05-2026        |
| `DanfeController` `GET /api/fiscal/nfe/{chave}/danfe`           | ✓ `borurio-web/controller/fiscal/` — 18-05-2026 |
| `DanfeControllerTest` — 5 cenários cobertos                     | ✓ 66/66 testes passando — 18-05-2026          |

### Fase 11 — Deploy PRD + CI/CD (pendente)

| Item                                        | Prioridade   | Descrição                                                             |
|---------------------------------------------|--------------|-----------------------------------------------------------------------|
| `CERT_ENCRYPTION_KEY` em PRD                | **CRÍTICO**  | Gerar via `openssl rand -base64 32`; injetar via secrets manager      |
| Certificados A1 PRD com CNPJ real           | **CRÍTICO**  | `tpAmb=1`; registrar empresa com `cert_path` apontando para cert PRD  |
| CI/CD pipeline                              | **MÉDIO**    | GitHub Actions: test → build → push image → deploy HOM → smoke test   |
| Monitoramento                               | **BAIXO**    | Prometheus + Loki                                                     |
| ~~Invalidação automática de cache~~         | ~~ALTO~~     | ✓ Implementado — `EmpresaController.atualizar()` chama `invalidar()` |
| ~~Rate limiting~~                           | ~~MÉDIO~~    | ✓ Implementado — `RateLimitInterceptor` (10 req/min login, 30 emitir)|
| ~~Política de retenção `nfe_log`~~          | ~~BAIXO~~    | ✓ Implementado — `NfeLogRetencaoScheduler` + `@EnableScheduling`     |

---

## 17. REFERÊNCIAS NORMATIVAS

| Documento                                  | Descrição                                                         |
|--------------------------------------------|-------------------------------------------------------------------|
| AJUSTE SINIEF 07/2005 e alterações         | Institui a Nota Fiscal Eletrônica                                 |
| Manual de Orientação do Contribuinte (MOC) | Versão 7.0 — layout NF-e 4.00                                     |
| Nota Técnica 2019.001                      | Atualização do layout NF-e 4.00 / Algoritmos SHA-256 obrigatórios |
| ABNT NBR ISO/IEC 27001                     | Gestão de segurança da informação                                 |
| XML-DSig W3C Recommendation                | `https://www.w3.org/TR/xmldsig-core/`                             |
| RFC 5652                                   | Cryptographic Message Syntax (base do PKCS#12)                    |

---

*Documento MTF-001 — versão 2.8 — Borurio ERP Fiscal BR*  
*Gerado com base no estado validado em HOM em 11-05-2026*  
*Última atualização: 10-07-2026 (estoque opcional por empresa; reemissão REJEITADO/ERRO; endereço do emitente via pedido; errorCode/retryable padronizados; seção 13.1 corrigida com causa 2 do cStat=225; 190/190 testes)*  
*Próxima revisão prevista: após deploy PRD (Fase 11)*
