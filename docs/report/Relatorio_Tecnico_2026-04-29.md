# Relatório Técnico — 29/04/2026

**Projeto:** Borurio ERP Fiscal BR / Jcho ERP  
**Branch:** `fix/sefaz-xml-structure`  
**Responsável:** Bruno Ribeiro  
**Status geral:** BUILD SUCCESS — 5 módulos / 18 testes / 0 falhas

---

## 1. Contexto e objetivo do dia

Sessão de consolidação e maturação do motor fiscal. Três frentes paralelas:

1. Aplicar o bloco de 7 correções/melhorias de infraestrutura identificadas na sessão anterior
2. Diagnosticar e corrigir bugs reais encontrados durante análise do sistema completo
3. Implementar o primeiro módulo fiscal avançado: **Cancelamento de NF-e (Evento 110111)**

Regras ativas durante a sessão:
- Não chamar SEFAZ real (sem POST /api/fiscal/nfe/enviar)
- Não fazer commits nem push
- Não iniciar módulos de pedido/estoque
- Não refatorar de forma ampla

---

## 2. Estado do projeto em números

| Métrica | Valor |
|---|---|
| Módulos Maven | 5 (core, app, fiscal, web, pai) |
| Arquivos Java main | 79 |
| Migrations Flyway | V001 a V010 |
| Testes automatizados | 18 (0 falhas, 1 skip @Disabled SSL) |
| Controllers REST | 8 |
| Endpoints mapeados | ~25 |
| Diff desta sessão | +752 inserções / -1142 remoções |

---

## 3. Bloco 1 — Correções de infraestrutura (7 itens)

### 3.1 V010__alter_ncm_add_ativo.sql

**Problema:** `NcmMapper.listarNcmAtivos()` executava `WHERE ativo = TRUE`, mas a migração V006 criou a tabela `ncm` sem a coluna `ativo`. Qualquer chamada ao `GET /api/fiscal/ncm/listar` causaria SQL error em runtime.

**Correção:** Migration V010 adiciona `ativo TINYINT(1) NOT NULL DEFAULT 1` com índice `idx_ncm_ativo`. O DEFAULT já preenche todos os registros existentes como ativos.

### 3.2 NcmMapper — SELECT explícito

**Problema:** `SELECT *` sem ordenação. Colunas da entidade (`aliquota`, `unidadeMedida`) não existem na tabela, potencial confusão em mapeamento futuro.

**Correção:** `SELECT id, codigo, descricao, ativo FROM ncm WHERE ativo = TRUE ORDER BY codigo`.

### 3.3 GlobalExceptionHandler

**Arquivo:** `borurio-web/src/main/java/br/com/borurio/web/exception/GlobalExceptionHandler.java`

**Problema:** Cada controller tinha try/catch manual retornando HTTP 200 mesmo em erro. Sem padronização de status HTTP.

**Correção:** `@RestControllerAdvice` com:
- `IllegalArgumentException` → HTTP 400 + `Result.code=400`
- `NoSuchElementException` → HTTP 404 + `Result.code=404`
- `Exception` (catch-all) → HTTP 500 + `Result.code=500`

### 3.4 SecurityConfig — endpoints fiscais protegidos

**Problema:** `/api/fiscal/nfe/enviar` e `/api/fiscal/nfe/status` estavam em `permitAll()`. Qualquer agente externo podia transmitir NF-e para SEFAZ sem autenticação.

**Correção:** Ambos removidos do bloco `permitAll()`. Agora exigem JWT válido.

### 3.5 NfeLogController — API de auditoria

**Arquivo:** `borurio-web/.../controller/fiscal/NfeLogController.java`

**Endpoints criados:**
```
GET  /api/fiscal/nfe/logs          → lista todos os eventos fiscais
GET  /api/fiscal/nfe/logs/{chave}  → busca por chave de acesso NF-e (44 dígitos)
```

### 3.6 Remoção de NfeStatusService (serviço duplicado)

**Análise prévia (grep + compile):**
- `NfeStatusService` / `NfeStatusServiceImpl` / `NfeStatusServiceTest` — grep confirmou zero referências funcionais no codebase
- `NfeEnvioController.status()` já usava `NfeTransmitService.consultarStatus()` — o `NfeStatusService` era idle Spring bean
- Compilação confirmou ausência de dependências

