# MTF-001 — Manual Técnico: Motor Fiscal NF-e 4.00
## Borurio ERP Fiscal BR

---

**Documento:** MTF-001
**Versão:** 3.1
**Data de emissão:** 11-05-2026
**Data da revisão documental:** 22-07-2026
**Autor:** Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
**Status:** REFERÊNCIA TÉCNICA DO MOTOR FISCAL — HOM
**Branch de referência:** `fix/sefaz-xml-structure`

> **Ressalva de estado (22-07-2026):** com o release do commit `4a39a88` ativo em HOM (V032 aplicada), as funcionalidades que aguardavam validação integrada — proteção contra emissão concorrente, contexto fiscal multi-CNPJ nos eventos pós-emissão, baseline seguro de numeração, `indFinal`/`indIntermed`, revogação/rotação de token OMS e a modalidade de frete por fluxo (`modFrete`) — foram validadas em HOM real, incluindo uma nova autorização SEFAZ (`cStat=100`) com `modFrete=2` confirmado no XML efetivamente transmitido. O ambiente HOM permanece disponível para os testes que a OMS chinesa (CC) considerar necessários; o planejamento de go-live só se inicia após a validação final do CC. PRD não está validada e o sistema não está em produção.

> **Histórico de versões:**
> - v1.0 (11-05-2026): documento inicial, fases 1–9 + fase 10 em elaboração
> - v2.0 (12-05-2026): sprint 3 concluído; RBAC atualizado; `/situacao` corrigido; Fase 10 encerrada; endpoints de integração e smoke test adicionados
> - v2.1 (15-05-2026): correções de revisão técnica; SecureRandom para cNF; rate limiting implementado; scheduler de retenção de logs implementado; gap de cache certificado corrigido no código; contrato de integração atualizado com referência de mensagens
> - v2.2 (18-05-2026): Fase 12-A — estoque mínimo fiscal implementado; reserva atômica antes da SEFAZ; baixa definitiva em AUTORIZADO; estorno em CANCELADO; tabela `estoque_movimento`; V023–V024 aplicados
> - v2.3 (18-05-2026): DANFE implementado — `DanfeXmlParser`, `DanfePdfGenerator`, `DanfeService`, `GET /api/fiscal/nfe/{chave}/danfe`; OpenPDF 1.3.30; watermark "SEM VALOR FISCAL" em HOM; V023–V024 aplicados em HOM
> - v2.4 (21-05-2026): 3 bugs corrigidos em `DanfePdfGenerator` — formatação monetária pt_BR nos totais, `DecimalFormat` thread-safe por chamada, label de protocolo condicional
> - v2.5 (26-05-2026): Manifestação do Destinatário implementada (210200/210210/210220/210240); cOrgao=91 (AN); validação de cStat na resposta SEFAZ; xml_retorno capturado em erro; contrato de integração v1.3
> - v2.6 (22-06-2026): V028 Multi-CNPJ OMS — `POST /api/integration/fiscal-authorizations`; token por cliente OMS (`codigoEmpresaOms`); múltiplos CNPJs sob o mesmo token; auto-criação de empresa a partir do Subject X.509; token determinístico via `emitidoEm` truncado a segundos; validação `cnpjEmitente` OMS em `POST /pedidos` (fail-fast HTTP 403); contrato v1.7
> - v2.7 (30-06-2026): fix `PRODUCT_NOT_FOUND` no fluxo multi-CNPJ — separação entre `empresaId` de estoque (empresa-âncora do pedido) e `empresa` para NF-e (empresa fiscal do CNPJ emitente); DA-08 documentado
> - v2.8 (10-07-2026): estoque opcional por empresa (`controleEstoqueAtivo`); diagnóstico de rejeição por cadastro de emitente incompleto e correção; reemissão de pedidos `REJEITADO`/`ERRO` no mesmo `pedidoId`; endereço do emitente opcional em `POST /api/app/pedidos` completando o cadastro automaticamente; `errorCode`/`retryable` padronizados; `SEFAZ_REJECTED` não retorna mais HTTP 200; contrato de integração v1.9
> - v2.9 (14-07-2026): causa raiz real do `cStat=225` identificada e corrigida — o motor assinava o XML com algoritmo divergente do exigido pelo schema oficial da SEFAZ; XSD local realinhado ao pacote oficial vigente; `AssinaturaXmlService` corrigido para RSA-SHA1/SHA-1. Grupo `indIntermed` adicionado a `<ide>`; ajuste provisório de `indFinal` (à época, inferido do tipo de documento do destinatário — já substituído, ver seção 5.7); IE do emitente corrigida no cadastro de uma das empresas de teste; exigências de payload do lado OMS (CFOP por UF, endereço completo do destinatário, texto padrão de homologação). Resultado: `cStat=100` obtido em HOM/SP pela primeira vez no projeto, em teste interno e em teste cruzado do integrador via OMS, confirmado no portal público da SEFAZ. Seção 13 reescrita com o histórico completo da investigação.
> - v3.0 (16-07-2026): consolidação documental — `indFinal` atualizado para o modelo configurável por empresa (heurística por CPF/CNPJ removida); nova subseção técnica para `indIntermed`; documentadas a proteção contra emissão concorrente, o baseline seguro de numeração e o contexto fiscal multi-CNPJ nos eventos pós-emissão; migrations V029/V030 adicionadas; nova seção sobre a distinção entre certificados de HOM e de PRD; nova seção de gap regulatório da Reforma Tributária do Consumo (IBS/CBS/IS); identidade de empresas e pessoas reais neutralizada em todo o documento; consolidação de bloqueadores de PRD (segurança e contingência).
> - v3.1 (22-07-2026): revogação e rotação global de autorização/token OMS implementada (JwtFilter valida a autorização ativa a cada requisição, endpoints `/api/admin/oms-authorizations/{id}/revogar` e `/rotacionar`, auditoria append-only, controle otimista por versão, `Idempotency-Key` obrigatória, JWT nunca persistido — seção 11.8) — V032; modalidade de frete (`modFrete`) passa a ser explícita por fluxo de emissão (enum `ModalidadeFrete`, seção 5.8) — fluxo OMS/marketplace declara `CONTA_TERCEIROS` (código 2), endpoint legado preserva `SEM_OCORRENCIA_TRANSPORTE` (código 9); todas as funcionalidades que aguardavam validação integrada em HOM (seção 1.1) foram validadas, incluindo nova autorização SEFAZ real com `cStat=100` e `modFrete=2` confirmado no XML transmitido.

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
13. [Histórico de Investigação cStat=225 e Status da Homologação SEFAZ-SP](#13-histórico-de-investigação-cstat225-e-status-da-homologação-sefaz-sp)
14. [Checklists Operacionais](#14-checklists-operacionais)
15. [Decisões de Arquitetura](#15-decisões-de-arquitetura)
16. [Roadmap até Produção](#16-roadmap-até-produção)
17. [Reforma Tributária do Consumo — IBS, CBS e IS](#17-reforma-tributária-do-consumo--ibs-cbs-e-is)
18. [Referências Normativas](#18-referências-normativas)

---

## 1. ESCOPO E OBJETIVO

Este manual descreve a arquitetura técnica, os fluxos de processamento e os procedimentos operacionais do **Motor Fiscal NF-e 4.00** implementado no sistema Borurio ERP Fiscal BR.

**Posicionamento do Borurio:** o Borurio é a camada fiscal brasileira integrada ao ERP/OMS logístico externo. É responsável pela validação fiscal, geração da NF-e, assinatura digital, transmissão à SEFAZ, persistência, eventos pós-emissão e auditoria. O Borurio **não é responsável** pela lógica comercial, logística ou operacional do OMS, e não deve ser tratado como um ERP/WMS completo nesta versão — seu escopo é estritamente a camada fiscal do fluxo pedido → NF-e → SEFAZ → eventos.

O documento destina-se a:

- **Equipe técnica interna** — manutenção, evolução e debugging do motor
- **Time de integração parceiro** — integração do motor fiscal com o ERP logístico externo (ver também `INTEGRATION_CONTRACT_PT-BR.md`)
- **Operações / DevOps** — deploy, monitoramento e procedimentos de homologação
- **Auditores técnicos** — rastreabilidade das decisões de design e conformidade

### 1.1 O que está FECHADO (validado em HOM)

| Funcionalidade                                                               | Validação                                                     |
|------------------------------------------------------------------------------|-----------------------------------------------------------------|
| Emissão NF-e 4.00 via SOAP HTTPS                                             | ✓ HOM/SP — 11-05-2026                                         |
| Assinatura XMLDSIG RSA-SHA1 + C14N (conforme schema oficial vigente)         | ✓ HOM/SP — corrigido 14-07-2026                               |
| `cStat=100` — Autorizado o uso da NF-e (empresa emitente habilitada em HOM)  | ✓ HOM/SP — 14-07-2026 (interno + integrador OMS)              |
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
| Postman collection end-to-end                                                | ✓ Gerada e alinhada — 22-05-2026                              |
| `NfeEnvioController` deprecado — endpoints legados marcados e redirecionados | ✓ Código — 12-05-2026                                         |
| Contratos de integração PT-BR e EN gerados e validados                       | ✓ Código — 12-05-2026                                         |
| `MyBatisConfig`: `@ConditionalOnProperty` garante boot correto em HOM        | ✓ HOM/SP — 12-05-2026                                         |
| Rate limiting: `/auth/login` (10 req/min); `/emitir` (default 30 req/min — HOM ajustado para 300 desde 30-07-2026, ver seção 16) | ✓ Código — 15-05-2026                                         |
| Agendamento de retenção de `nfe_log` (`NfeLogRetencaoScheduler`)             | ✓ Código — 15-05-2026                                         |
| CORS restrito — `*` substituído por origins explícitas por ambiente          | ✓ Código — 15-05-2026                                         |
| `SecureRandom` para geração de `cNF` (substituiu `new Random()`)             | ✓ Código — 15-05-2026                                         |
| Estoque mínimo fiscal — reserva, baixa definitiva, desfazer reserva, estorno | ✓ Código — 18-05-2026                                         |
| `estoque_movimento` — auditoria atômica de todos os movimentos de estoque    | ✓ Código — 18-05-2026                                         |
| `estoque_reservado` em `produto` — saldo disponível = total − reservado      | ✓ Código — 18-05-2026                                         |
| `GET /api/app/produtos/{id}/estoque` — consulta de saldo em tempo real       | ✓ Código — 18-05-2026                                         |
| `POST /api/app/produtos/{id}/estoque/entrada` — entrada manual [ADMIN]       | ✓ Código — 18-05-2026                                         |
| DANFE — geração de PDF (`DanfePdfGenerator`, OpenPDF 1.3.30)                 | ✓ Código — 18-05-2026                                         |
| `GET /api/fiscal/nfe/{chave}/danfe` — endpoint REST para download do DANFE  | ✓ Código — 18-05-2026                                         |
| Watermark "SEM VALOR FISCAL" automática em DANFE quando `tpAmb=2`           | ✓ Código — 18-05-2026                                         |
| V023–V024 aplicados em HOM (Flyway at v024)                                  | ✓ HOM/SP — 18-05-2026                                         |
| `DanfePdfGenerator` thread-safe — `DecimalFormat` recriado por chamada (substituiu `static final`) | ✓ Código — 20-05-2026                              |
| DANFE — formatação monetária pt_BR nos totais (`R$ 91,80` com vírgula decimal)                    | ✓ Código + HOM — 20-05-2026                                   |
| DANFE — label de protocolo condicional (`RETORNO SEFAZ — HOMOLOGAÇÃO` quando `cStat≠100`)         | ✓ Código + HOM — 20-05-2026                                   |
| Manifestação do Destinatário (eventos 210200/210210/210220/210240) — `POST /api/fiscal/nfe/manifestar` | ✓ Código — 26-05-2026                               |
| Validação de cStat na resposta SEFAZ — rejeição correta quando cStat≠128/135                       | ✓ Código — 26-05-2026                                         |
| xml_retorno persistido em `nfe_log` mesmo em caso de erro de transmissão                           | ✓ Código — 26-05-2026                                         |
| Autorização Fiscal OMS — `POST /api/integration/fiscal-authorizations`                              | ✓ HOM — 22-06-2026                                            |
| Multi-CNPJ OMS — múltiplos CNPJs sob o mesmo token (`codigoEmpresaOms`)                            | ✓ HOM — 22-06-2026                                            |
| Auto-criação de empresa a partir do Subject X.509 do certificado                                    | ✓ HOM — 22-06-2026                                            |
| Token determinístico — `emitidoEm` truncado a segundos; token idêntico em cenários B/C/D           | ✓ HOM — 22-06-2026                                            |
| Validação `cnpjEmitente` OMS em `POST /pedidos` — fail-fast HTTP 403 antes de persistir            | ✓ HOM — 22-06-2026                                            |
| `OmsCertificadoService.resolverPorJtiECnpj` — seleciona cert pelo CNPJ na emissão                  | ✓ HOM — 22-06-2026                                            |
| Smoke test multi-CNPJ M1–M4/M6 — aprovados em HOM                                                  | ✓ HOM — 22-06-2026                                            |
| V025–V028 aplicados em HOM (Flyway em v028)                                                        | ✓ HOM — 22-06-2026                                            |
| Controle de estoque opcional por empresa (`controleEstoqueAtivo`)                                  | ✓ HOM — 10-07-2026                                            |
| Reemissão de pedidos `REJEITADO`/`ERRO` no mesmo `pedidoId`                                        | ✓ Código — 10-07-2026                                         |
| Endereço do emitente opcional em `POST /api/app/pedidos` — completa cadastro incompleto automaticamente | ✓ Código — 10-07-2026                                    |
| `errorCode`/`retryable` padronizados — `EMITTER_ADDRESS_INCOMPLETE`, `SEFAZ_REJECTED`, `SEFAZ_TIMEOUT`, `SEFAZ_UNAVAILABLE`, `XML_SCHEMA_INVALID` | ✓ Código — 10-07-2026                    |
| `POST /emitir` não retorna mais HTTP 200 quando a SEFAZ rejeita a NF-e                             | ✓ Código — 10-07-2026                                         |
| Proteção contra emissão concorrente duplicada por pedido (ver seção 4.4)                            | ✓ HOM/SP — 22-07-2026 |
| Baseline seguro de numeração por CNPJ e série (ver seção 5.6)                                       | ✓ HOM/SP — 22-07-2026 |
| `indFinal` configurável por empresa emitente (ver seção 5.7)                                        | ✓ HOM/SP — 22-07-2026 |
| Suporte estrutural a `indIntermed` no fluxo atual (ver seção 5.7)                                   | ✓ HOM/SP — 22-07-2026 |
| Contexto fiscal multi-CNPJ nos eventos pós-emissão (ver seção 9.6)                                  | ✓ HOM/SP — 22-07-2026 |
| Modalidade de frete (`modFrete`) explícita por fluxo de emissão — OMS/marketplace=2 (Terceiros), legado=9 (Sem Transporte) (ver seção 5.8) | ✓ HOM/SP — 22-07-2026, confirmado no XML autorizado (`cStat=100`) |
| Revogação e rotação global de autorização/token OMS, com auditoria append-only, controle otimista por versão e idempotência (ver seção 11.5) | ✓ HOM/SP — 22-07-2026 |
| V029–V032 aplicados em HOM (Flyway em v032)                                                          | ✓ HOM/SP — 22-07-2026 |
| Segunda autorização real em HOM com `cStat=100` e `modFrete=2` confirmado no XML transmitido (empresa vinculada ao token com cadastro fiscal aceito pela SEFAZ) | ✓ HOM/SP — 22-07-2026 |

### 1.2 O que está PENDENTE

| Funcionalidade                                                        | Fase    | Observação                          |
|-------------------------------------------------------------------------|---------|-----------------------------------------|
| `CERT_ENCRYPTION_KEY` configurada em produção                         | Fase 11 | Passthrough ativo em HOM por design |
| CI/CD automatizado                                                    | Fase 11 | Deploy manual via `docker compose build` + `up --force-recreate` |
| Certificados A1 de produção com CNPJ real                             | Fase 11 | Certificado de HOM não deve ser reutilizado — ver seção 10.7 |
| Rotação do `SECURITY_JWT_SECRET` de HOM, troca de credenciais administrativas expostas, remoção do segredo hardcoded do `application-dev.yml`, gestão externa de segredos | Fase 11 | Endurecimento pré-produção — ver seção 13 |
| Estratégia de contingência SEFAZ                                      | Fase 11 | Modalidade ainda não definida — ver seção 16 |
| Secrets fora de arquivos versionados, rotação de credenciais, sanitização de logs | Fase 11 | Ver seção 13 (segurança) |
| Smoke test multi-CNPJ real dos eventos pós-emissão (P0.2) em HOM      | Fase 11 | Ver seção 9.6                       |
| Adequação da NF-e à Reforma Tributária do Consumo (IBS/CBS/IS)        | Gap regulatório | Ver seção 17 — trilha própria, não implementado nesta consolidação |

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
|------------------|--------------------------|------------------------------------------------------------------------------------------------------------------------|
| `borurio-core`   | `br.com.borurio.core`   | DTOs compartilhados, `ResultUtil`, `PageResponse`, utilitários base                                                |
| `borurio-app`    | `br.com.borurio.app`    | Entidades de negócio, MyBatis mappers, services: Empresa, Produto, Pedido, DbUser                                  |
| `borurio-fiscal` | `br.com.borurio.fiscal` | Geração XML NF-e, assinatura XMLDSIG, transmissão SOAP, sequenciador, persistência fiscal, auditoria               |
| `borurio-web`    | `br.com.borurio.web`    | Spring Boot, controllers REST, JWT, bridges (`PedidoEmissaoService`, `NfeGeracaoService`), certificado por empresa |

### 2.3 Regra de fronteira de módulo

> **`borurio-fiscal` NÃO importa `borurio-app`.**

A bridge entre os dois domínios é exclusivamente o módulo `borurio-web`. Quando o módulo fiscal precisa de dados da empresa emitente, recebe o record `CertificadoContexto` (apenas tipos JDK) em vez de receber a entidade `Empresa`.

### 2.4 Stack tecnológica

| Componente            | Versão / Tecnologia                                              |
|------------------------|----------------------------------------------------------------------|
| Linguagem             | Java 17                                                          |
| Framework             | Spring Boot 3.3.2                                                |
| Persistência          | MyBatis (annotations)                                            |
| Banco de dados        | MySQL 8.4                                                        |
| Migrations            | Flyway (V001–V032)                                               |
| Auth                  | JWT stateless (HMAC-SHA256)                                      |
| Segurança             | Spring Security 6.x                                              |
| XML Signing           | Java XML Crypto API (`javax.xml.crypto.dsig`)                    |
| SOAP                  | HTTPS direto (sem CXF, sem wsimport)                             |
| Cache de certificados | `ConcurrentHashMap` em memória                                   |
| Container             | Docker (imagem interna); porta 8081 em HOM                       |
| API docs              | springdoc-openapi 2.6.0 — Swagger UI em `/swagger-ui/index.html` |

---

## 3. MODELO DE DADOS FISCAL

### 3.1 Migrations aplicadas

| Migration   | Descrição                                                                     | Estado |
|-------------|-----------------------------------------------------------------------------------|--------|
| V001        | `cliente` (legado, não usado no fluxo fiscal principal)                       | Aplicada em HOM |
| V002        | `nfe_log` — auditoria de eventos fiscais                                      | Aplicada em HOM |
| V003        | Dados mock de referência                                                      | Aplicada em HOM |
| V008        | `db_user` — autenticação e autorização                                        | Aplicada em HOM |
| V009        | `produto`                                                                     | Aplicada em HOM |
| V011        | `nfe_sequencia` — controle de número por série/CNPJ                           | Aplicada em HOM |
| V012        | `nfe_documento` — estado fiscal de cada NF-e autorizada                       | Aplicada em HOM |
| V013        | `produto` — campos fiscais: origem, csosn, estoque                            | Aplicada em HOM |
| V014        | `pedido` + `pedido_item`                                                      | Aplicada em HOM |
| V015        | `pedido_item` — snapshot fiscal (ncm, cfop, csosn, origem, unidade)           | Aplicada em HOM |
| V016        | `empresa` — cadastro multiemitente                                            | Aplicada em HOM |
| V017        | `empresa_id` em `db_user`, `produto`, `pedido`                                | Aplicada em HOM |
| V018        | `empresa` — certificado A1 por empresa (cert_path, cert_senha, cert_tipo)     | Aplicada em HOM |
| V019        | `db_user.role` (ADMIN / OPERADOR) + `nfe_log.empresa_id`                      | Aplicada em HOM |
| V020        | `cliente.empresa_id` — isolamento multiempresa de clientes                    | Aplicada em HOM |
| V021        | `cliente.nome` e `cliente.email` nullable                                     | Aplicada em HOM |
| V022        | Foreign key constraints ausentes em `pedido_item`, `nfe_documento`, `nfe_log` | Aplicada em HOM |
| V023        | `estoque_movimento` — auditoria de reservas, baixas, estornos e entradas                              | Aplicada em HOM |
| V024        | `produto.estoque_reservado` DECIMAL(13,4) NOT NULL DEFAULT 0                                         | Aplicada em HOM |
| V025        | `oms_api_key` — chaves de API para integradores OMS (hash SHA-256, `integrator_id`)                  | Aplicada em HOM |
| V026        | `oms_fiscal_authorization` — slot de autorização por cliente OMS (`integrator_id`, `codigo_oms`, `jti`, `emitido_em`) | Aplicada em HOM |
| V027        | `oms_company_certificate` — certificado PKCS12 por `auth_id` com estrutura mono-CNPJ inicial         | Aplicada em HOM |
| V028        | Multi-CNPJ: `oms_fiscal_authorization` slot sem `empresa_id`; `oms_company_certificate` adiciona `cnpj`, `empresa_id`, coluna gerada `cnpj_ativo_unico` | Aplicada em HOM |
| V029        | `empresa.controle_estoque_ativo` TINYINT(1) NOT NULL DEFAULT 1 — controle de estoque opcional por empresa | Aplicada em HOM — confirmada 22-07-2026 |
| V030        | `empresa.ind_final_padrao` CHAR(1) NOT NULL DEFAULT `'1'` — padrão de `indFinal` por empresa emitente | Aplicada em HOM — confirmada 22-07-2026 |
| V031        | `nfe_sequencia_auditoria` — auditoria de sincronização de série/numeração fiscal                     | Aplicada em HOM — confirmada 22-07-2026 |
| V032        | `oms_fiscal_authorization_audit` (append-only) + coluna `versao` em `oms_fiscal_authorization` — revogação/rotação de autorização OMS (Gate 7H) | Aplicada em HOM — 22-07-2026, Flyway em v032, 0 falhas |

> V029–V032 seguem o mesmo padrão de migration das anteriores. Todas confirmadas aplicadas contra o MySQL real de HOM em 22-07-2026 (`flyway_schema_history`, 32 migrations, 0 falhas).

### 3.2 Tabelas fiscais principais

#### `nfe_documento`
Armazena o estado persistido de cada NF-e emitida. Fonte de verdade para consultas offline e reemissão de DANFE.

| Coluna          | Tipo          | Descrição                                                          |
|-----------------|----------------|--------------------------------------------------------------------|
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
|---------------|---------------|---------------------------------------------------------------------------|
| `chave_nfe`   | VARCHAR(44)  | Chave associada ao evento                                               |
| `tipo_evento` | VARCHAR(100) | `ENVIO_NFE` / `TRANSMISSAO_SEFAZ` / `CONSULTA` / `CANCELAMENTO` / `CCE` |
| `status`      | VARCHAR(20)  | `SUCCESS` / `ERROR` / `PENDING`                                         |
| `usuario`     | VARCHAR(100) | Usuário autenticado (`SecurityContextHolder`)                           |
| `empresa_id`  | BIGINT       | ID da empresa emitente (V019)                                           |
| `xml_envio`   | LONGTEXT     | XML transmitido                                                         |
| `xml_retorno` | LONGTEXT     | Resposta SOAP da SEFAZ                                                  |

#### `nfe_sequencia`
Garante unicidade atômica do número da NF-e por CNPJ + série. Ver seção 5.6 para o baseline seguro de inicialização.

| Coluna          | Descrição             |
|-----------------|-----------------------|
| `cnpj`          | CNPJ do emitente      |
| `serie`         | Série da NF-e         |
| `ultimo_numero` | Último número emitido |

#### `oms_api_key` (V025)
Chaves de API para integradores OMS. Autenticam o endpoint `/api/integration/fiscal-authorizations`.

| Coluna          | Tipo         | Descrição                                               |
|-----------------|---------------|-----------------------------------------------------------|
| `integrator_id` | BIGINT       | ID do integrador (agrupa clientes OMS deste integrador) |
| `hash`          | VARCHAR(64)  | SHA-256 hex da chave plaintext                          |
| `descricao`     | VARCHAR(100) | Nome descritivo da chave                                |
| `ativo`         | TINYINT(1)   | 1 = ativa; 0 = revogada                                 |

#### `oms_fiscal_authorization` (V026/V028)
Slot de autorização por cliente OMS. Um registro por `(integrator_id, codigo_oms)`.

| Coluna           | Tipo         | Descrição                                                             |
|-------------------|---------------|---------------------------------------------------------------------------|
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
|---------------------|---------------|-------------------------------------------------------------------------|
| `auth_id`          | BIGINT       | FK → `oms_fiscal_authorization.id`                                 |
| `empresa_id`       | BIGINT       | FK → `empresa.id` — empresa correspondente ao CNPJ                 |
| `cnpj`             | VARCHAR(14)  | CNPJ do certificado (14 dígitos, sem formatação)                   |
| `thumbprint`       | VARCHAR(64)  | SHA-256 hex do certificado X.509 — usado para detectar troca de cert |
| `cert_pfx_enc`     | LONGBLOB     | PFX criptografado via AES-256-GCM                                  |
| `cert_senha_enc`   | VARCHAR(512) | Senha criptografada via AES-256-GCM                                |
| `ativo`            | TINYINT(1)   | 1 = certificado ativo para este CNPJ; 0 = desativado (histórico)  |
| `cnpj_ativo_unico` | VARCHAR(14)  | Coluna gerada: `IF(ativo=1, cnpj, NULL)` — UNIQUE por `(auth_id, cnpj_ativo_unico)` |

#### `empresa` — campos fiscais de configuração (V018, V029, V030)

| Coluna                   | Tipo         | Descrição                                                          |
|---------------------------|---------------|-------------------------------------------------------------------------|
| `serie_nfe_padrao`       | VARCHAR(3)   | Série padrão para novos pedidos da empresa                        |
| `controle_estoque_ativo` | TINYINT(1)   | (V029) Se 0, `/emitir` nunca reserva, baixa ou estorna estoque para esta empresa |
| `ind_final_padrao`       | CHAR(1)      | (V030) Padrão fiscal de `indFinal` — ver seção 5.7                |

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
    │  ├─ claim atômico do pedido (ver seção 4.4)  → só uma execução concorrente prossegue
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
    │  │      └─ montarIde resolve indFinal (Empresa.indFinalPadrao) e indIntermed — ver seção 5.7
    │  ├─ nfeXmlBuilder.build(nfe)               → XML sem assinatura
    │  ├─ sequenciaService.proximoNumero()        → número atômico por série/CNPJ (ver seção 5.6)
    │  ├─ empresaCertificadoService.resolverPorEmpresa() → CertificadoContexto ou null
    │  │
    │  │  nfeOrquestradorService.processar(xml, cnpj, certCtx)
    │  │
    ▼  ▼
NfeOrquestradorService (borurio-fiscal)
    │  ├─ [1] converterParaDocument()             → parse XML com namespace-aware
    │  ├─ [2] xsdValidator.validate()             → contra xsd/custom/nfe_v4.00_consolidado.xsd — lança XmlSchemaValidationException (v1.9)
    │  ├─ [3] assinaturaXmlService.assinar()      → XMLDSIG RSA-SHA1 + C14N (conforme schema oficial)
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
    │  ├─ baixarEstoque()                          → somente se AUTORIZADO (cStat=100) e controle de estoque ativo (ver seção 11)
    │  └─ (v1.9) se REJEITADO: lança BusinessException.sefazRejected(cStat, xMotivo) — HTTP 422, não retorna 200
```

Se uma exceção for lançada durante `nfeGeracaoService.gerar()`, o serviço chama `traduzirFalhaTransmissao()` (v1.9) pra classificar a causa antes de relançar: `XmlSchemaValidationException` → `XML_SCHEMA_INVALID` (422); `SocketTimeoutException` → `SEFAZ_TIMEOUT` (503, retryable); `ConnectException`/`UnknownHostException` → `SEFAZ_UNAVAILABLE` (503, retryable); qualquer outra exceção não classificada → HTTP 500 genérico (retryable=false). Em todos os casos o pedido fica em `ERRO`, preservando a `chaveNfe` que já existia (se houver).

### 4.2 Status semântico do pedido

| Status       | Condição                                                             | Estoque baixado?   |
|--------------|-------------------------------------------------------------------------|----------------------|
| `RASCUNHO`   | Pedido criado, ainda não emitido                                     | Não                |
| `AUTORIZADO` | `cStat = 100` da SEFAZ                                               | Sim (se controle de estoque ativo) |
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

### 4.4 Proteção contra emissão concorrente

Duas chamadas simultâneas a `POST /api/app/pedidos/{id}/emitir` para o mesmo pedido podiam, antes desta proteção, passar pela mesma validação de status antes de qualquer uma atualizar o pedido — o sequenciador de numeração protegia o `nNF` contra duplicidade, mas não impedia que duas execuções concorrentes produzissem duas NF-e para o mesmo pedido comercial.

**Mecanismo:** antes de montar/transmitir o XML, `PedidoEmissaoService` executa um claim atômico do pedido — uma transação curta e condicional (`UPDATE ... WHERE status IN (...)`) que permite a apenas uma execução prosseguir. A transação do claim é curta; nenhum lock é mantido durante a chamada SOAP à SEFAZ.

**Comportamento para a chamada que perde a corrida:**

| `errorCode` | HTTP | `retryable` | Orientação |
|---|---|---|---|
| `EMISSAO_EM_ANDAMENTO` | 409 | `true` | Não criar um pedido novo. `retryable: true` aqui não significa repetição imediata: aguardar um intervalo curto, consultar `GET /api/app/pedidos/{id}/situacao` e repetir `/emitir` somente se o estado do pedido ainda permitir. |

A numeração por CNPJ+série permanece protegida como já estava (ver seção 5.6).

**Cobertura de teste:** validado por teste automatizado com 2 e 10 threads reais concorrentes. Isso não substitui um teste de concorrência contra um banco MySQL real em ambiente integrado — o teste automatizado usa a mesma instância de banco de teste do projeto, não um ambiente de HOM sob carga real.

**Riscos residuais:** resposta perdida após transmissão à SEFAZ deixa o pedido em estado incerto até reconciliação manual/consulta; número alocado não equivale a NF-e autorizada; o sistema não reutiliza automaticamente um número já consumido.

---

## 5. GERAÇÃO DO XML NF-e

### 5.1 Componentes envolvidos

| Classe                   | Módulo         | Responsabilidade                                        |
|---------------------------|-----------------|--------------------------------------------------------------|
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
    <transp>  <!-- modFrete explícito por fluxo (ver seção 5.8): 2 (Terceiros) no fluxo OMS, 9 (Sem Transporte) no legado -->
    <pag>     <!-- detPag: indPag=0, tPag=01, vPag=total -->
  </infNFe>
  <Signature>  <!-- inserido pelo AssinaturaXmlService após validação XSD -->
</NFe>
```

### 5.4 Campos fiscais por produto

Cada item do XML (`<det>`) utiliza os dados congelados no `PedidoItem`:

| Bloco NF-e           | Campo   | Origem                                                     |
|------------------------|-----------|------------------------------------------------------------------|
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

### 5.6 Numeração — baseline seguro por CNPJ e série

A numeração (`nNF`) é isolada por CNPJ emitente e série, e sua atribuição é atômica (seção 5.2). Além do sequenciador, existe uma camada de inicialização segura do contador (baseline) para empresas com histórico de emissão anterior — por exemplo, migração parcial de outro ERP.

**Regras do baseline:**
- Sequência inexistente para o par CNPJ+série → cria com o último número conhecido informado.
- Sequência existente com o mesmo valor informado → operação idempotente, nenhuma escrita ocorre.
- Sequência existente com valor diferente (maior **ou** menor) → erro explícito; não avança nem regride silenciosamente; a sequência ativa nunca é sobrescrita.

**Escopo administrativo:** a inicialização de baseline é um procedimento administrativo/de onboarding controlado, interno ao Borurio — **não é uma chamada que o OMS realiza em cada emissão**. Não existe endpoint de integração para isso nesta versão do contrato; documentar um endpoint aqui seria descrever algo que não existe no código.

**Cobertura de teste e risco residual:** os testes de concorrência do baseline usam um mapper simulado com lock em memória, não um MySQL real — um teste de integração com banco real para concorrência de inicialização é recomendado antes de expor qualquer onboarding externo baseado nessa operação.

### 5.7 `indFinal` e `indIntermed`

#### `indFinal` — indicador de consumidor final

`indFinal` é resolvido a partir do cadastro da empresa emitente (`Empresa.indFinalPadrao`), **não** a partir do documento (CPF/CNPJ) do destinatário — um CNPJ também pode ser consumidor final, então usar o formato do documento como proxy é fiscalmente inadequado. A versão anterior deste manual (v2.9) descrevia uma inferência provisória por CPF/CNPJ; esse comportamento foi substituído por completo.

| Regra | Comportamento |
|---|---|
| Domínio válido | `"0"` ou `"1"` |
| Default no banco (`empresa.ind_final_padrao`) | `"1"` (`NOT NULL DEFAULT '1'`) |
| Empresa nula, ou campo nulo/vazio | Fallback de compatibilidade — `"1"` (cobre fluxo legado e dados anteriores a esta funcionalidade) |
| Valor fora do domínio (`"0"`/`"1"`) | Falha explícita — `errorCode: IND_FINAL_PADRAO_INVALIDO`, HTTP 422, `retryable: false` — não é normalizado silenciosamente |
| Override por pedido | Não existe nesta versão — o valor vem inteiramente do cadastro da empresa |
| Contrato OMS | Sem alteração — o OMS não envia `indFinal` |
| Multi-CNPJ | Isolado corretamente — a resolução usa a mesma instância de `Empresa` já resolvida para o CNPJ emitente real da operação (a mesma usada para montar o bloco `<emit>`), sem interferência entre empresas |

#### `indIntermed` — indicador de intermediador/marketplace

`Ide` transporta o campo `indIntermed`; `NfeXmlBuilder` serializa a tag `<indIntermed>` de forma condicional — só é gravada quando o valor não é nulo/vazio (por isso o preenchimento em `NfeGeracaoService` é indispensável: sem ele, o campo simplesmente não apareceria no XML). No fluxo atual, `NfeGeracaoService` atribui o valor `"0"`, atendendo ao cenário configurado de venda direta sem intermediador.

**Status:** ✓ validado em HOM/SP — 22-07-2026.

- Não houve alteração no payload OMS — o OMS não envia `indIntermed`.
- `"0"` **não é regra universal para toda NF-e** — é o valor atual para o cenário de venda direta já configurado.
- A ausência desse campo, quando obrigatório pelo layout vigente, foi historicamente observada como rejeição `cStat=434` — "NF-e sem indicativo do intermediador" (ver seção 13).
- Um cenário de venda via marketplace/plataforma de terceiro permanece **evolução futura**, condicionada a definição de negócio, e poderá exigir: origem da venda por pedido, `indIntermed="1"`, grupo `infIntermed` (CNPJ do intermediador, identificador da operação) — nenhum desses elementos existe nesta versão. **Não confundir com `modFrete`** (seção 5.8): `indIntermed` identifica se há intermediador na venda; `modFrete` identifica quem contrata o transporte — são dois campos fiscais independentes, e o fluxo atual já usa `modFrete=2` (plataforma contrata o transporte) mantendo `indIntermed="0"` (venda direta, sem intermediador na operação fiscal).

### 5.8 `modFrete` — modalidade de frete por fluxo de emissão

Até 22-07-2026, `NfeXmlBuilder` gravava `modFrete=9` ("Sem Ocorrência de Transporte") fixo para toda emissão. O CC (Xiao Li) confirmou que, no fluxo atual integrado às plataformas de e-commerce, o transporte é contratado/operado pela própria plataforma — nem pelo emitente, nem pelo destinatário — o que tecnicamente corresponde a `modFrete=2` ("Contratação do Frete por conta de Terceiros"), não a `9` (que pressupõe ausência de transporte).

**Modelo implementado:** enum `ModalidadeFrete` (`borurio-fiscal`, pacote `domain.nfe`) com os seis valores do leiaute NF-e 4.00 (`CONTA_REMETENTE`, `CONTA_DESTINATARIO`, `CONTA_TERCEIROS`, `PROPRIO_REMETENTE`, `PROPRIO_DESTINATARIO`, `SEM_OCORRENCIA_TRANSPORTE`). `NfeXmlBuilder.build()` e `NfeGeracaoService.gerar()` passam a exigir a modalidade explicitamente — sem overload que a omita, sem valor default oculto — cada chamador declara a sua:

| Fluxo | Chamador | Modalidade declarada | `modFrete` no XML |
|---|---|---|---|
| OMS / marketplace | `PedidoEmissaoService.emitir()` | `ModalidadeFrete.CONTA_TERCEIROS` | `2` |
| Legado/administrativo (`POST /api/fiscal/nfe/gerar`, deprecated) | `NfeEnvioController` | `ModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE` | `9` (comportamento anterior preservado) |

O grupo `<transporta>` (dados do transportador) é `minOccurs="0"` no XSD oficial independentemente do valor de `modFrete` — não é necessário e não é enviado, já que o Borurio nunca recebe dados de transportadora da OMS (gestão operacional de frete é responsabilidade do ERP/OMS/WMS, fora do escopo fiscal do Borurio).

**Escopo da decisão:** vale para o fluxo atual de marketplaces. Não é generalizada automaticamente para vendas diretas, retirada local ou transporte próprio — se esses cenários surgirem, a modalidade pode precisar ser parametrizada por pedido/canal (roadmap condicional, não implementado nesta versão).

**Validado em HOM/SP em 22-07-2026:** autorização real (`cStat=100`) com `<modFrete>2</modFrete>` confirmado no XML efetivamente transmitido e autorizado pela SEFAZ (empresa vinculada ao token com cadastro fiscal aceito pela SEFAZ, fluxo multi-CNPJ).

---

## 6. ASSINATURA DIGITAL XMLDSIG

### 6.1 Algoritmos (conforme schema oficial vigente `xmldsig-core-schema_v1.01.xsd`)

> **Correção 14-07-2026:** até 13-07-2026 o código assinava com RSA-SHA256, seguindo uma leitura anterior sobre o algoritmo exigido. O `cStat=225` observado nesse período foi provocado por XML incompatível com o schema da NF-e, relacionado à estrutura e aos algoritmos declarados na assinatura XMLDSig — o schema XMLDSig oficial da SEFAZ (`xmldsig-core-schema_v1.01.xsd`, confirmado no pacote `PL_010e_v1.02` baixado diretamente de nfe.fazenda.gov.br, versão vigente publicada 10-07-2026) define os atributos `Algorithm` de `SignatureMethod` e `DigestMethod` com `fixed` — valor único aceito, sem alternativa: `rsa-sha1` e `sha1`. O XSD local usado na validação pré-envio estava divergente do oficial — sem as restrições `fixed`, aceitava qualquer algoritmo, fazendo a validação local "passar" incorretamente antes do envio à SEFAZ. Após o alinhamento do XML e da assinatura ao schema oficial adotado pelo projeto, o fluxo passou a alcançar `cStat=100` em HOM.

| Algoritmo       | URI                                                                |
|--------------------|----------------------------------------------------------------------|
| Assinatura      | `http://www.w3.org/2000/09/xmldsig#rsa-sha1` (RSA-SHA1)            |
| Digest          | `http://www.w3.org/2000/09/xmldsig#sha1` (SHA-1)                   |
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
    ├─ 5. Reference("#" + id, SHA-1, [ENVELOPED, C14N])
    ├─ 6. SignedInfo(C14N, RSA-SHA1, [reference])
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
|---------------------|--------------------------------------------------------------------------------|
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
|-----------------------|-----------------------------|-----------------|---------------------------------------------------|
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

### 9.4 Seed e backfill no startup

O `StartupListener` (`@PostConstruct`) executa na inicialização:

1. **Seed empresa padrão** — lê `fiscal.emitente.*` do `application.properties` e cria `empresa` se não existir
2. **Backfill** — atualiza `empresa_id` em `db_user`, `produto` e `pedido` onde `empresa_id IS NULL`
3. **Seed admin** — cria usuário `admin` com `role='ADMIN'` se `db_user` estiver vazio

### 9.5 Multi-CNPJ OMS

O modelo multi-CNPJ expande o multiempresa para integradores externos que possuem múltiplos CNPJs emitentes. Um cliente OMS (`codigoEmpresaOms`) pode autorizar múltiplos CNPJs sob o **mesmo token JWT**.

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

> **Bug corrigido em 30-06-2026:** `PedidoEmissaoService.emitir()` anteriormente resolvia `empresaId = empresa.getId()` onde `empresa` era obtida via `buscarPorCnpj(cnpjEmitente)`. Para um segundo CNPJ (empresa fiscal distinta), isso fazia com que `estoqueService.reservarItens()` buscasse o produto com `empresa_id` errado — mas os produtos foram cadastrados sob `empresa_id` da empresa-âncora. Resultado: `PRODUCT_NOT_FOUND`. A correção: `empresaId = pedido.getEmpresaId()` como valor primário para operações de estoque, com `empresa` (fiscal) usado exclusivamente para geração do XML e seleção do certificado.

### 9.6 Contexto fiscal multi-CNPJ nos eventos pós-emissão

A emissão já resolvia empresa e certificado corretamente pelo CNPJ do pedido (seção 9.5). Cancelamento, CC-e, consulta de situação e inutilização possuíam anteriormente caminhos baseados em configuração global (`EmitenteProperties`), independente do CNPJ real do pedido/documento — corrigido pela introdução de um contexto fiscal explícito.

**Componentes:** `FiscalContexto` (record que recusa ser construído com campo nulo) e `FiscalContextoResolver` (resolve empresa+certificado sempre por `pedido.cnpjEmitente`, nunca por configuração global).

**Comportamento:**
- Empresa, certificado e UF são resolvidos pela empresa fiscal correta em todo evento pós-emissão, pelo CNPJ real da operação.
- O CNPJ embutido na chave de acesso é validado contra o contexto fiscal resolvido antes de prosseguir.
- Não existe fallback silencioso para certificado/empresa global em operação fiscal identificada — a resolução falha de forma explícita e controlada quando não pode determinar a empresa ou o certificado corretos.

| `errorCode` | HTTP | `retryable` | Situação |
|---|---|---|---|
| `DOCUMENTO_CNPJ_DIVERGENTE` | 422 | `false` | O CNPJ da chave de acesso do documento não corresponde ao CNPJ da empresa resolvida para a operação — falha explícita em vez de prosseguir com contexto/certificado de outra empresa |

**Cobertura de teste e risco residual:** implementação validada por teste automatizado (isolamento entre CNPJs, falha explícita de resolução de contexto). Smoke test real multi-CNPJ desses quatro eventos (cancelamento, CC-e, consulta, inutilização) em HOM ainda está pendente — distinto da emissão (abaixo), que já foi validada em contexto multi-CNPJ real.

**Evidência real da emissão multi-CNPJ (22-07-2026):** uma tentativa de emissão pela empresa padrão associada ao token do integrador OMS chegou à SEFAZ e retornou `cStat=209` ("IE do emitente inválida") — achado cadastral fiscal real, tratado corretamente pelo sistema (rejeição estruturada, `errorCode: SEFAZ_REJECTED`, HTTP 422, nenhum dado alterado indevidamente). Em seguida, uma emissão para uma segunda empresa vinculada ao mesmo token (com Inscrição Estadual ativa e cadastro completo) foi autorizada com `cStat=100` e `<modFrete>2</modFrete>` confirmado no XML transmitido — comprovando que `FiscalContextoResolver`/`resolverPorJtiECnpj` selecionam corretamente empresa e certificado por `cnpjEmitente`, sem exigir um token diferente por emitente.

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

### 10.6 Certificado OMS — carregamento por JTI e CNPJ

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

### 10.7 Certificados de HOM e de PRD — distinção obrigatória

Os certificados A1 disponibilizados e utilizados atualmente no projeto são **exclusivos do ambiente de homologação e teste**. Eles não devem ser reutilizados em produção. O ambiente de produção utilizará um **certificado A1 distinto**, ainda não disponibilizado — será fornecido posteriormente pelo responsável da empresa emitente, por canal seguro, durante a preparação formal do ambiente de produção.

Este manual não registra e não deve registrar: nome de empresa, CNPJ, caminho real de arquivo, senha, conteúdo base64, alias de keystore, token ou qualquer identificação completa de certificado — real ou de teste.

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
|-----------------|------------------------------|-----------------------------------------------------------------------------|
| Administrador | `ADMIN`                   | Todas as operações, incluindo criar/atualizar empresas e gerenciar usuários |
| Operador      | `OPERADOR`                | Operações de negócio (produtos, pedidos, emissão fiscal)                    |

Restrições aplicadas em `SecurityConfig` (validado em HOM):

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
|------------------------------------------|---------------------------------------------|
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

### 11.7 Requisitos de segurança antes de PRD

Nenhum dos itens abaixo tem evidência direta de exposição confirmada nesta revisão — são registrados como requisitos a revisar e validar antes de produção, não como incidentes já comprovados:

- Segredos fora de arquivos versionados.
- Rotação de credenciais antes de PRD.
- Revisão de permissões de acesso ao ambiente de produção.
- Revisar e validar a sanitização de logs — impedir registro de tokens, senhas, headers de autenticação, certificados e XML fiscal sensível.
- Isolamento de certificados por empresa (armazenamento e acesso).
- Política de retenção de logs revisada para o volume de produção.
- Validação de procedimento de rollback.

---

### 11.8 Revogação e Rotação de Autorização OMS (Gate 7H)

Até 22-07-2026, `JwtFilter` validava apenas a assinatura e a expiração do JWT — uma autorização OMS revogada continuava aceita em qualquer requisição até o token expirar naturalmente (TTL longo, por desenho). A partir do commit `4a39a88`, `JwtFilter` consulta a autorização ativa no banco a cada requisição OMS: revogação passa a ter efeito imediato, sem depender da expiração do token.

**Endpoints administrativos** (`/api/admin/oms-authorizations/{id}/revogar` e `/rotacionar`, `ROLE_ADMIN`, fora do contrato público da OMS):

- **Revogação:** idempotente, sempre HTTP 200; marca a autorização como revogada e cria evento de auditoria `REVOGACAO`.
- **Rotação:** emite novo JWT (novo JTI) para a mesma autorização, invalidando o JTI anterior; requer `expectedVersion` (controle otimista — `AUTHORIZATION_CHANGED` se a versão não corresponder) e permite reativar uma autorização revogada quando a versão corresponder à versão pós-revogação; cria evento de auditoria `ROTACAO`.
- **`Idempotency-Key` (UUID) obrigatória** em ambos — replay da mesma chave retorna o mesmo resultado sem reprocessar (mesmo token, mesma versão, nenhuma auditoria adicional); `IDEMPOTENCY_KEY_CONFLICT` se a chave já foi usada para outra autorização/operação.
- **Auditoria append-only** (`oms_fiscal_authorization_audit`, V032): um evento por operação, com versão anterior/nova, `Idempotency-Key`, `requestId` e motivo (`motivoCodigo`/`motivoDetalhe`) — **nunca armazena o JWT**, apenas metadados da operação.
- Resposta administrativa sempre com `Cache-Control: no-store` e `Pragma: no-cache` — o corpo contém um JWT e não pode ser cacheado por proxy/browser intermediário.

**Validação em HOM/SP (22-07-2026):**

- Autorização técnica isolada (ambiente de teste, sem relação com integrador real): duas rotações e uma revogação encadeadas, incluindo replay idempotente de cada operação — versão final e contagem de eventos de auditoria conferem exatamente com o número de operações reais (replays não duplicam).
- Autorização real do integrador OMS: uma rotação (versão inicial `0` → versão final `1`), um evento de auditoria `ROTACAO`, replay idempotente confirmado sem nova auditoria, token novo funcional em endpoints protegidos, JTI alterado (confirmado por comparação interna, nunca exibido).

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
  "chaveNfe":      "{{chaveNfe}}",

  "cStat":         "100",
  "xMotivo":       "Autorizado o uso da NF-e",
  "nProt":         "135260512345678",
  "dhRecbto":      "2026-05-12T10:10:00",

  "consultaSefaz": "<retConsSitNFe>...</retConsSitNFe>"
}
```

| Campo                                       | Presença     | Origem                                           |
|------------------------------------------------|----------------|-------------------------------------------------------|
| `pedidoId`, `numero`, `status`, `chaveNfe`  | Sempre       | Banco de dados local (`pedidos`)                 |
| `cStat`, `xMotivo`, `nProt`, `dhRecbto`     | Condicional  | Tabela `nfe_documento` (se existir para a chave) |
| `consultaSefaz`                             | Sempre       | Chamada live `consSitNFe` à SEFAZ em tempo real  |

Ver seção 9.6 para o contexto fiscal multi-CNPJ aplicado a este endpoint.

### 12.2 Cancelamento (evento 110111)

```
POST /api/app/pedidos/{id}/cancelar
Body: { "justificativa": "mínimo 15 caracteres" }
```

Condições verificadas por `PedidoOperacaoService.cancelar()`:
1. `status == "AUTORIZADO"` — lança `IllegalStateException` (HTTP 422) para qualquer outro status
2. `nfe_documento` existe para a chave e tem `nProt` preenchido — lança `IllegalStateException` (HTTP 422) se ausente

O serviço resolve o contexto fiscal pelo CNPJ real da operação (seção 9.6), monta o XML do evento de cancelamento, assina com `AssinaturaXmlService.assinarEvento()`, transmite para o endpoint SEFAZ e atualiza o status do pedido para `"CANCELADO"`.

Resposta: XML bruto da SEFAZ em `data` do envelope `Result<String>`.

### 12.3 Carta de Correção Eletrônica (CC-e, evento 110110)

```
POST /api/app/pedidos/{id}/cce
Body: { "correcao": "mínimo 15 caracteres" }
```

Condição: `status == "AUTORIZADO"` — lança `IllegalStateException` (HTTP 422) caso contrário. A CC-e não altera o status do pedido. Limite SEFAZ: 20 CC-e por chave NF-e. Contexto fiscal multi-CNPJ resolvido conforme seção 9.6.

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
|----------|-------------------------------------------------------------------|
| 400    | Chave com comprimento diferente de 44 dígitos                 |
| 401    | Token JWT ausente ou inválido                                 |
| 404    | NF-e não encontrada na tabela `nfe_documento`                 |
| 422    | XML da NF-e ainda não disponível (emissão não processada)     |
| 500    | Falha interna na geração do PDF                               |

**Arquitetura DANFE:**

| Classe                    | Módulo            | Responsabilidade                                                   |
|-----------------------------|----------------------|--------------------------------------------------------------------------|
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
                  ├─ AssinaturaXmlService.assinarEvento() → RSA-SHA1 + C14N
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

## 13. HISTÓRICO DE INVESTIGAÇÃO cStat=225 E STATUS DA HOMOLOGAÇÃO SEFAZ-SP

> **STATUS ATUAL (14-07-2026): RESOLVIDO.** A causa raiz do `cStat=225` foi identificada e corrigida nesta data. O motor fiscal obteve `cStat=100` ("Autorizado o uso da NF-e") em HOM/SP para uma empresa emitente com cadastro fiscal aceito pela SEFAZ na emissão de homologação, tanto em teste interno quanto em teste do integrador via OMS, com confirmação cruzada no portal público `hom.nfe.fazenda.gov.br`. Esta seção documenta o histórico completo da investigação (~10 semanas) para rastreabilidade — incluindo a hipótese inicial, que estava incorreta — e o status atual por situação cadastral de empresa.

### 13.1 cStat=225 — "Rejeição: Falha no Schema XML do lote de NFe" (RESOLVIDO 14-07-2026)

**Causa raiz real — XML incompatível com o schema da NF-e (identificada e corrigida em 14-07-2026):**

`cStat=225` indica falha de validação do XML perante o schema da NF-e. No incidente observado em 14-07-2026, a causa esteve relacionada à estrutura e aos algoritmos declarados na assinatura XMLDSig: o motor assinava o XML com RSA-SHA256/SHA-256, enquanto o schema XMLDSig oficial vigente da SEFAZ (`xmldsig-core-schema_v1.01.xsd`, confirmado no pacote oficial `PL_010e_v1.02` baixado diretamente de nfe.fazenda.gov.br, versão vigente publicada 10-07-2026) define os atributos `Algorithm` de `SignatureMethod` e `DigestMethod` com `fixed` — um único valor aceito, sem alternativa: `http://www.w3.org/2000/09/xmldsig#rsa-sha1` e `http://www.w3.org/2000/09/xmldsig#sha1`.

O arquivo XSD local usado na validação pré-envio (`xsd/oficial/xmldsig-core-schema_v1.01.xsd`) estava divergente do oficial: as restrições `fixed` haviam sido removidas, fazendo com que a validação local aceitasse qualquer algoritmo e "passasse" mesmo com a assinatura no algoritmo divergente do exigido. Isso mascarou o problema durante toda a investigação anterior — a validação local nunca acusava a divergência que a SEFAZ rejeitava.

**Correção aplicada:** `AssinaturaXmlService` alterado para assinar com RSA-SHA1/SHA-1 (`SignatureMethod`/`DigestMethod`). O conteúdo semântico do schema XMLDSig utilizado foi alinhado ao pacote oficial `PL_010e_v1.02`, preservando as restrições oficiais de validação para RSA-SHA1/SHA-1. O arquivo local possui diferenças não funcionais de documentação/formatação em relação ao arquivo original.

**Causas adicionais corrigidas na mesma investigação (14-07-2026):** depois de corrigir a assinatura, mais problemas reais foram encontrados e corrigidos em cascata até a autorização completa:

| # | Problema | Correção |
|---|---|---|
| 1 | Grupo `indIntermed` ausente em `<ide>` — exigido pelo layout vigente; ausência observada como rejeição `cStat=434` ("NF-e sem indicativo do intermediador") | Campo adicionado em `Ide` e `NfeXmlBuilder`; preenchido com `"0"` (venda direta, sem intermediador/marketplace) — ver seção 5.7 para o estado atual e a distinção entre correção estrutural e regra de negócio |
| 2 | `indFinal` fixo em `"0"` independentemente do destinatário | Nesta investigação foi aplicado um ajuste provisório, inferido do tipo de documento do destinatário (CPF → `1`, CNPJ → `0`). **Esse ajuste foi posteriormente substituído por completo** — ver seção 5.7 para o modelo atual, configurável por empresa emitente |
| 3 | IE do emitente ausente/inválida no cadastro | Empresa emitente habilitada em HOM: cadastro fiscal aceito pela SEFAZ nessa emissão, corrigido no cadastro. Uma segunda empresa testada permanece com IE cassada por inatividade — pendência cadastral externa à empresa; o cadastro fiscal deve ser confirmado e, se necessário, corrigido pelo responsável fiscal da empresa (ver 13.3) |
| 4 | Exigências de payload (lado da requisição enviada pelo OMS) | CFOP correto por UF de destino; endereço completo do destinatário com código IBGE do município; texto padrão de homologação no nome do destinatário |

**Resultado:** `cStat=100` (Autorizado o uso da NF-e) obtido em HOM/SP pela primeira vez no histórico do projeto, para a empresa emitente com cadastro fiscal aceito pela SEFAZ na emissão de homologação, em teste interno e em teste cruzado do integrador via OMS, com confirmação cruzada no portal público nacional (hom.nfe.fazenda.gov.br).

**Impacto em HOM/PRD:** desde a v1.9, qualquer `cStat≥200` resulta em `REJEITADO` e `POST /emitir` retorna HTTP 422 `SEFAZ_REJECTED` com `data.cStat`/`data.xMotivo`. Com a correção de 14-07-2026, o fluxo de emissão para empresas com cadastro fiscal aceito pela SEFAZ passa a retornar `cStat=100` (`AUTORIZADO`) em vez de `cStat=225` (`REJEITADO`). **Problemas cadastrais — incluindo Inscrição Estadual inapta ou cassada — não devem ser associados ao `cStat=225`: devem ser diagnosticados pelo `cStat` e `xMotivo` efetivamente retornados pela SEFAZ na tentativa, e cada rejeição tem código próprio.** Não é uma particularidade de HOM nem de São Paulo — é a especificação nacional vigente.

> **Registro histórico — hipótese anterior, incorreta:** entre 08-05-2026 e 13-07-2026, a hipótese de trabalho era que `cStat=225` fosse uma limitação de infraestrutura do processador de autorização individual da SEFAZ-SP (`verAplic=SP_NFE_PL_008i2`), tratado como componente legado que ainda exigiria SHA-1 enquanto o resto do país usaria SHA-256, e que portanto não seria um bug do sistema. Essa hipótese estava **errada**: SHA-1 é exatamente o que o schema oficial exige até hoje (confirmado na versão mais atual do pacote de schemas, publicada 10-07-2026), e o bug era do lado do Borurio. Os detalhes técnicos originais que levaram a essa hipótese (padrão `retEnviNFe.cStat=104` com `protNFe.cStat=225`, dois processadores distintos) permanecem verdadeiros como observação de campo — a interpretação da causa é que estava incorreta.

### 13.2 Bug de dado real corrigido em 10-07-2026 — cadastro do emitente incompleto

Durante a investigação, uma segunda causa real e distinta para o mesmo `cStat=225`/mesmo `xMotivo` foi identificada e corrigida em 10-07-2026: a empresa emitente (auto-criada via autorização OMS, que só recebe CNPJ/razão social/UF do certificado A1 — ver seção 9.5) estava sem endereço (`logradouro`/`numero`/`bairro`/`codigoMunicipio`/`municipio`/`cep` ausentes), gerando um `enderEmit` incompleto no XML.

**Correção:** endereço completado via `PUT /api/app/empresas/{id}` (ou automaticamente desde v1.9, via campos `emit*` em `POST /api/app/pedidos` — ver seção 4). Além disso, `NfeGeracaoService.validarEnderecoEmitente()` (v1.9) intercepta esse caso ANTES de montar o XML e chamar a SEFAZ, retornando `EMITTER_ADDRESS_INCOMPLETE` (422) em vez de deixar a SEFAZ rejeitar por schema. Esse bug é independente do problema de assinatura descrito em 13.1 e continua corrigido.

### 13.3 Status atual por situação cadastral de empresa emitente (14-07-2026)

| Situação | cStat obtido |
|---|---|
| Empresa emitente com cadastro fiscal aceito pela SEFAZ na emissão de homologação | `cStat=100` — AUTORIZADO, funcional em HOM |
| Empresa emitente com IE cassada por inatividade | Bloqueada — pendência cadastral externa da empresa junto à SEFAZ, **não é bug do sistema**; o cadastro fiscal deve ser confirmado e, se necessário, corrigido pelo responsável fiscal da empresa. Essa empresa não deve ser usada como referência de certificado de produção |

### 13.4 Invalidação de cache de certificado

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
       {"username":"admin@empresa.com","password":"<senha>"}
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
□ 9. `cStat=100` esperado para empresa com cadastro fiscal aceito pela SEFAZ na emissão de homologação; `cStat≥200` retorna HTTP 422 `SEFAZ_REJECTED` — inspecionar `data.cStat`/`data.xMotivo` (ver seção 13)
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

Executar com Postman collection (`docs/postman/borurio-erp-collection.json`) ou sequência curl em HOM. Ver `INTEGRATION_CONTRACT_PT-BR.md` para payloads completos.

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

### 14.5 Última execução de testes automatizados registrada

Última execução local registrada em 16-07-2026:

```
mvn -pl borurio-web -am test -DskipTests=false
```

Resultado informado: **177 testes aprovados, 1 teste ignorado preexistente** (cobre os módulos `borurio-core`, `borurio-app`, `borurio-fiscal` e `borurio-web` via `-am`).

Essa é a última execução registrada, não o total definitivo do projeto. A contagem depende dos módulos e testes efetivamente selecionados pelo comando executado. Contagens anteriores (57, 61, 66, 75, 82, 126, 190) registradas em versões anteriores deste manual não devem ser comparadas isoladamente com a atual — a variação entre elas pode decorrer de seleção de módulos, renomeação, remoção ou configuração do Surefire, e não foi investigada nesta consolidação como indício de regressão. Testes automatizados não substituem migration em MySQL real, uso de certificado real de homologação, deploy ou chamada real à SEFAZ.

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

**Implementado:** 30-06-2026, com regressão completa passando.

---

### DA-09: `indFinal` resolvido pela empresa emitente, não pelo documento do destinatário

**Decisão:** `indFinal` é um padrão configurável em `Empresa.indFinalPadrao`, resolvido para a empresa fiscal emitente da operação — não inferido do CPF/CNPJ do destinatário.

**Motivação:** o formato do documento do destinatário não determina a natureza da operação. Um CNPJ também pode ser consumidor final; a heurística anterior produziria declaração fiscal incorreta nesse cenário.

**Consequência:** o valor de `indFinal` é responsabilidade do cadastro da empresa, não do payload do pedido. Um override por pedido é evolução possível, não implementada nesta versão.

---

## 16. ROADMAP ATÉ PRODUÇÃO

### Fase 10 — Documentação e Swagger ✓ CONCLUÍDA (12-05-2026)

| Item                                          | Status                                                  |
|-------------------------------------------------|---------------------------------------------------------|
| Manual técnico PT                             | ✓ Este documento                                        |
| Manual técnico EN                             | ✓ `MTF-001_motor-fiscal-nfe_EN.md`                      |
| Contrato de integração PT-BR                  | ✓ `INTEGRATION_CONTRACT_PT-BR.md`                       |
| Contrato de integração EN                     | ✓ `INTEGRATION_CONTRACT_EN.md`                          |
| Swagger anotado (10 tags, deprecated marcado) | ✓ `SwaggerConfig.java`                                  |
| Postman collection end-to-end                 | ✓ `docs/postman/borurio-erp-collection.json`            |

### Fase 12-B — DANFE ✓ CONCLUÍDA (18-05-2026)

| Item                                                            | Status                                        |
|--------------------------------------------------------------------|----------------------------------------------------|
| `DanfeXmlParser` — XPath + namespace `nfe:` sobre XML assinado | ✓ `borurio-fiscal/danfe/` — 18-05-2026        |
| `DanfePdfGenerator` — OpenPDF 1.3.30, barcode128, watermark    | ✓ `borurio-fiscal/danfe/` — 18-05-2026        |
| `DanfeService` + `DanfeServiceImpl`                             | ✓ `borurio-fiscal/danfe/` — 18-05-2026        |
| `DanfeController` `GET /api/fiscal/nfe/{chave}/danfe`           | ✓ `borurio-web/controller/fiscal/` — 18-05-2026 |
| `DanfeControllerTest` — 5 cenários cobertos                     | ✓ testes de controller — 18-05-2026          |

### Fase 11 — Deploy PRD (pendente)

| Item                                        | Prioridade   | Descrição                                                             |
|-----------------------------------------------|----------------|-----------------------------------------------------------------------|
| Teste controlado em produção (operação fiscal real, passível de cancelamento, acompanhado pelo contador) | **CRÍTICO — BLOQUEANTE** | `cStat=100` em HOM não substitui a validação em PRD. Autorização em HOM confirma conformidade de schema/assinatura; não confirma numeração/série de produção, comportamento do certificado PRD (`tpAmb=1`) nem o ciclo real perante o contador — ver seção 13 |
| Certificado A1 de produção — ver seção 10.7 | **CRÍTICO**  | Certificado distinto do usado em HOM, ainda não disponibilizado; será fornecido pelo responsável da empresa emitente |
| `CERT_ENCRYPTION_KEY` em PRD                | **CRÍTICO**  | Gerar via `openssl rand -base64 32`; injetar via mecanismo seguro de gestão de segredos, fora do repositório e dos arquivos versionados |
| Empresa emitente ativa, credenciada e com situação cadastral validada para PRD | **CRÍTICO** | Não presumir que uma empresa testada em HOM está automaticamente apta para PRD |
| Migrations V029/V030 validadas contra MySQL real | **CRÍTICO** | Ver seção 3.1 |
| Segurança pré-PRD (segredos, rotação, sanitização de logs, isolamento de certificados, rollback) | **CRÍTICO** | Ver seção 11.7 |
| Estratégia de contingência SEFAZ | **CRÍTICO** | Ver subseção abaixo — modalidade ainda não definida |
| Smoke test multi-CNPJ real dos eventos pós-emissão em HOM | **ALTO** | Ver seção 9.6 |
| Adequação da NF-e à Reforma Tributária do Consumo | **BLOQUEADOR CONDICIONAL** | Ver seção 17 — depende do regime e das operações da empresa emitente |
| CI/CD pipeline                              | **MÉDIO**    | Deploy → build → push image → deploy HOM → smoke test   |
| Monitoramento                               | **BAIXO**    | Observabilidade de produção — não definido nesta versão |
| ~~Invalidação automática de cache~~         | ~~ALTO~~     | ✓ Implementado — `EmpresaController.atualizar()` chama `invalidar()` |
| ~~Rate limiting~~                           | ~~MÉDIO~~    | ✓ Implementado — `RateLimitInterceptor`; login 10 req/min, `/emitir` default 30 (HOM=300 desde 30-07-2026, provisório — ver subseção abaixo) |
| ~~Política de retenção `nfe_log`~~          | ~~BAIXO~~    | ✓ Implementado — `NfeLogRetencaoScheduler` + `@EnableScheduling`     |

#### Rate limiting — `/emitir`

- Valor padrão no código (`RateLimitInterceptor`) permanece **30 requisições/minuto por IP** quando `RATE_LIMIT_EMITIR_MAX` não é configurado.
- Em HOM, desde 30-07-2026, `RATE_LIMIT_EMITIR_MAX=300` (via `docker/env/.env.hom`), após relato do OMS de bloqueio em volume real de emissões.
- Valor **provisório** de homologação — não é o valor definitivo de PRD.
- O valor de PRD será definido após o OMS informar pico esperado por minuto e concorrência, seguido de teste de carga.
- A proteção contra abuso não foi removida; o controle segue ativo, só o teto foi ampliado.
- O controle atual continua sendo por IP (`request.getRemoteAddr()`), não por empresa/token/JWT.
- Evolução futura a avaliar: identificação por empresa/OMS via JWT em vez de IP, e resposta com header `Retry-After` no 429.

#### Contingência fiscal

Requisito obrigatório antes de PRD, **não confundir com retry HTTP** — contingência SEFAZ é um modo operacional distinto (ex.: emissão sem resposta online da SEFAZ), diferente de reenviar uma chamada que falhou por timeout. Pendências:

- Definir a estratégia/modalidade de contingência aplicável ao cenário do projeto — não escolher a modalidade por suposição; validar contra a documentação oficial vigente.
- Documentar critérios de ativação.
- Documentar reconciliação posterior de documentos emitidos em contingência.
- Definir tratamento de estado incerto (falha de comunicação com a SEFAZ após transmissão).
- Testes específicos da modalidade escolhida.
- Validação da estratégia com o responsável fiscal.

---

## 17. REFORMA TRIBUTÁRIA DO CONSUMO — IBS, CBS E IS

**Classificação: GAP REGULATÓRIO PRIORITÁRIO — NÃO IMPLEMENTADO.**

O projeto tem como requisito a adequação dos leiautes da NF-e aos novos tributos da reforma tributária do consumo — IBS (Imposto sobre Bens e Serviços), CBS (Contribuição sobre Bens e Serviços) e IS (Imposto Seletivo). Esta seção registra o gap; **nenhuma implementação foi feita nesta consolidação documental.**

### 17.1 Estado da referência normativa

A referência inicial disponível no acervo do projeto é a Nota Técnica 2025.002-RTC, versão 1.00 — o pacote que introduziu campos, tipos, classificações tributárias, totalizadores e eventos relacionados a IBS, CBS e IS no layout da NF-e. **Essa versão é histórica e não deve ser tratada como a versão normativa atual.** Os portais oficiais já registram evolução da NT 2025.002 além da v1.00 — em 16-07-2026, a referência disponível publicamente já havia avançado até a versão 1.50. Antes de qualquer implementação, a versão mais recente publicada no Portal Nacional da NF-e deve ser consultada e confirmada como a vigente no momento da implementação.

### 17.2 Estado do código atual

Busca no `MTF-001` e no código do motor fiscal não encontrou evidência de implementação dos grupos, cálculos, tipos ou validações da RTC (IBS/CBS/IS) — nem no `NfeXmlBuilder`, nem nos domínios de `Ide`/`Det`/`Total`. **Não declarar IBS, CBS ou IS como implementados. Não declarar compatibilidade de PRD com a reforma tributária.**

### 17.3 Impacto técnico esperado (não implementado)

A adequação, quando priorizada, deve avaliar impacto em:

- Atualização dos pacotes XSD para a versão vigente da NT.
- Tipos básicos compartilhados de DF-e (Documento Fiscal Eletrônico).
- CST de IBS/CBS.
- `cClassTrib` (classificação tributária).
- Grupo de IBS/CBS/IS por item do XML.
- Totalizadores da NF-e.
- Regras de cálculo tributário.
- Eventos fiscais relacionados.
- DANFE — layout e campos exibidos.
- Domínio fiscal (entidades/DTOs do motor).
- `NfeXmlBuilder` e os demais builders de XML.
- Validações preventivas antes da transmissão.
- Persistência — colunas/tabelas parametrizadas para os novos tributos.
- Contrato de integração OMS — possíveis campos novos no payload.
- Testes unitários e de schema.
- Homologação real contra o ambiente da SEFAZ.
- Validação contábil/fiscal formal.

### 17.4 Aplicabilidade — ressalva obrigatória

O CGIBS informou que, a partir de 03-08-2026, os campos de IBS e CBS passam a ser exigidos sistemicamente para empresas do regime regular, com rejeição de documentos incompletos. A Receita Federal também registra a obrigação de emissão de documentos fiscais eletrônicos com destaque de IBS e CBS no período de testes de 2026.

**Isso não deve ser interpretado automaticamente como a mesma regra aplicável integralmente a qualquer empresa emitente do Borurio.** A aplicabilidade depende do regime tributário (CRT), do enquadramento e das operações reais de cada empresa emitente — não se deve assumir que uma regra destinada ao regime regular se aplica da mesma forma a uma empresa do Simples Nacional sem validação específica. Antes de qualquer implementação, é necessário validar CRT, enquadramento, operações e tratamento fiscal com contador ou responsável tributário.

### 17.5 Classificação de prioridade e impacto

- **Para o motor fiscal multiempresa:** gap regulatório real, não uma evolução distante.
- **Para a entrega prevista até novembro de 2026:** prioridade alta.
- **Para PRD:** **BLOQUEADOR CONDICIONAL PARA PRD, CONFORME REGIME E OPERAÇÃO DA EMPRESA EMITENTE** — não um bloqueador incondicional de todo o fluxo já validado em HOM, mas também não algo que pode ser presumido como não-aplicável sem confirmação fiscal.
- **Trilha de trabalho:** deve existir uma trilha técnica separada de análise e implementação, começando por análise de aplicabilidade fiscal e comparação entre o código atual e a versão vigente da NT — sem alterar o motor fiscal até o escopo ser aprovado.

---

## 18. REFERÊNCIAS NORMATIVAS

| Documento                                  | Descrição                                                         |
|-----------------------------------------------|-------------------------------------------------------------------------|
| AJUSTE SINIEF 07/2005 e alterações         | Institui a Nota Fiscal Eletrônica                                 |
| Manual de Orientação do Contribuinte (MOC) | Versão 7.0 — layout NF-e 4.00                                     |
| Nota Técnica 2019.001                      | Atualização do layout NF-e 4.00                                   |
| Nota Técnica 2025.002-RTC                  | Reforma Tributária do Consumo — IBS/CBS/IS; referência histórica em acervo é v1.00, versão vigente publicamente evoluiu até v1.50 (16-07-2026) — ver seção 17 |
| xmldsig-core-schema_v1.01.xsd (PL_010e_v1.02) | Schema XMLDSig oficial vigente — define `SignatureMethod`/`DigestMethod` fixos em RSA-SHA1/SHA-1 |
| ABNT NBR ISO/IEC 27001                     | Gestão de segurança da informação                                 |
| XML-DSig W3C Recommendation                | `https://www.w3.org/TR/xmldsig-core/`                             |
| RFC 5652                                   | Cryptographic Message Syntax (base do PKCS#12)                    |

---

*Documento MTF-001 — versão 3.0 — Borurio ERP Fiscal BR*
*Gerado com base no estado validado em HOM em 11-05-2026*
*Última revisão documental: 16-07-2026 — consolidação de P0.1–P0.4 como capacidades permanentes do motor (concorrência, contexto multi-CNPJ, baseline seguro, indFinal por empresa, suporte a indIntermed); certificados de HOM/PRD distinguidos; gap regulatório da Reforma Tributária do Consumo registrado; identidade de empresas e pessoas reais neutralizada*
*Próxima revisão prevista: após teste controlado em produção (ver seção 16) ou definição de escopo da trilha de Reforma Tributária (ver seção 17)*