**Arquivos removidos:**
- `NfeStatusService.java`
- `NfeStatusServiceImpl.java`
- `NfeStatusServiceTest.java`

### 3.7 docker-compose.dev.yml — variáveis emitente

**Problema:** `FISCAL_EMITENTE_*` ausentes no bloco `environment:`. Operadores não sabiam quais vars configurar no `.env.dev`.

**Correção:** Todas as 12 variáveis declaradas sem valor (herdam de shell/env_file, sem override):
```yaml
FISCAL_EMITENTE_CNPJ:
FISCAL_EMITENTE_RAZAO_SOCIAL:
# ... 10 outros campos
```

---

## 4. Bloco 2 — Bugs reais corrigidos

### 4.1 NfeAuthorizeServiceImpl — parâmetros invertidos

**Classificação:** Bug de dados (silencioso)

**Impacto:** Todo evento de `AUTORIZACAO_MOCK` persistia no banco com:
- `chave_nfe` = literal `"AUTORIZACAO_MOCK"` (constante)
- `tipo_evento` = `"NF-e autorizada localmente (mock SEFAZ)"`
- `descricao` = a chave real de 44 dígitos
- `usuario` = XML completo do documento autorizado (múltiplos KB numa coluna VARCHAR)

**Raiz:** Assinatura de `registrarEvento` é `(chaveNfe, tipoEvento, descricao, usuario)`. A chamada passava os args na ordem `(tipoEvento, descricao, chaveNfe, xmlString)`.

**Correção:**
```java
// ANTES (errado):
nfeLogService.registrarEvento(
    "AUTORIZACAO_MOCK",
    "NF-e autorizada localmente (mock SEFAZ)",
    chaveNFe,
    xmlString   ← XML inteiro como "usuário"
);

// DEPOIS (correto):
nfeLogService.registrarEvento(
    chaveNFe,
    "AUTORIZACAO_MOCK",
    "NF-e autorizada localmente (mock SEFAZ)",
    "system"
);
```
Variável `xmlString` (dead code após correção) removida.

### 4.2 NfeOrquestradorService — tpAmb e UF hardcoded

**Classificação:** Bug de configuração crítico para PRD

**Impacto:** Em produção (`tpAmb=1` no YAML), o log registraria `"Amb=2"`. Mais grave: o valor `2` seria propagado para `consultarStatus()`, causando rejeição pela SEFAZ (envelope SOAP com `<tpAmb>2</tpAmb>` para URL de produção).

**Correção:** `NfeOrquestradorService` passou a injetar:
- `@Value("${sefaz.tpAmb:2}")` → ambiente real por perfil
- `EmitenteProperties` → UF real do emitente

```java
// ANTES:
nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente, "SP", 2);

// DEPOIS:
String uf = emitente.getUf() != null ? emitente.getUf() : "SP";
nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente, uf, tpAmb);
```

### 4.3 NfeEnvioController.status() — SOAP com tpAmb errado em PRD

**Classificação:** Bug crítico de produção (rejeição SEFAZ garantida)

**Impacto:** `consultarStatus("SP", 2)` hardcoded. Em PRD:
- URL configurada aponta para SEFAZ produção
- SOAP enviaria `<tpAmb>2</tpAmb>` (homologação)
- SEFAZ PRD rejeita: ambiente divergente

**Correção:** Controller injetou `EmitenteProperties` e `@Value("${sefaz.tpAmb:2}")`:
```java
String uf = emitente.getUf() != null ? emitente.getUf() : "SP";
String resposta = nfeTransmitService.consultarStatus(uf, tpAmb);
```

### 4.4 NfePipelineLocalTest — construtor desatualizado

**Causa:** `NfeOrquestradorService` ganhou 4º parâmetro (`EmitenteProperties`). Teste instanciava com 3 args.

**Correção:** `EmitenteProperties emitente = new EmitenteProperties()` adicionado. Campos nulos são inócuos porque o guard P0 (`<enviNFe>` rejeitado) dispara antes de qualquer acesso ao emitente.

---

## 5. Bloco 3 — Cancelamento de NF-e (Evento 110111)

### 5.1 Contexto fiscal

O Cancelamento é o segundo evento fiscal mais crítico do ciclo de vida de uma NF-e, imediatamente após a autorização. Sem ele, uma NF-e emitida com erro (CNPJ incorreto, valores errados, destinatário trocado) não tem recurso dentro do sistema — a empresa precisaria fazer o cancelamento manualmente via portal SEFAZ.

**Prazo SEFAZ:** 24 horas após autorização para cancelamento automático (após isso, requer carta de correção ou cancelamento via exigência fiscal).

**Código do evento:** 110111  
**Endpoint SEFAZ:** `recepcao-evento` (NFeRecepcaoEvento4)

### 5.2 AssinaturaXmlService — refatoração para suporte a eventos

**Problema original:** `assinar()` localizava `infNFe` e inseria a `<Signature>` dentro de `<NFe>`. Para eventos, o elemento a assinar é `infEvento` e a `<Signature>` vai dentro de `<evento>`.

**Refatoração mantém backward compatibility:**

```
assinar(xmlNfe)
  └→ localizarElementoPorTag(doc, "infNFe")
  └→ assinarElemento(doc, infNFe, doc.getDocumentElement())   ← Signature em <NFe>

assinarEvento(xmlEvento)
  └→ localizarElementoPorTag(doc, "infEvento")
  └→ assinarElemento(doc, infEvento, infEvento.getParentNode()) ← Signature em <evento>
```

Método privado genérico `assinarElemento(Document, Element elementoParaAssinar, Element container)` centraliza a lógica XMLDSIG RSA-SHA256.

### 5.3 Arquivos implementados

| Arquivo | Módulo | Papel |
|---|---|---|
| `dto/NfeCancelamentoRequest.java` | borurio-fiscal | DTO de entrada: chaveNfe, nProtocolo, justificativa |
| `service/NfeCancelamentoService.java` | borurio-fiscal | Interface: `cancelar(req)` |
| `service/impl/NfeCancelamentoServiceImpl.java` | borurio-fiscal | Lógica completa |
| `controller/fiscal/NfeCancelamentoController.java` | borurio-web | POST /api/fiscal/nfe/cancelar |

### 5.4 Fluxo do cancelamento

```
POST /api/fiscal/nfe/cancelar (JWT obrigatório)
  │
  ├── validar() — chave 44 dígitos, protocolo obrigatório, justificativa 15-255 chars
  │
  ├── montarEnvEvento() — XML envEvento com infEvento Id="ID110111{chave}01"
  │     <cOrgao> = CUF do emitente (via UF_PARA_CUF map)
  │     <tpAmb>  = @Value("${sefaz.tpAmb:2}")
  │     <CNPJ>   = EmitenteProperties.getCnpj()
  │
  ├── assinaturaXmlService.assinarEvento() — XMLDSIG RSA-SHA256 em infEvento
  │
  ├── montarSoap() — envelope SOAP 1.2 com namespace NFeRecepcaoEvento4
  │
  ├── enviarSoap() — HTTPS com SSLContext do certificado A1
  │
  ├── [SUCESSO] registrarLog(tipoEvento="CANCELAMENTO", status="SUCCESS")
  └── [ERRO]    registrarLog(tipoEvento="CANCELAMENTO", status="ERROR") + relança exception
```

### 5.5 Validações implementadas

```java
chaveNfe  → 44 dígitos exatos (apenas dígitos)
nProtocolo → não nulo, não vazio
justificativa → 15 ≤ length ≤ 255 (regra SEFAZ NT 2019.001)
```

---

## 6. Mapa completo de endpoints da API

### Autenticação
| Método | Endpoint | Auth | Descrição |
|---|---|---|---|
| POST | /auth/login | Público | Autenticação JWT |
| GET | /ping | Público | Health simples |

### App — Clientes
| Método | Endpoint | Auth | Descrição |
|---|---|---|---|
| GET | /api/app/clientes | JWT | Lista todos |
| GET | /api/app/clientes/{id} | JWT | Busca por ID |
| POST | /api/app/clientes | JWT | Cadastra |
| PUT | /api/app/clientes/{id} | JWT | Atualiza |

### App — Produtos
| Método | Endpoint | Auth | Descrição |
|---|---|---|---|
| GET | /api/app/produtos | JWT | Lista todos |
| GET | /api/app/produtos/ativos | JWT | Lista ativos |
| GET | /api/app/produtos/{id} | JWT | Busca por ID |
| GET | /api/app/produtos/codigo/{codigo} | JWT | Busca por código |
| POST | /api/app/produtos | JWT | Cadastra |
| PUT | /api/app/produtos/{id} | JWT | Atualiza |
| DELETE | /api/app/produtos/{id} | JWT | Desativa (soft) |

### Fiscal — NCM
| Método | Endpoint | Auth | Descrição |
|---|---|---|---|
| GET | /api/fiscal/ncm/listar | JWT | Lista NCMs ativos |
| GET | /api/fiscal/ncm/{codigo} | JWT | Busca por código |
| POST | /api/fiscal/ncm/sincronizar | JWT | Sincroniza tabela NCM |

### Fiscal — NF-e Emissão
| Método | Endpoint | Auth | Descrição |
|---|---|---|---|
| POST | /api/fiscal/nfe/enviar | JWT | Transmite XML direto |
| POST | /api/fiscal/nfe/gerar | JWT | Gera + transmite via request estruturado |
| GET | /api/fiscal/nfe/status | JWT | Consulta status SEFAZ |

### Fiscal — NF-e Eventos
| Método | Endpoint | Auth | Descrição |
|---|---|---|---|
| POST | /api/fiscal/nfe/cancelar | JWT | Cancela NF-e (Evento 110111) |

### Fiscal — Auditoria
| Método | Endpoint | Auth | Descrição |
|---|---|---|---|
| GET | /api/fiscal/nfe/logs | JWT | Lista todos os eventos fiscais |
| GET | /api/fiscal/nfe/logs/{chave} | JWT | Busca eventos por chave NF-e |

### Actuator
| Método | Endpoint | Auth | Descrição |
|---|---|---|---|
| GET | /actuator/health | Público | Health completo (db, redis, ping) |
| GET | /actuator/info | Público | Info da aplicação |

---

## 7. Schema do banco de dados (10 migrations)

| Migration | Tabela/Ação | Módulo |
|---|---|---|
| V001 | `cliente` | App |
| V002 | `nfe_log` | Fiscal |
| V003 | Dados mock | App |
| V004 | ALTER nfe_log (status, xml_envio, xml_retorno) | Fiscal |
| V005 | Atualiza mock data | App |
| V006 | `ncm`, `ncm_sync_log` (schema oficial 2025) | Fiscal |
| V007 | ALTER cliente (campos fiscais) | App |
| V008 | `db_user` | App |
| V009 | `produto` | App |
| V010 | ALTER ncm (coluna ativo) | Fiscal |

---

## 8. Arquitetura de módulos Maven

```
borurio-erp-br (pai)
├── borurio-core         → Result<T>, ResultUtil, ResultCodeEnum, CommonStateEnum
│   └── sem dependências externas ao projeto
│
├── borurio-app          → entidades e serviços de negócio
│   ├── depende de: borurio-core
│   └── entidades: Cliente, Produto, DbUser
│       mappers: ClienteMapper, ProdutoMapper, DbUserMapper
│       services: ClienteService, ProdutoService
│
├── borurio-fiscal       → motor fiscal NF-e 4.00 / SEFAZ
│   ├── depende de: borurio-core
│   ├── config: EmitenteProperties, SefazProperties, SslConfig
│   ├── domain/nfe: NFe, InfNFe, Ide, Emit, Dest, Det, Produto, Total, EnderEmit
│   ├── builder: NfeXmlBuilder, XmlUtil
│   ├── dto: NfeEmissaoRequest, NfeEmissaoItem, NfeCancelamentoRequest
│   ├── entity: NfeLog, Ncm, NcmSyncLog
│   ├── mapper: NfeLogMapper, NcmMapper, NcmSyncLogMapper
│   ├── utils: XsdValidator
│   └── services:
│       ├── CertificadoService        → carrega PKCS12, fornece PrivateKey, X509, SSLContext
│       ├── AssinaturaXmlService      → XMLDSIG RSA-SHA256 (NF-e + eventos)
│       ├── NfeOrquestradorService    → parse → XSD → assinar → transmitir
│       ├── NfeTransmitService        → SOAP HTTPS (autorização, status, recibo)
│       ├── NfeCancelamentoService    → Evento 110111 (cancelamento)
│       ├── NfeLogService             → auditoria fiscal (nfe_log)
│       ├── NcmService                → tabela NCM
│       ├── NcmSyncLogService         → log de sincronizações NCM
│       └── NfeAuthorizeService       → mock SEFAZ local (usado em testes)
│
└── borurio-web          → API REST (único módulo que depende de app + fiscal)
    ├── depende de: borurio-core, borurio-app, borurio-fiscal
    ├── auth: JwtFilter, JwtUtil, AuthController, AuthService
    ├── config: SecurityConfig, SwaggerConfig, MyBatisConfig, PasswordEncoderConfig
    ├── exception: GlobalExceptionHandler
    ├── service: NfeGeracaoService (bridge app ↔ fiscal)
    └── controllers:
        ├── PingController
        ├── app/ClienteController
        ├── app/ProdutoController
        ├── fiscal/NcmController
        ├── fiscal/NfeEnvioController
        ├── fiscal/NfeCancelamentoController
        └── fiscal/NfeLogController
```

---

## 9. Pipeline NF-e — fluxo técnico completo

```
[EMISSÃO]
NfeGeracaoService.gerar(NfeEmissaoRequest)
  ├── validar campos obrigatórios
  ├── calcular chave de acesso (44 dígitos)
  │     cUF(2) + AAMM(4) + CNPJ(14) + mod55(2) + serie(3) + nNF(9) + tpEmis(1) + cNF(8) + cDV(1)
  │     cDV = Módulo 11 sobre os 43 primeiros dígitos
  ├── montar NFe (Ide, Emit, Dest, Det, Total)
  ├── NfeXmlBuilder.build(nfe) → XML bare <NFe>
  └── NfeOrquestradorService.processar(xml, cnpj)
        ├── P0 guard: rejeita se raiz ≠ <NFe>
        ├── XsdValidator.validate() → XSD nfe_v4.00_consolidado.xsd
        ├── AssinaturaXmlService.assinar() → XMLDSIG RSA-SHA256 em infNFe
        └── NfeTransmitService.transmitirXml(xmlAssinado, cnpj, uf, tpAmb)
              ├── criarEnvelopeEnviNFe() → <enviNFe> com <NFe> assinada
              ├── SOAP 1.2 → HTTPS → NFeAutorizacao4
              ├── salvarLog (PENDING → SUCCESS/ERROR)
              └── retorna XML SEFAZ (retEnviNFe)

[CANCELAMENTO]
NfeCancelamentoService.cancelar(NfeCancelamentoRequest)
  ├── validar (chave 44 dígitos, protocolo, justificativa 15-255 chars)
  ├── montarEnvEvento() → XML evento 110111 com infEvento Id="ID110111{chave}01"
  ├── AssinaturaXmlService.assinarEvento() → XMLDSIG em infEvento
  ├── SOAP 1.2 → HTTPS → NFeRecepcaoEvento4
  └── NfeLogService.salvar (tipoEvento="CANCELAMENTO")
```

---

## 10. Segurança aplicada (DevSecOps)

| Caminho | Controle |
|---|---|
| Credenciais SEFAZ hardcoded | Removidas (2 arquivos debug deletados) |
| Chaves JWT em código | Eliminadas — lidas de variável de ambiente |
| Senha certificado A1 em código | Eliminada — lida de `${FISCAL_CERT_PASSWORD}` |
| Endpoints fiscais sem auth | Corrigido — todos exigem JWT |
| XML Injection / XXE | DocumentBuilderFactory com disallow-doctype-decl |
| SQL Injection | MyBatis com `#{}` parametrizado em todos os mappers |
| CORS | Desabilitado (API interna) |
| Sessions | STATELESS em todas as chains |

---

## 11. Resultado do dia

| Item | Status |
|---|---|
| Compilação 5 módulos | ✅ BUILD SUCCESS |
| 18 testes automatizados | ✅ 0 falhas |
| NCM runtime bug (ativo column) | ✅ Corrigido |
| GlobalExceptionHandler | ✅ Implementado |
| SecurityConfig endurecido | ✅ Endpoints fiscais protegidos |
| NfeLogController (auditoria) | ✅ Implementado |
| Serviço duplicado removido | ✅ NfeStatusService deletado |
| docker-compose.dev vars emitente | ✅ Documentadas |
| Bug parâmetros NfeAuthorizeServiceImpl | ✅ Corrigido |
| Bug tpAmb hardcoded em PRD | ✅ Corrigido (Orquestrador + Controller) |
| Cancelamento NF-e (Evento 110111) | ✅ Implementado e compilando |
| AssinaturaXmlService (eventos) | ✅ assinarEvento() implementado |

---

## 12. Pendências e próximos passos

### Fiscal avançado (prioridade)

| # | Item | Status |
|---|---|---|
| 1 | Inutilização de numeração NF-e | 🔲 Próximo |
| 2 | Carta de Correção Eletrônica (CC-e) | 🔲 Pendente |
| 3 | Consulta NF-e por chave (GET /api/fiscal/nfe/{chave}) | 🔲 Pendente |
| 4 | Validação CNPJ/CPF Módulo 11 em NfeGeracaoService | 🔲 Pendente |
| 5 | Controle de sequência numérica por série | 🔲 Pendente |
| 6 | Manifestação do destinatário | 🔲 Futuro |
| 7 | DANFE (PDF) | 🔲 Futuro |

### Infraestrutura / Qualidade

| # | Item | Status |
|---|---|---|
| 8 | Commit atômico: segurança (remoção credenciais) | 🔲 Pendente |
| 9 | Commit atômico: infraestrutura (V010, GlobalException, SecurityConfig) | 🔲 Pendente |
| 10 | Commit atômico: Produto module + migrations V007-V010 | 🔲 Pendente |
| 11 | Commit atômico: Cancelamento NF-e | 🔲 Pendente |
| 12 | Validar docker-compose.hom.yml e docker-compose.prd.yml | 🔲 Pendente |
| 13 | Testes unitários NfeCancelamentoServiceImpl | 🔲 Pendente |
| 14 | Paginação em NfeLogController | 🔲 Futuro |

### ERP módulos de negócio (bloqueados por ora)

| # | Item | Status |
|---|---|---|
| 15 | Módulo Pedido / Ordem | ⛔ Não iniciado |
| 16 | Módulo Estoque / Logística | ⛔ Não iniciado |
| 17 | Relatórios / Dashboard | ⛔ Não iniciado |
| 18 | Multiempresa | ⛔ Não iniciado |

---

## 13. Checklist completo — ERP Logístico com Motor Fiscal

### 🏗 Infraestrutura e Ambiente

- [x] Docker Compose DEV funcional (MySQL + Redis + MinIO + Web)
- [x] Docker Compose HOM funcional
- [x] Docker Compose PRD configurado
- [x] Spring Boot 3.3.2 multi-module Maven
- [x] Flyway migrations V001-V010
- [x] MyBatis com @MapperScan centralizado (MyBatisConfig)
- [x] Redis configurado por ambiente
- [x] MinIO configurado (DEV)
- [x] Logback por ambiente (dev, hom, prd)
- [x] /actuator/health retornando UP (db, redis, ping)
- [ ] Pipeline CI/CD (GitHub Actions / Jenkins)
- [ ] Build automatizado por branch
- [ ] Deploy automatizado HOM
- [ ] Deploy automatizado PRD

### 🔐 Segurança

- [x] JWT autenticação (login, validação, expiração)
- [x] JwtFilter delegando para SecurityConfig
- [x] SecurityConfig: chain Actuator (permitAll) + chain App (JWT)
- [x] Endpoints fiscais protegidos por JWT
- [x] XXE protection em todos os DocumentBuilderFactory
- [x] SQL Injection — MyBatis #{} parametrizado
- [x] Credenciais hardcoded removidas (classes debug deletadas)
- [x] Certificado A1 lido de variável de ambiente
- [x] Senha do certificado de variável de ambiente
- [x] STATELESS sessions
- [ ] Refresh token / token revocation
- [ ] Rate limiting nos endpoints de emissão fiscal
- [ ] Audit log de acesso por usuário (quem emitiu qual NF-e)
- [ ] Multiempresa: isolamento de dados por CNPJ/tenant

### 👤 Módulo de Autenticação e Usuários

- [x] POST /auth/login → JWT
- [x] DbUser (entidade + mapper + service)
- [x] UserDetailsServiceImpl
- [ ] POST /auth/refresh-token
- [ ] POST /auth/logout (blacklist)
- [ ] Perfis de usuário (ADMIN, FISCAL, LOGISTICA, FINANCEIRO)
- [ ] Gerenciamento de usuários via API

### 🧑‍💼 Módulo de Clientes

- [x] Entidade Cliente com campos fiscais (CNPJ/CPF, IE, endereço)
- [x] ClienteMapper (MyBatis)
- [x] ClienteService / ClienteServiceImpl
- [x] ClienteController (GET, POST, PUT)
- [x] Migration V001 (tabela cliente)
- [x] Migration V007 (campos fiscais no cliente)
- [ ] Validação CNPJ Módulo 11
- [ ] Validação CPF Módulo 11
- [ ] Busca por CNPJ/CPF
- [ ] Soft delete / inativação
- [ ] Paginação na listagem

### 📦 Módulo de Produtos

- [x] Entidade Produto (codigo, descricao, ncm, cfop, unidade, preco, estado)
- [x] ProdutoMapper (MyBatis)
- [x] ProdutoService / ProdutoServiceImpl
- [x] ProdutoController (GET, POST, PUT, DELETE soft)
- [x] Migration V009 (tabela produto)
- [x] Validação NCM (regex 8 dígitos)
- [x] Validação CFOP (regex 4 dígitos)
- [x] Validação preço > 0
- [x] Unicidade de código de produto
- [ ] Vinculação produto ↔ NCM (FK para tabela ncm)
- [ ] Controle de estoque por produto
- [ ] Histórico de preços
- [ ] Paginação na listagem

### 🏛 Módulo NCM (Tabela Fiscal)

- [x] Tabela ncm com schema oficial PUCOMEX 2025
- [x] NcmMapper com SELECT explícito + coluna ativo
- [x] NcmService / NcmServiceImpl
- [x] NcmController (listar, buscar por código, sincronizar)
- [x] Migration V006 (schema ncm + ncm_sync_log)
- [x] Migration V010 (coluna ativo)
- [x] NcmSyncLogService (log de sincronizações)
- [ ] Sincronização automática via scheduler (cron)
- [ ] Endpoint de sincronização via HTTP do PUCOMEX
- [ ] Busca fulltext por descrição NCM

### 🧾 Motor Fiscal NF-e 4.00

#### Configuração e Certificado
- [x] CertificadoService (carrega PKCS12, PrivateKey, X509Certificate, SSLContext)
- [x] EmitenteProperties (@ConfigurationProperties fiscal.emitente.*)
- [x] SefazProperties (@ConfigurationProperties sefaz.urls.*)
- [x] tpAmb por perfil (DEV/HOM=2, PRD=1)
- [x] URLs SEFAZ por perfil (HOM/PRD)

#### Geração de XML NF-e
- [x] Domain objects: NFe, InfNFe, Ide, Emit, Dest, Det, Produto, Total, EnderEmit
- [x] NfeXmlBuilder (@Component Spring)
- [x] NfeGeracaoService (bridge app ↔ fiscal, chave 44 dígitos, Módulo 11 cDV)
- [x] NfeEmissaoRequest / NfeEmissaoItem DTOs
- [x] Montagem Ide (cUF, cNF, natOp, serie, nNF, dhEmi, tpNF, idDest, cMunFG...)
- [x] Montagem Emit (CNPJ, razaoSocial, nomeFantasia, IE, CRT, endereço)
- [x] Montagem Dest (CNPJ/CPF, xNome, indIEDest)
- [x] Montagem Det (items com cProd, xProd, NCM, CFOP, uCom, qCom, vUnCom, vProd)
- [x] Montagem Total (vProd, vNF)
- [ ] Montagem Transp (frete)
- [ ] Montagem Cobr / Dup (cobrança e duplicatas)
- [ ] Montagem infAdic (informações adicionais)
- [ ] Montagem ICMS por CST/CSOSN
- [ ] Montagem PIS/COFINS por CST
- [ ] Montagem IPI quando aplicável
- [ ] Validação CNPJ/CPF Módulo 11 em NfeGeracaoService
- [ ] Controle de sequência numérica (nNF por série)

#### Assinatura Digital
- [x] AssinaturaXmlService (XMLDSIG RSA-SHA256)
- [x] assinar() para NF-e (assina infNFe, Signature em NFe raiz)
- [x] assinarEvento() para eventos (assina infEvento, Signature em evento)
- [x] Transforms: ENVELOPED + C14N Exclusivo
- [x] KeyInfo com X509Certificate embutido

#### Validação XSD
- [x] XsdValidator com schema nfe_v4.00_consolidado.xsd
- [x] Validação antes da assinatura no NfeOrquestradorService
- [x] P0 guard: rejeita XML com raiz ≠ <NFe>

#### Transmissão SEFAZ
- [x] NfeTransmitService (interface)
- [x] NfeTransmitServiceImpl (SOAP 1.2 HTTPS com certificado A1)
- [x] transmitirXml() → criarEnvelopeEnviNFe → enviarSoap
- [x] consultarStatus(uf, tpAmb) → SOAP NFeStatusServico4
- [x] consultarRecibo(nRec, uf, tpAmb) → SOAP NFeRetAutorizacao4 (modo assíncrono)
- [x] NfeOrquestradorService (orquestra parse → XSD → assinar → transmitir)
- [x] Log de auditoria (NfeLog) em todas as etapas

#### Eventos Fiscais
- [x] Cancelamento (Evento 110111) — NfeCancelamentoService
- [ ] Inutilização de numeração (inutNFe)
- [ ] Carta de Correção Eletrônica (Evento 110110)
- [ ] Manifestação do destinatário (Evento 210200/210210/210220/210240)
- [ ] EPEC (Emissão em Contingência)

#### Auditoria e Log
- [x] NfeLog (entidade: chaveNfe, tipoEvento, status, descricao, xmlEnvio, xmlRetorno, dataEvento, cnpjEmitente, usuario)
- [x] NfeLogMapper (insert, findAll, findByChave, findById, deleteAntigos)
- [x] NfeLogService / NfeLogServiceImpl
- [x] NfeLogController (GET /logs, GET /logs/{chave})
- [x] Migration V002 (tabela nfe_log) + V004 (campos status/xml)
- [ ] Paginação em GET /logs (page, size, sort)
- [ ] Filtros por status, tipoEvento, data
- [ ] Retenção automática (deleteAntigos via scheduler)
- [ ] Exportação de logs (CSV/Excel)

#### DANFE
- [ ] Geração de PDF DANFE
- [ ] Template DANFE NF-e 4.00
- [ ] GET /api/fiscal/nfe/{chave}/danfe

### 🛒 Módulo de Pedidos / Ordens

- [ ] Entidade Pedido (numero, cliente, emitente, status, data, itens)
- [ ] Entidade ItemPedido (produto, qtd, preco, ncm, cfop)
- [ ] PedidoMapper / PedidoService
- [ ] PedidoController (CRUD)
- [ ] Estados do pedido: RASCUNHO → CONFIRMADO → FATURADO → CANCELADO
- [ ] Emissão de NF-e a partir do pedido (Pedido → NfeEmissaoRequest)
- [ ] Vínculo Pedido ↔ NF-e (chave de acesso)
- [ ] Migration tabela pedido + item_pedido

### 📊 Módulo de Estoque / Logística

- [ ] Saldo de estoque por produto/depósito
- [ ] Movimentações (entrada, saída, transferência)
- [ ] Entidade Deposito
- [ ] EstoqueMapper / EstoqueService
- [ ] Reserva de estoque ao confirmar pedido
- [ ] Baixa de estoque ao emitir NF-e
- [ ] Alerta de estoque mínimo

### 📈 Relatórios e Dashboard

- [ ] Dashboard: NF-e emitidas no mês, receita, rejeições, cancelamentos
- [ ] Relatório de NF-e por período
- [ ] Relatório de clientes ativos
- [ ] Relatório de produtos mais vendidos
- [ ] Exportação Excel/PDF

### 📚 Documentação e Onboarding

- [x] Topologias arquiteturais (6 diagramas Draw.io + PNG)
- [x] Relatórios técnicos por sessão (docs/report/)
- [ ] Manual técnico (usando as 6 topologias)
- [ ] Manual de acesso (endpoints, autenticação, exemplos curl)
- [ ] Manual de implantação (DEV → HOM → PRD)
- [ ] Checklist DEV/HOM/PRD para equipe
- [ ] Swagger/OpenAPI atualizado e completo
- [ ] README.md com quickstart

---

## 14. Conclusão

O sistema avançou de forma significativa nesta sessão. O motor fiscal NF-e está cada vez mais maduro e seguro para operar nos três ambientes:

- **DEV:** Compilação limpa, testes 18/18, variáveis de emitente documentadas no compose
- **HOM:** URLs SEFAZ corretas, tpAmb=2 lido de config, endpoints protegidos por JWT
- **PRD:** Bugs críticos de tpAmb hardcoded corrigidos, Emit/UF por config, sem credenciais em código

O cancelamento de NF-e (Evento 110111) completa o ciclo mínimo operacional da emissão: **emitir → cancelar**. O próximo passo é a inutilização de numeração, seguida da CC-e, para cobrir os principais cenários de correção fiscal exigidos pela legislação brasileira.
