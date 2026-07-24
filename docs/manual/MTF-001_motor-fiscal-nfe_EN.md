# MTF-001 — Technical Manual: NF-e 4.00 Fiscal Engine
## Borurio ERP Fiscal BR

---

**Document:** MTF-001  
**Version:** 3.1
**Issued:** 2026-05-11  
**Last updated:** 2026-07-22
**Author:** Bruno Ribeiro — Fullstack Developer / DevSecOps  
**Status:** VALIDATED IN STAGING — new `cStat=100` authorization with `modFrete=2` (company linked to the token with fiscal registration accepted by SEFAZ); OMS token revocation/rotation validated end-to-end; PRD not validated, system not in production; go-live pending final validation by the Chinese integrator (CC)
**Reference branch:** `fix/sefaz-xml-structure`  

> **Note (2026-07-22):** this document was behind the PT-BR original by one full version (v2.9 vs v3.0). Sections 5.6/5.7 (numbering baseline, `indFinal`/`indIntermed`) and 11.7 (pre-PRD security checklist) from the PT-BR v3.0 revision are **not yet mirrored here** — see `MTF-001_motor-fiscal-nfe.md` (PT-BR, canonical) for that content. This revision adds the v3.1 content (freight modality, OMS authorization revocation/rotation) directly; full v3.0 backfill is a separate follow-up.

> **Version history:**
> - v1.0 (2026-05-11): initial document, phases 1–9 + phase 10 in progress
> - v2.0 (2026-05-12): sprint 3 complete; RBAC updated; `/situacao` response corrected; Phase 10 closed; integration endpoints and smoke test added
> - v2.1 (2026-05-15): technical review corrections; SecureRandom for cNF; rate limiting implemented; log retention scheduler implemented; certificate cache invalidation gap resolved; 57 tests passing; integration contract updated with message reference table
> - v2.2 (2026-05-18): Phase 12-A — minimum fiscal inventory implemented; atomic reservation before SEFAZ; definitive write-off on AUTORIZADO; reversal on CANCELADO; `estoque_movimento` table; 61/61 tests; V023–V024 applied
> - v2.3 (2026-05-18): DANFE implemented — `DanfeXmlParser`, `DanfePdfGenerator`, `DanfeService`, `GET /api/fiscal/nfe/{chave}/danfe`; OpenPDF 1.3.30; watermark "SEM VALOR FISCAL" in staging; 66/66 tests; V023–V024 applied to HOM
> - v2.4 (2026-05-21): 3 bugs fixed in `DanfePdfGenerator` — pt_BR monetary formatting in totals, thread-safe `DecimalFormat` per call, conditional protocol label; borurio-web tests 66 → 75 (9 new — fiscal states, inventory RBAC, UsuarioController)
> - v2.5 (2026-05-26): Recipient Manifestation implemented (events 210200/210210/210220/210240); `cOrgao=91` (AN — Ambiente Nacional, NT 2012.004); `SefazProperties.manifestacaoEvento` with distinct AN URL; `cStat` validation in SEFAZ response (cStat=135/136=success, others=rejection); `xml_retorno` captured even on error; section 12.5 added; integration contract v1.3; borurio-web tests 75 → 82 (7 new — NfeManifestacaoController)
> - v2.6 (2026-06-22): V028 Multi-CNPJ OMS — `POST /api/integration/fiscal-authorizations`; token per OMS client (`codigoEmpresaOms`); multiple CNPJs under the same token; auto-company-creation from X.509 Subject; deterministic token via `emitidoEm` truncated to seconds; `cnpjEmitente` OMS validation in `POST /pedidos` (fail-fast HTTP 403); `OmsCertificadoService.resolverPorJtiECnpj`; sections 3.1/3.2/9.5/10.6/16 updated; contract v1.7; 114/114 tests
> - v2.7 (2026-06-30): PRODUCT_NOT_FOUND bug fix in multi-CNPJ flow — `PedidoEmissaoService` separated `empresaId` for stock operations (anchor empresa from the order) from `empresa` for NF-e (fiscal empresa from cnpjEmitente); DA-08 documented; section 9.5 updated with root cause and fix; 126/126 tests
> - v2.8 (2026-07-10): optional per-company stock control (`controleEstoqueAtivo`, commit 936e771); live staging session with CC — diagnosed a real cStat=225 (incomplete emitter registration, new "cause 2" in section 13.1) and fixed it; 3 improvements requested by CC implemented and tested: (1) reissue of `REJEITADO`/`ERRO` orders on the same `pedidoId`; (2) optional emitter address in `POST /api/app/pedidos` auto-completing the company registration; (3) standardized `errorCode`/`retryable`, `SEFAZ_REJECTED` no longer returns HTTP 200; code review (8 agents + 9 verifications) found and fixed 4 bugs: `chaveNfe` loss on retry, empresa mutation without rollback, incorrect `retryable=true` in the global fallback and in `SEFAZ_REJECTED`; integration contract v1.9; 190/190 tests
> - v3.1 (2026-07-22): global OMS authorization revocation/rotation implemented (`JwtFilter` now checks the active authorization in the database on every OMS request — revocation takes effect immediately, no longer dependent on JWT expiry; admin endpoints `/api/admin/oms-authorizations/{id}/revogar` and `/rotacionar`; append-only audit trail; optimistic version control; mandatory `Idempotency-Key`; JWT never persisted — see section 11.7) — migration V032; freight modality (`modFrete`) now explicit per issuance flow via the `ModalidadeFrete` enum (see section 5.6) — the OMS/marketplace flow declares `CONTA_TERCEIROS` (code `2`, the marketplace contracts the freight, confirmed by CC), the legacy endpoint preserves `SEM_OCORRENCIA_TRANSPORTE` (code `9`, previous behavior unchanged); new real SEFAZ authorization in HOM (`cStat=100`) with `modFrete=2` confirmed in the transmitted XML; full regression 299/299.
> - v3.0 (2026-07-16, PT-BR only — not yet fully mirrored in this translation): `indFinal` moved to the per-company configurable model (CPF/CNPJ heuristic removed); new technical subsection for `indIntermed`; concurrent-emission protection, safe numbering baseline and multi-CNPJ post-issuance context documented; migrations V029/V030 added; new section on the HOM vs. PRD certificate distinction; new section on the Consumption Tax Reform (IBS/CBS/IS) regulatory gap.
> - v2.9 (2026-07-14): **real root cause of cStat=225 identified and fixed** — the engine was signing the XML with RSA-SHA256, but the SEFAZ's current official schema (`xmldsig-core-schema_v1.01.xsd`, confirmed in the official `PL_010e_v1.02` package downloaded directly from nfe.fazenda.gov.br) requires `fixed="rsa-sha1"`/`fixed="sha1"`; the local XSD was out of sync with the official one, masking the mismatch in local validation. `AssinaturaXmlService` fixed to RSA-SHA1/SHA-1. The semantic content of the local XMLDSig schema was aligned with the official `PL_010e_v1.02` package, preserving the official validation restrictions for RSA-SHA1/SHA-1 (the local file has non-functional documentation/formatting differences from the original). 4 more fixes in the same investigation: missing `indIntermed` group in `<ide>`; a provisional `indFinal` adjustment inferred from the recipient's document type — pending final definition in the OMS contract (see section 13.1); emitter state tax ID (IE) corrected in the registration of the currently qualified emitting company; OMS-side payload requirements (CFOP per destination state, complete recipient address with IBGE municipality code, standard staging-environment text in the recipient name). Result: **`cStat=100` (Autorizado o uso da NF-e) obtained in HOM/SP for the first time in the project's history**, in an internal test and in a cross-test run by the Chinese integrator via the OMS, cross-confirmed on the public national portal (hom.nfe.fazenda.gov.br). Section 13 rewritten with the full investigation history. A second, historical company registration remains blocked — state tax ID revoked for inactivity since 2024, a registration issue external to the company, not a system bug; not to be used as a production certificate reference.

---

## TABLE OF CONTENTS

1. [Scope and Objective](#1-scope-and-objective)
2. [Module Architecture](#2-module-architecture)
3. [Fiscal Data Model](#3-fiscal-data-model)
4. [Full NF-e Issuance Flow](#4-full-nf-e-issuance-flow)
5. [NF-e XML Generation](#5-nf-e-xml-generation)
6. [XMLDSIG Digital Signature](#6-xmldsig-digital-signature)
7. [SOAP Transmission to SEFAZ](#7-soap-transmission-to-sefaz)
8. [Persistence and Fiscal Audit](#8-persistence-and-fiscal-audit)
9. [Multi-Company (Multitenancy)](#9-multi-company-multitenancy)
10. [Per-Company Digital Certificate](#10-per-company-digital-certificate)
11. [Security Foundation](#11-security-foundation)
12. [Post-Issuance Fiscal Operations](#12-post-issuance-fiscal-operations)
13. [cStat=225 Investigation History and SEFAZ-SP Staging Status](#13-cstat225-investigation-history-and-sefaz-sp-staging-status)
14. [Operational Checklists](#14-operational-checklists)
15. [Architecture Decisions](#15-architecture-decisions)
16. [Roadmap to Production](#16-roadmap-to-production)
17. [Normative References](#17-normative-references)

---

## 1. SCOPE AND OBJECTIVE

This manual describes the technical architecture, processing flows, and operational procedures of the **NF-e 4.00 Fiscal Engine** implemented in the Borurio ERP Fiscal BR system.

The document is intended for:

- **Internal technical team** — maintenance, evolution, and debugging of the engine
- **Partner integration team** — integrating the fiscal engine with the external logistics ERP (see also `INTEGRATION_CONTRACT_EN.md`)
- **Operations / DevOps** — deployment, monitoring, and staging procedures
- **Technical auditors** — traceability of design decisions and compliance

### 1.1 What is CLOSED (validated in HOM)

| Feature                                                                        | Validation                                                |
|--------------------------------------------------------------------------------|-----------------------------------------------------------|
| NF-e 4.00 issuance via SOAP HTTPS                                              | ✓ HOM/SP — 2026-05-11                                     |
| XMLDSIG RSA-SHA1 + C14N signature (per current official schema)                | ✓ HOM/SP — fixed 2026-07-14                               |
| `cStat=100` — Autorizado o uso da NF-e (currently qualified emitting company in HOM) | ✓ HOM/SP — 2026-07-14 (internal + Chinese integrator)      |
| Order → NF-e cycle                                                             | ✓ HOM/SP — 2026-05-08                                     |
| Immutable fiscal snapshot in order item                                        | ✓ HOM/SP — 2026-05-08                                     |
| Semantic status (AUTORIZADO / REJEITADO / AGUARDANDO / ERRO)                   | ✓ HOM/SP — 2026-05-08                                     |
| NF-e cancellation (event 110111)                                               | ✓ HOM/SP — 2026-05-08                                     |
| Electronic Correction Letter (event 110110)                                    | ✓ HOM/SP — 2026-05-08                                     |
| NF-e status query (consSitNFe) — structured response                           | ✓ HOM/SP — 2026-05-08                                     |
| Multi-company — data isolation by empresa_id                                   | ✓ HOM/SP — 2026-05-08                                     |
| Per-company A1 certificate with cache                                          | ✓ HOM/SP — 2026-05-11                                     |
| RBAC (ADMIN / OPERADOR roles) with correct restriction for `/api/app/usuarios` | ✓ HOM/SP — 2026-05-12                                     |
| AES-256-GCM cert_senha encryption                                              | ✓ Code validated; passthrough in HOM (key not configured) |
| Audit log with empresa_id and authenticated user                               | ✓ HOM/SP — 2026-05-11                                     |
| Swagger aligned with all real endpoints (10 tags)                              | ✓ HOM/SP — 2026-05-12                                     |
| End-to-end Postman collection (10 folders, 49 requests)                        | ✓ Generated and aligned — 2026-05-22                      |
| `NfeEnvioController` deprecated — legacy endpoints marked and redirected       | ✓ Code — 2026-05-12                                       |
| PT-BR and EN integration contracts generated and validated                     | ✓ Code — 2026-05-12                                       |
| `MyBatisConfig`: `@ConditionalOnProperty` ensures correct boot in HOM          | ✓ HOM/SP — 2026-05-12                                     |
| 57/57 tests passing (12 controllers covered + fiscal)                          | ✓ Code — 2026-05-15                                       |
| Rate limiting: `/auth/login` (10 req/min) and `/emitir` (30 req/min)           | ✓ Code — 2026-05-15                                       |
| `nfe_log` retention scheduling (`NfeLogRetencaoScheduler`)                     | ✓ Code — 2026-05-15                                       |
| CORS restricted — `*` replaced by explicit origins per environment             | ✓ Code — 2026-05-15                                       |
| `SecureRandom` for `cNF` generation (replaced `new Random()`)                  | ✓ Code — 2026-05-15                                       |
| Minimum fiscal inventory — reservation, write-off, undo, reversal              | ✓ Code — 2026-05-18                                       |
| `estoque_movimento` — full atomic audit of all stock movements                  | ✓ Code — 2026-05-18                                       |
| `GET /api/app/produtos/{id}/estoque` — real-time stock balance query            | ✓ Code — 2026-05-18                                       |
| `POST /api/app/produtos/{id}/estoque/entrada` — manual stock entry [ADMIN]     | ✓ Code — 2026-05-18                                       |
| DANFE — `DanfePdfGenerator` (OpenPDF 1.3.30, barcode128, watermark in staging) | ✓ Code — 2026-05-18                                       |
| `GET /api/fiscal/nfe/{chave}/danfe` — REST endpoint returning `application/pdf`| ✓ Code — 2026-05-18                                       |
| 66/66 tests passing (DANFE added 5 controller tests)                            | ✓ Code — 2026-05-18                                       |
| V023–V024 applied to HOM (Flyway at v024)                                       | ✓ HOM/SP — 2026-05-18                                     |
| `DanfePdfGenerator` thread-safe — `DecimalFormat` recreated per call (replaced `static final`) | ✓ Code — 2026-05-20                        |
| DANFE — pt_BR monetary formatting in totals (`R$ 91,80` with decimal comma)                    | ✓ Code + HOM — 2026-05-20                                 |
| DANFE — conditional protocol label (`RETORNO SEFAZ — HOMOLOGAÇÃO` when `cStat≠100`)           | ✓ Code + HOM — 2026-05-20                                 |
| 75/75 tests passing (borurio-web — 9 new tests on 2026-05-20)                                  | ✓ Code — 2026-05-20                                       |
| Recipient Manifestation (events 210200/210210/210220/210240) — `POST /api/fiscal/nfe/manifestar` | ✓ Code + HOM — 2026-05-26                               |
| `cStat` validation in Manifestation SEFAZ response (cStat=135/136=success, others=rejection)   | ✓ Code — 2026-05-26                                       |
| `xml_retorno` captured in `nfe_log` even when SEFAZ rejects (error path)                       | ✓ Code — 2026-05-26                                       |
| 82/82 tests passing (borurio-web — 7 new for NfeManifestacaoController)                        | ✓ Code — 2026-05-26                                         |
| OMS Fiscal Authorization — `POST /api/integration/fiscal-authorizations`                       | ✓ HOM — 2026-06-22                                          |
| Multi-CNPJ OMS — multiple CNPJs under the same OMS client token                                | ✓ HOM — 2026-06-22                                          |
| Auto-company creation from X.509 Subject on first authorization                                | ✓ HOM — 2026-06-22                                          |
| Deterministic token — `emitidoEm` truncated to seconds; identical token in scenarios B/C/D     | ✓ HOM — 2026-06-22                                          |
| `cnpjEmitente` OMS validation in `POST /pedidos` — fail-fast HTTP 403 before persisting        | ✓ HOM — 2026-06-22                                          |
| `OmsCertificadoService.resolverPorJtiECnpj` — selects cert by CNPJ at issuance time           | ✓ HOM — 2026-06-22                                          |
| Multi-CNPJ smoke test M1–M4/M6 — approved in HOM                                              | ✓ HOM — 2026-06-22                                          |
| **126/126 tests passing** (borurio-web 93 + fiscal 33; +12 NfeEnvioControllerTest A-03)       | ✓ Code — 2026-06-22                                         |
| V025–V028 applied in HOM (Flyway at v028)                                                      | ✓ HOM — 2026-06-22                                          |
| Optional per-company stock control (`controleEstoqueAtivo`)                                    | ✓ HOM — 2026-07-10                                          |
| Reissue of `REJEITADO`/`ERRO` orders on the same `pedidoId` (`STATUS_EMISSIVEIS`)              | ✓ Code — 2026-07-10                                         |
| Optional emitter address in `POST /api/app/pedidos` — auto-completes incomplete registration    | ✓ Code — 2026-07-10                                         |
| `NfeGeracaoService.validarEnderecoEmitente()` — blocks issuance before SEFAZ if address incomplete | ✓ Code — 2026-07-10                                     |
| Standardized `errorCode`/`retryable` — `EMITTER_ADDRESS_INCOMPLETE`, `SEFAZ_REJECTED`, `SEFAZ_TIMEOUT`, `SEFAZ_UNAVAILABLE`, `XML_SCHEMA_INVALID` | ✓ Code — 2026-07-10                       |
| `POST /emitir` no longer returns HTTP 200 when SEFAZ rejects the NF-e                          | ✓ Code — 2026-07-10                                         |
| **190/190 tests passing** (code review with 4 fixes: chaveNfe loss, empresa mutation without rollback, incorrect retryable) | ✓ Code — 2026-07-10                       |

### 1.2 What is PENDING

| Feature                                                         | Phase    | Note                                |
|-----------------------------------------------------------------|----------|-------------------------------------|
| `CERT_ENCRYPTION_KEY` configured in production                  | Phase 11 | Passthrough active in HOM by design |
| Automated CI/CD                                                 | Phase 11 | Manual deployment via docker cp     |
| Production A1 certificates with real CNPJ                       | Phase 11 | Phase 11 critical item              |

---

## 2. MODULE ARCHITECTURE

### 2.1 Dependency diagram

```
borurio-core
    │
    ├── borurio-app
    │       └── entities, mappers, business services
    │
    ├── borurio-fiscal
    │       └── NF-e engine, XMLDSIG, SOAP, audit
    │
    └── borurio-web  (Spring Boot executable JAR)
            ├── depends on borurio-app
            ├── depends on borurio-fiscal
            └── REST controllers, JWT auth, business bridges
```

### 2.2 Module responsibilities

| Module           | Root package            | Responsibility                                                                                                     |
|------------------|-------------------------|--------------------------------------------------------------------------------------------------------------------|
| `borurio-core`   | `br.com.borurio.core`   | Shared DTOs, `ResultUtil`, `PageResponse`, base utilities                                                          |
| `borurio-app`    | `br.com.borurio.app`    | Business entities, MyBatis mappers, services: Empresa, Produto, Pedido, DbUser                                     |
| `borurio-fiscal` | `br.com.borurio.fiscal` | NF-e XML generation, XMLDSIG signing, SOAP transmission, sequencer, fiscal persistence, audit                      |
| `borurio-web`    | `br.com.borurio.web`    | Spring Boot, REST controllers, JWT, bridges (`PedidoEmissaoService`, `NfeGeracaoService`), per-company certificate |

### 2.3 Module boundary rule

> **`borurio-fiscal` does NOT import `borurio-app`.**

The bridge between the two domains is exclusively the `borurio-web` module. When the fiscal module needs issuer company data, it receives a `CertificadoContexto` record (JDK types only) instead of receiving the `Empresa` entity.

### 2.4 Technology stack

| Component         | Version / Technology                                             |
|-------------------|------------------------------------------------------------------|
| Language          | Java 17                                                          |
| Framework         | Spring Boot 3.3.2                                                |
| Persistence       | MyBatis (annotations)                                            |
| Database          | MySQL 8.4                                                        |
| Migrations        | Flyway (V001–V032)                                               |
| Auth              | Stateless JWT (HMAC-SHA256)                                      |
| Security          | Spring Security 6.x                                              |
| XML Signing       | Java XML Crypto API (`javax.xml.crypto.dsig`)                    |
| SOAP              | Direct HTTPS (no CXF, no wsimport)                               |
| Certificate cache | In-memory `ConcurrentHashMap`                                    |
| Container         | Docker (internal image); port 8081 in HOM                        |
| API docs          | springdoc-openapi 2.6.0 — Swagger UI at `/swagger-ui/index.html` |

---

## 3. FISCAL DATA MODEL

### 3.1 Applied migrations (V001–V028)

| Migration  | Description                                                                  |
|------------|------------------------------------------------------------------------------|
| V001       | `cliente` (legacy, not used in the main fiscal flow)                         |
| V002       | `nfe_log` — fiscal event audit                                               |
| V003       | Reference mock data                                                          |
| V008       | `db_user` — authentication and authorization                                 |
| V009       | `produto`                                                                    |
| V011       | `nfe_sequencia` — number control per serie/CNPJ                              |
| V012       | `nfe_documento` — fiscal state of each authorized NF-e                       |
| V013       | `produto` — fiscal fields: origem, csosn, estoque                            |
| V014       | `pedido` + `pedido_item`                                                     |
| V015       | `pedido_item` — fiscal snapshot (ncm, cfop, csosn, origem, unidade)          |
| V016       | `empresa` — multi-issuer registration                                        |
| V017       | `empresa_id` in `db_user`, `produto`, `pedido`                               |
| V018       | `empresa` — per-company A1 certificate (cert_path, cert_senha, cert_tipo)    |
| V019       | `db_user.role` (ADMIN / OPERADOR) + `nfe_log.empresa_id`                     |
| V020       | `cliente.empresa_id` — multi-company isolation for customers                 |
| V021       | `cliente.nome` and `cliente.email` nullable                                  |
| V022       | Missing foreign key constraints on `pedido_item`, `nfe_documento`, `nfe_log`                |
| V023       | `estoque_movimento` — atomic audit of all stock movements                                    |
| V024       | `produto.estoque_reservado` DECIMAL(13,4) NOT NULL DEFAULT 0                                |
| V025       | `oms_api_key` — API keys for OMS integrators (SHA-256 hash, `integrator_id`)               |
| V026       | `oms_fiscal_authorization` — one slot per OMS client (`integrator_id`, `codigo_oms`, `jti`, `emitido_em`) |
| V027       | `oms_company_certificate` — PKCS12 certificate per `auth_id` (initial single-CNPJ structure) |
| V028       | Multi-CNPJ: `oms_fiscal_authorization` slot without `empresa_id`; `oms_company_certificate` adds `cnpj`, `empresa_id`, generated column `cnpj_ativo_unico` |

### 3.2 Main fiscal tables

#### `nfe_documento`
Stores the persisted state of each issued NF-e. Source of truth for offline fiscal queries and DANFE reissuance.

| Column          | Type          | Description                                                   |
|-----------------|---------------|---------------------------------------------------------------|
| `chave_nfe`     | VARCHAR(44)   | Access key (44 digits)                                        |
| `numero`        | VARCHAR(9)    | NF-e number                                                   |
| `serie`         | VARCHAR(3)    | Series                                                        |
| `cnpj_emitente` | VARCHAR(14)   | Issuer CNPJ without formatting                                |
| `cnpj_cpf_dest` | VARCHAR(14)   | Recipient CNPJ/CPF                                            |
| `c_stat`        | VARCHAR(10)   | SEFAZ status code (`"100"` = authorized, `"101"` = cancelled) |
| `x_motivo`      | VARCHAR(255)  | SEFAZ status description                                      |
| `n_prot`        | VARCHAR(20)   | Authorization protocol number (15 digits)                     |
| `valor_total`   | DECIMAL(13,2) | Total NF-e value                                              |
| `xml_nfe`       | LONGTEXT      | Signed XML without protocol                                   |
| `xml_protocolo` | LONGTEXT      | Complete nfeProc (fiscal archiving — 5 years)                 |
| `tp_amb`        | INT           | 1=production / 2=staging                                      |
| `dh_recbto`     | DATETIME      | SEFAZ receipt timestamp                                       |
| `data_emissao`  | DATETIME      | Issuance timestamp (`dhEmi` from XML)                         |

#### `nfe_log`
Audit record for each fiscal operation.

| Column        | Type         | Description                                                             |
|---------------|--------------|-------------------------------------------------------------------------|
| `chave_nfe`   | VARCHAR(44)  | Access key linked to the event                                          |
| `tipo_evento` | VARCHAR(100) | `ENVIO_NFE` / `TRANSMISSAO_SEFAZ` / `CONSULTA` / `CANCELAMENTO` / `CCE` |
| `status`      | VARCHAR(20)  | `SUCCESS` / `ERROR` / `PENDING`                                         |
| `usuario`     | VARCHAR(100) | Authenticated user (`SecurityContextHolder`)                            |
| `empresa_id`  | BIGINT       | Issuing company ID (V019)                                               |
| `xml_envio`   | LONGTEXT     | Transmitted XML                                                         |
| `xml_retorno` | LONGTEXT     | SEFAZ SOAP response                                                     |

#### `nfe_sequencia`
Guarantees atomic uniqueness of the NF-e number per CNPJ + serie.

| Column          | Description        |
|-----------------|--------------------|
| `cnpj`          | Issuer CNPJ        |
| `serie`         | NF-e series        |
| `ultimo_numero` | Last issued number |

---

## 4. FULL NF-e ISSUANCE FLOW

### 4.1 Call sequence (order → NF-e)

```
HTTP Client
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
    │  ├─ pedidoService.buscarComItens(id)      → validates status ∈ {RASCUNHO, REJEITADO, ERRO} (v1.9)
    │  ├─ montarRequest(pedido)                  → NfeEmissaoRequest with fiscal snapshot
    │  ├─ resolverEmpresa(empresaId)              → Empresa from JWT context
    │  │
    │  │  nfeGeracaoService.gerar(req, empresa)
    │  │
    ▼  ▼
NfeGeracaoService (borurio-web)
    │  ├─ validarEnderecoEmitente(empresa)        → (v1.9) throws EMITTER_ADDRESS_INCOMPLETE BEFORE building XML, if address incomplete
    │  ├─ montarIde / montarEmit / montarDest / montarDet / montarTotal
    │  ├─ nfeXmlBuilder.build(nfe)               → unsigned XML
    │  ├─ sequenciaService.proximoNumero()        → atomic number per serie/CNPJ
    │  ├─ empresaCertificadoService.resolverPorEmpresa() → CertificadoContexto or null
    │  │
    │  │  nfeOrquestradorService.processar(xml, cnpj, certCtx)
    │  │
    ▼  ▼
NfeOrquestradorService (borurio-fiscal)
    │  ├─ [1] converterParaDocument()             → parse XML, namespace-aware
    │  ├─ [2] xsdValidator.validate()             → against xsd/custom/nfe_v4.00_consolidado.xsd — throws XmlSchemaValidationException (v1.9)
    │  ├─ [3] assinaturaXmlService.assinar()      → XMLDSIG RSA-SHA1 + C14N (per official schema)
    │  └─ [4] nfeTransmitService.transmitirXml()  → SOAP HTTPS → SEFAZ
    │
    ▼
NfeTransmitServiceImpl (borurio-fiscal)
    │  ├─ criarEnvelopeEnviNFe()                  → batch with 1 NF-e
    │  ├─ enviarSoap(url, envelope, sslContext)    → HTTPS POST (retried via SefazRetryConfig/resilience4j)
    │  └─ salvarLogSeguro(nfeLog)                  → nfe_log with real user
    │
    ▼
NfeGeracaoService (response handling)
    │  ├─ retornoParser.parse(soap)                → NfeSefazRetorno { cStat, xMotivo, nProt }
    │  ├─ documentoService.salvarComRetorno()      → nfe_documento persisted
    │  └─ registrarLog()                           → nfe_log with empresa_id
    │
    ▼
PedidoEmissaoService (post-issuance)
    │  ├─ resolverStatus(retorno)                  → AUTORIZADO / REJEITADO / AGUARDANDO
    │  ├─ pedidoService.atualizarStatus()          → pedido.status + chave_nfe (preserves existing chaveNfe if the new attempt fails, v1.9)
    │  ├─ baixarEstoque()                          → only if AUTORIZADO (cStat=100)
    │  └─ (v1.9) if REJEITADO: throws BusinessException.sefazRejected(cStat, xMotivo) — HTTP 422, not 200
```

If an exception is thrown during `nfeGeracaoService.gerar()`, the service calls `traduzirFalhaTransmissao()` (v1.9) to classify the cause before rethrowing: `XmlSchemaValidationException` → `XML_SCHEMA_INVALID` (422); `SocketTimeoutException` → `SEFAZ_TIMEOUT` (503, retryable); `ConnectException`/`UnknownHostException` → `SEFAZ_UNAVAILABLE` (503, retryable); any other unclassified exception → generic HTTP 500 (retryable=false). In every case the order ends up in `ERRO`, preserving any `chaveNfe` it already had.

### 4.2 Semantic order status

| Status       | Condition                                                                          | Stock reduced?   |
|--------------|------------------------------------------------------------------------------------|------------------|
| `RASCUNHO`   | Order created, not yet transmitted                                                 | No               |
| `AUTORIZADO` | SEFAZ returned `cStat = 100`                                                       | Yes              |
| `AGUARDANDO` | Batch accepted (`cStat = 104`) without infProt, or failure to parse SEFAZ response | No               |
| `REJEITADO`  | `cStat >= 200` — HTTP 422 `SEFAZ_REJECTED` (v1.9, no longer HTTP 200)              | No — reservation reversed |
| `ERRO`       | Exception during transmission — HTTP 422/500/503 depending on classification (v1.9) | No — reservation reversed |
| `CANCELADO`  | Cancellation event authorized                                                      | N/A              |

> **v1.9:** `REJEITADO` and `ERRO` are no longer terminal — `PedidoEmissaoService.STATUS_EMISSIVEIS = {RASCUNHO, REJEITADO, ERRO}` allows calling `/emitir` again on the same `pedidoId`. Each new attempt generates a fresh `nNF`/`chaveNfe` via `NfeSequenciaService`, with no duplicate-submission risk to SEFAZ.

### 4.3 Immutable fiscal snapshot

When the order is created, `PedidoServiceImpl` calls `preencherSnapshot()`, copying fiscal fields from `Produto` into `PedidoItem`:

```
PedidoItem.codigoProduto ← Produto.codigo
PedidoItem.descricao     ← Produto.descricao
PedidoItem.ncm           ← Produto.ncm
PedidoItem.cfop          ← Produto.cfop
PedidoItem.unidade       ← Produto.unidade
PedidoItem.origem        ← Produto.origem  (default: 0)
PedidoItem.csosn         ← Produto.csosn   (default: "400")
```

**Invariant:** after the order is created, any subsequent change to the product catalog does not affect the order's fiscal data. Issuance always uses the snapshot frozen in `pedido_item`.

---

## 5. NF-e XML GENERATION

### 5.1 Components involved

| Class                    | Module         | Responsibility                                     |
|--------------------------|----------------|----------------------------------------------------|
| `NfeGeracaoService`      | borurio-web    | Assembles the NF-e blocks from `NfeEmissaoRequest` |
| `NfeXmlBuilder`          | borurio-fiscal | Serializes the `NFe` object to XML via JAXB        |
| `NfeOrquestradorService` | borurio-fiscal | Orchestrates validation + signing + transmission   |

### 5.2 Access key calculation (44 digits)

```
chave43 = cUF(2) + aaaMM(4) + CNPJ(14) + mod(2=55) + serie(3) + nNF(9) + tpEmis(1) + cNF(8)
cDV     = modulo 11 over chave43
chave   = chave43 + cDV
```

`cNF` (numeric code) is generated with `SecureRandom.nextInt(100_000_000)` — using `java.security.SecureRandom` to ensure cryptographic unpredictability.

The NF-e number (`nNF`) is obtained atomically via `NfeSequenciaService.proximoNumero(cnpj, serie)` — uses `SELECT ... FOR UPDATE` (or equivalent) to guarantee uniqueness under concurrent load.

### 5.3 Generated XML structure (simplified)

```xml
<NFe versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
  <infNFe Id="NFe{44-digits}" versao="4.00">
    <ide>     <!-- identification -->
    <emit>    <!-- issuer: CNPJ, company name, address, IE, CRT -->
    <dest>    <!-- recipient: CPF/CNPJ, name, address -->
    <det>     <!-- items: product, NCM, CFOP, ICMS(CSOSN), PIS, COFINS -->
    <total>   <!-- ICMSTot with 20 mandatory fields -->
    <transp>  <!-- modFrete explicit per issuance flow (see section 5.6): 2 (Third Party) for the OMS flow, 9 (No Freight) for the legacy endpoint -->
    <pag>     <!-- detPag: indPag=0, tPag=01, vPag=total -->
  </infNFe>
  <Signature>  <!-- inserted by AssinaturaXmlService after XSD validation -->
</NFe>
```

### 5.4 Fiscal fields per product

Each XML item (`<det>`) uses the data frozen in `PedidoItem`:

| NF-e block           | Field   | Source                                                      |
|----------------------|---------|-------------------------------------------------------------|
| `<prod>`             | `cProd` | `item.codigoProduto`                                        |
| `<prod>`             | `NCM`   | `item.ncm` (8 digits, validated against official NCM table) |
| `<prod>`             | `CFOP`  | `item.cfop`                                                 |
| `<prod>`             | `uCom`  | `item.unidade`                                              |
| `<ICMS>`             | `orig`  | `item.origem`                                               |
| `<ICMS>`             | `CSOSN` | `item.csosn` (e.g.: `400` = CRT 1 without ICMS taxation)    |
| `<PIS>` / `<COFINS>` | `CST`   | `07` (exempt operation)                                     |

### 5.5 Pre-signature XSD validation

Before signing, `NfeOrquestradorService` validates the XML against:

```
borurio-fiscal/src/main/resources/xsd/custom/nfe_v4.00_consolidado.xsd
```

This schema consolidates `leiauteNFe_v4.00.xsd` + `tiposBasico_v4.00.xsd` into a single file to resolve classpath dependencies. Validation uses the JAXP API (`javax.xml.validation`).

### 5.6 `modFrete` — freight modality per issuance flow

Until 2026-07-22, `NfeXmlBuilder` wrote `modFrete=9` ("No Occurrence of Transport") fixed for every issuance. The CC (Xiao Li) confirmed that, in the current flow integrated with e-commerce marketplaces, freight is contracted/operated by the marketplace itself — neither by the issuer nor the recipient — which technically corresponds to `modFrete=2` ("Freight Contracted by Third Party"), not `9`.

**Model implemented:** enum `ModalidadeFrete` (`borurio-fiscal`, package `domain.nfe`) with the six NF-e 4.00 layout values. `NfeXmlBuilder.build()` and `NfeGeracaoService.gerar()` now require the modality explicitly — no overload omits it, no hidden default:

| Flow | Caller | Declared modality | `modFrete` in XML |
|---|---|---|---|
| OMS / marketplace | `PedidoEmissaoService.emitir()` | `ModalidadeFrete.CONTA_TERCEIROS` | `2` |
| Legacy/admin (`POST /api/fiscal/nfe/gerar`, deprecated) | `NfeEnvioController` | `ModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE` | `9` (previous behavior preserved) |

The `<transporta>` group (carrier data) is `minOccurs="0"` in the official XSD regardless of `modFrete` — not required and not sent, since the Borurio never receives carrier data from the OMS.

**Scope:** applies to the current marketplace flow. Not automatically generalized to direct sales, local pickup or own transport — those scenarios, if they appear, may require per-order/channel parameterization (conditional roadmap, not implemented in this version).

**Validated in HOM/SP on 2026-07-22:** real authorization (`cStat=100`) with `<modFrete>2</modFrete>` confirmed in the actually transmitted and authorized XML (a company linked to the token with fiscal registration accepted by SEFAZ, multi-CNPJ flow).

---

## 6. XMLDSIG DIGITAL SIGNATURE

### 6.1 Algorithms (per the current official `xmldsig-core-schema_v1.01.xsd`)

> **2026-07-14 correction:** until 2026-07-13, the code signed with RSA-SHA256, following a reading that NT 2019.001 required SHA-256. That reading was incorrect — the SEFAZ's official XMLDSig schema (`xmldsig-core-schema_v1.01.xsd`), confirmed in the `PL_010e_v1.02` package downloaded directly from nfe.fazenda.gov.br (current version, published 2026-07-10), defines the `Algorithm` attributes of `SignatureMethod` and `DigestMethod` with `fixed` — i.e., a single accepted value, no alternative: `rsa-sha1` and `sha1`. The local XSD used for validation (`xsd/oficial/xmldsig-core-schema_v1.01.xsd`) was out of sync with the official one — without the `fixed` restrictions, it accepted any algorithm, making local validation "pass" incorrectly before submission to SEFAZ. Fixed on this date: signing now uses RSA-SHA1/SHA-1. The semantic content of the XMLDSig schema used was aligned with the official `PL_010e_v1.02` package, preserving the official validation restrictions for RSA-SHA1/SHA-1 — the local file has non-functional documentation/formatting differences from the original file. Result: `cStat=100` obtained in HOM/SP.

| Algorithm        | URI                                                                |
|------------------|--------------------------------------------------------------------|
| Signature        | `http://www.w3.org/2000/09/xmldsig#rsa-sha1` (RSA-SHA1)            |
| Digest           | `http://www.w3.org/2000/09/xmldsig#sha1` (SHA-1)                   |
| Canonicalization | `http://www.w3.org/TR/2001/REC-xml-c14n-20010315` (Inclusive C14N) |
| Transform 1      | `ENVELOPED` (removes the Signature element itself from the digest) |
| Transform 2      | Inclusive C14N                                                     |

### 6.2 Signing flow

```
AssinaturaXmlService.assinar(xmlNfe, ctx?)
    │
    ├─ 1. parse XML → Document (namespace-aware, anti-XXE)
    ├─ 2. localizarElementoPorTag("infNFe") → Element
    ├─ 3. infNFe.setIdAttribute("Id", true)  → registers Id as ID type in DOM
    ├─ 4. XMLSignatureFactory.getInstance("DOM")
    ├─ 5. Reference("#" + id, SHA-1, [ENVELOPED, C14N])
    ├─ 6. SignedInfo(C14N, RSA-SHA1, [reference])
    ├─ 7. KeyInfo(X509Data(cert))
    ├─ 8. XMLSignature.sign(DOMSignContext(privateKey, nfeElement))
    └─ 9. serialize(doc) → UTF-8, no XML declaration, no indentation
```

### 6.3 Signature element position

The `<Signature>` is inserted as a direct child of the `<NFe>` element, **after** `<infNFe>`:

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

The same pattern is used for events (`infEvento`) and numbering cancellation (`infInut`), using `assinarEvento()` and `assinarInutilizacao()`.

### 6.4 XXE protections

All XML parsers in the system are configured with:

```java
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
```

---

## 7. SOAP TRANSMISSION TO SEFAZ

### 7.1 Staging endpoint (SP)

| Service            | HOM URL                                                                   |
|--------------------|---------------------------------------------------------------------------|
| NF-e Authorization | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeautorizacao4.asmx`       |
| Service Status     | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfestatusservico4.asmx`     |
| Status Query       | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeconsultaprotocolo4.asmx` |

### 7.2 SOAP 1.2 envelope

`NfeTransmitServiceImpl` builds the envelope directly (no WSDL client generated):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
  <soap12:Body>
    <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
      <enviNFe versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
        <idLote>{15 digits}</idLote>
        <indSinc>1</indSinc>
        {signedXml}
      </enviNFe>
    </nfeDadosMsg>
  </soap12:Body>
</soap12:Envelope>
```

`indSinc=1` → synchronous processing (immediate response). Batch contains 1 NF-e.

### 7.3 mTLS authentication

The SEFAZ connection uses **mutual TLS authentication** (mTLS):

- The system presents the issuer's A1 certificate in the `KeyManagerFactory`
- `SSLContext` configured with `TLSv1.2`
- Certificate loaded from PKCS12 KeyStore

When no per-company certificate is configured, falls back to the global `CertificadoServiceImpl`.

### 7.4 Response interpretation

SEFAZ returns a SOAP envelope with `retEnviNFe`. `NfeSefazRetornoParser` extracts:

| Field     | Description                                                     |
|-----------|-----------------------------------------------------------------|
| `cStat`   | Status code (100 = authorized, 104 = pending, 2xx+ = rejection) |
| `xMotivo` | Textual status description                                      |
| `nProt`   | Authorization protocol number (present only if cStat=100)       |
| `chNFe`   | Access key returned by SEFAZ                                    |

---

## 8. PERSISTENCE AND FISCAL AUDIT

### 8.1 nfe_documento

After each transmission, `NfeDocumentoService.salvarComRetorno()` persists:

```
nfe_documento {
    chave_nfe, numero, serie, cnpj_emitente,
    cnpj_cpf_dest, razao_dest, valor_total,
    c_stat, x_motivo, n_prot,
    xml_nfe,          ← signed XML without protocol
    xml_protocolo,    ← complete nfeProc (5-year archiving requirement)
    tp_amb,           ← 1=production / 2=staging
    dh_recbto,        ← SEFAZ receipt timestamp
    data_emissao
}
```

### 8.2 nfe_log (dual audit layer)

Two distinct records are created per issuance:

| Event type          | Created by               | empresa_id  | usuario                                      |
|---------------------|--------------------------|-------------|----------------------------------------------|
| `ENVIO_NFE`         | `NfeTransmitServiceImpl` | NULL        | Authenticated user (`SecurityContextHolder`) |
| `TRANSMISSAO_SEFAZ` | `NfeGeracaoService`      | Company ID  | NULL                                         |

> **Design note:** the dual layer is intentional and is a direct consequence of DA-01 (section 15). `NfeTransmitServiceImpl` (fiscal module) has access to `SecurityContextHolder` but not to `Empresa`. `NfeGeracaoService` (web module) has access to `Empresa` but is not in the same call stack as the transmission.

### 8.3 Log failure guarantees

Persistence failures in `nfe_log` never interrupt the fiscal flow:

```java
// NfeGeracaoService
try {
    nfeLogService.salvar(log);
} catch (Exception logEx) {
    log.error("[NfeGeracao] Falha ao persistir nfe_log | chave={} | erro={}",
              chave, logEx.getMessage());
}
```

The same protection exists in `NfeTransmitServiceImpl.salvarLogSeguro()`.

---

## 9. MULTI-COMPANY (MULTITENANCY)

### 9.1 Isolation model

```
db_user.empresa_id  → company to which the user belongs
produto.empresa_id  → products isolated per company
pedido.empresa_id   → orders isolated per company
nfe_log.empresa_id  → audit segmented per company
```

### 9.2 Context propagation via JWT

```
Login (POST /auth/login)
    │
    │  AuthService.authenticate()
    │      └─ dbUserMapper.findByEmail(username)  ← username field is the user's email
    │      └─ DbUser.empresaId → embedded in token as claim "eid"
    │      └─ jwtUtil.generateToken(email, empresaId)
    │               JWT payload: { "sub": "email@company.com", "eid": 1, "exp": ... }
    ▼
Every authenticated request
    │
    │  JwtFilter.doFilterInternal()
    │      └─ jwtUtil.extractEmpresaId(token) → Long (claim "eid")
    │      └─ EmpresaContextHolder.set(empresaId)  ← ThreadLocal
    │      finally: EmpresaContextHolder.clear()   ← no leakage between requests
    ▼
Controllers
    │
    │  Long empresaId = EmpresaContextHolder.get()
    │  → produto.setEmpresaId(empresaId)
    │  → pedido.setEmpresaId(empresaId)
    │  → listarPorEmpresa(empresaId)  vs  listarTodos()
```

### 9.3 Compatibility fallback

When `empresaId == null` (user without an associated company, or dev environment without JWT):

- `listarTodos()` is used instead of `listarPorEmpresa()`
- `EmitenteProperties` is used as the issuer instead of `Empresa`
- Legacy data (before V017) is backfilled to the default company in `StartupListener`

### 9.4 Seed and backfill on startup

`StartupListener` (`@PostConstruct`) runs at initialization:

1. **Default company seed** — reads `fiscal.emitente.*` from `application.properties` and creates `empresa` if none exists
2. **Backfill** — updates `empresa_id` in `db_user`, `produto`, and `pedido` where `empresa_id IS NULL`
3. **Admin seed** — creates `admin` user with `role='ADMIN'` if `db_user` is empty

### 9.5 Multi-CNPJ OMS (V028)

The V028 model extends multi-company support to external integrators with multiple issuer CNPJs. An OMS client (`codigoEmpresaOms`) can authorize multiple CNPJs under the **same JWT token**.

#### Authorization flow

```
POST /api/integration/fiscal-authorizations
X-Api-Key: {technical-key}
Body: { codigoEmpresaOms, cnpj, certBase64, certSenha }
    │
    ▼
OmsFiscalAuthorizationService.autorizar()
    │  ├─ Validates X-Api-Key (SHA-256 → oms_api_key)
    │  ├─ Decodes and loads PKCS12
    │  ├─ Extracts X509Certificate, validates expiry and Subject CNPJ
    │  ├─ Locates or creates empresa from X.509 Subject (auto-creation)
    │  ├─ Looks up slot in oms_fiscal_authorization by (integrator_id, codigo_oms)
    │  │
    │  ├─ CASE A — no slot → INSERT auth + cert; generate new JTI; emitidoEm=now().truncatedTo(SECONDS)
    │  ├─ CASE B — same CNPJ, same thumbprint → no change; returns existing token
    │  ├─ CASE C — same CNPJ, different thumbprint → deactivates old cert; INSERT new cert; updates tokenExpiraEm; keeps JTI
    │  └─ CASE D — new CNPJ → INSERT cert; keeps JTI and token intact
    │
    └─ jwtUtil.generateOmsToken(codigoOms, empresaId_anchor, jti, tokenExpiraEm, emitidoEm)
         payload: { "sub": codigoOms, "eid": empresaId_anchor, "jti": uuid, "tipo": "OMS", "iat": emitidoEm, "exp": tokenExpiraEm }
```

#### Deterministic token

The JWT `iat` is always `auth.getEmitidoEm()` — stored in the database with second-level precision. This ensures that in scenarios B/C/D, where the JTI is reused, the generated token string is **identical** to the original. Without this guarantee, millisecond differences between `new Date()` and `CURRENT_TIMESTAMP` would produce different tokens on each call.

#### CNPJ resolution at issuance time

In `POST /api/app/pedidos`, the `cnpjEmitente` field selects which OMS certificate to use:

```
PedidoController.criar()
    │  ├─ extracts jti from JWT (claim "jti")
    │  ├─ validates cnpjAutorizadoParaJti(jti, cnpjEmitente) — fail-fast HTTP 403
    │  └─ persists order with cnpjEmitente in the snapshot

PedidoEmissaoService.emitir()
    │  └─ OmsCertificadoService.resolverPorJtiECnpj(jti, cnpjEmitente)
              └─ buscarAtivoPorAuthIdECnpj(authId, cnpjEmitente) → CertificadoContexto
```

#### Separation of responsibilities: fiscal empresa vs. catalog empresa (DA-08)

The multi-CNPJ flow introduces two distinct "empresa" concepts that **must not be confused**:

| Concept | Source | Used for |
|---|---|---|
| **Fiscal empresa (issuer)** | `empresaMapper.buscarPorCnpj(cnpjEmitente)` | NF-e XML (`<emit>`), signing certificate |
| **Anchor empresa (order)** | `pedido.getEmpresaId()` (token's `eid` claim) | Stock, reservation, write-off, product lookup |

Products are registered using the OMS token, whose `eid` points to the anchor empresa (the OMS client's first authorized CNPJ). Therefore, all catalog and stock operations use `pedido.getEmpresaId()` — not the fiscal empresa's id resolved from the issuer CNPJ.

> **Bug fixed on 2026-06-30 (commit `117a447`):** `PedidoEmissaoService.emitir()` previously resolved `empresaId = empresa.getId()` where `empresa` was obtained via `buscarPorCnpj(cnpjEmitente)`. For the second CNPJ (fiscal empresa `id=2`), this caused `estoqueService.reservarItens()` to look up the product with `empresa_id=2` — but products were registered with `empresa_id=1` (anchor empresa). Result: `PRODUCT_NOT_FOUND`. The fix: `empresaId = pedido.getEmpresaId()` as the primary value for stock operations, with `empresa` (fiscal) used exclusively for XML generation and certificate selection.

### 9.6 Multi-CNPJ fiscal context in post-issuance events (2026-07-22 catch-up — see PT-BR section 9.6 for full detail)

Cancellation, CC-e (correction letter), status inquiry and numbering invalidation are **already implemented and covered by automated tests** — they are not future roadmap items. `FiscalContextoResolver` resolves empresa+certificate for these events always by `pedido.cnpjEmitente`, never by global configuration, with explicit failure (`DOCUMENTO_CNPJ_DIVERGENTE`, HTTP 422) instead of a silent fallback. **What remains pending is a real SEFAZ smoke test of these four events specifically in a multi-CNPJ context** — distinct from issuance, which has already been validated end-to-end for multi-CNPJ (below).

**Real multi-CNPJ issuance evidence (2026-07-22):** an issuance attempt using the default company associated with the OMS integrator's token reached SEFAZ and returned `cStat=209` ("invalid issuer state tax ID") — a real fiscal registration finding, handled correctly by the system (structured rejection, `errorCode: SEFAZ_REJECTED`, HTTP 422, no data improperly changed). A subsequent issuance for a second company linked to the same token (fiscal registration accepted by SEFAZ during that staging issuance) was authorized with `cStat=100` and `<modFrete>2</modFrete>` confirmed in the transmitted XML — proving that company/certificate resolution by `cnpjEmitente` works correctly without requiring a different token per issuer.

---

## 10. PER-COMPANY DIGITAL CERTIFICATE

### 10.1 Database columns

```sql
ALTER TABLE empresa
    ADD COLUMN cert_path  VARCHAR(255) NULL,   -- classpath or filesystem path
    ADD COLUMN cert_senha VARCHAR(255) NULL,   -- KeyStore password (encrypted)
    ADD COLUMN cert_tipo  VARCHAR(10)  NULL DEFAULT 'PKCS12';
```

### 10.2 CertificadoContexto record

Defines the credential contract between modules without exposing the `Empresa` entity to the fiscal module:

```java
// borurio-fiscal — JDK types only
public record CertificadoContexto(
    Long empresaId,
    PrivateKey privateKey,
    X509Certificate certificate,
    SSLContext sslContext
) {}
```

### 10.3 Certificate resolution flow

```
PedidoEmissaoService / NfeGeracaoService
    │
    │  empresaCertificadoService.resolverPorEmpresa(empresa)
    │
    ▼
EmpresaCertificadoService
    │  if empresa.certPath == null → Optional.empty()   ← fallback to global cert
    │  else → cache.computeIfAbsent(empresaId, ...)
    │              └─ carregarContexto(empresa)
    │                    ├─ CertSenhaEncryptor.decrypt(certSenha)
    │                    ├─ KeyStore.load(inputStream, password)
    │                    ├─ resolverAlias() → isKeyEntry()
    │                    ├─ PrivateKey + X509Certificate
    │                    └─ SSLContext TLSv1.2 (KeyManagerFactory)
    │
    └─ CertificadoContexto { empresaId, privateKey, cert, sslContext }
```

### 10.4 Certificate cache

The `ConcurrentHashMap<Long, CertificadoContexto>` cache persists for the container's lifetime. To invalidate (e.g., after a certificate update):

```
EmpresaCertificadoService.invalidar(empresaId)
```

> **Implemented:** `EmpresaController.atualizar()` calls `empresaCertificadoService.invalidar(id)` after persisting every update. The cache is automatically invalidated whenever company data is changed via `PUT /api/app/empresas/{id}`.

### 10.5 Certificate file resolution

`EmpresaCertificadoService` tries in order:

1. **Classpath** via `ClassPathResource(path)`
2. **Filesystem** via `new File(path)`

If neither finds the file, it throws `IllegalStateException`.

### 10.6 OMS Certificate — Loading by JTI and CNPJ (V028)

Complements company-based resolution (`resolverPorEmpresa`) with OMS CNPJ-based resolution:

```
OmsCertificadoService.resolverPorJtiECnpj(jti, cnpj)
    │  ├─ omsAuthMapper.buscarPorJti(jti)        → OmsFiscalAuthorization
    │  ├─ verifies revogado_em == null
    │  ├─ omsCertMapper.buscarAtivoPorAuthIdECnpj(authId, cnpj) → OmsCompanyCertificate
    │  └─ carregarContexto(certRow.getEmpresaId(), certRow)
              ├─ encryptor.decryptBytes(certPfxEnc) → PFX bytes
              ├─ encryptor.decrypt(certSenhaEnc)    → password
              ├─ KeyStore.load(pfxBytes, password)
              └─ CertificadoContexto { empresaId, privateKey, cert, sslContext }
```

OMS certificates are not cached — revocation is checked on every issuance.

---

## 11. SECURITY FOUNDATION

### 11.1 JWT Authentication

- Algorithm: HMAC-SHA256
- Payload: `{ "sub": email, "eid": empresaId, "iat": ..., "exp": ... }`
- Expiration configurable via `security.jwt.expiration-ms` (default: 3600000ms = 1 hour)
- Secret key via `security.jwt.secret` (Base64 or raw string, ≥ 32 bytes)
- Filter: `JwtFilter extends OncePerRequestFilter` — extracts token from `Authorization: Bearer <token>` header, populates `EmpresaContextHolder`, clears in `finally`

> **Note:** the `username` field in the login body (`POST /auth/login`) is semantically an email — `AuthService` calls `dbUserMapper.findByEmail(username)` internally.

### 11.2 Access control (RBAC)

| Role | Value in `db_user.role` | Permissions |
|---|---|---|
| Administrator | `ADMIN` | All operations, including creating/updating companies and managing users |
| Operator | `OPERADOR` | Business operations (products, orders, fiscal issuance) |

Restrictions applied in `SecurityConfig` (Sprint 3 — validated in HOM 2026-05-12):

```java
// ADMIN-only routes
.requestMatchers(new AntPathRequestMatcher("/api/app/empresas", "POST")).hasRole("ADMIN")
.requestMatchers(new AntPathRequestMatcher("/api/app/empresas/**", "PUT")).hasRole("ADMIN")
.requestMatchers(new AntPathRequestMatcher("/api/app/usuarios")).hasRole("ADMIN")
.requestMatchers(new AntPathRequestMatcher("/api/app/usuarios/**")).hasRole("ADMIN")

// Everything else requires authentication
.anyRequest().authenticated()
```

> **Important:** `/api/app/usuarios` (without trailing slash) and `/api/app/usuarios/**` are distinct matchers — both are necessary, since `AntPathRequestMatcher("/api/app/usuarios/**")` does not match the bare root path.

### 11.3 Public endpoints (no authentication)

The following paths are permitted by `SecurityConfig` and skipped by `JwtFilter`:

| Path                                   | Note                                                               |
|----------------------------------------|--------------------------------------------------------------------|
| `/auth/**`                             | Login and authentication operations                                |
| `/api/test/**`                         | Health check — `GET /api/test/ping`                                |
| `/api/fiscal/nfe/test/**`              | Internal fiscal engine tests                                       |
| `/api/integration/**`                  | OMS fiscal authorization — authenticated by `X-Api-Key`, not JWT  |
| `/swagger-ui/**`, `/swagger-ui.html`   | Swagger documentation                                              |
| `/v3/api-docs/**`, `/v3/api-docs.yaml` | OpenAPI specification                                              |
| `/ping`                                | No controller mapped — do not use                                  |

> **Operational note:** `/ping` is listed in `permitAll` and in `JwtFilter.PUBLIC_EXACT`, but no controller maps this path. The correct health check endpoint is `GET /api/test/ping`.

### 11.4 Security error responses

Authentication and authorization errors are handled directly by Spring Security (before `GlobalExceptionHandler`) and have their own structure:

```json
{ "code": 401, "message": "Autenticação necessária", "success": false }
{ "code": 403, "message": "Acesso negado",           "success": false }
```

All other application errors return the standard `Result<>` envelope with `"data": null`. See `GlobalExceptionHandler` for the complete mapping.

### 11.5 cert_senha encryption (AES-256-GCM)

**Format stored in the database:**

```
ENC(<base64(iv_12bytes + ciphertext)>)
```

**Configuration:**

```bash
# Generate AES-256 key (32 bytes, Base64)
openssl rand -base64 32

# Environment variable in container
CERT_ENCRYPTION_KEY=<openssl output>

# application.properties
cert.encryption.key=${CERT_ENCRYPTION_KEY:}
```

**Passthrough mode:** if `CERT_ENCRYPTION_KEY` is not configured, `CertSenhaEncryptor` operates transparently (no encryption) and emits `WARN` in the logs. Suitable for local development and HOM without a certificate configured in the database.

**Graceful migration:** values without the `ENC(` prefix are treated as plaintext by `decrypt()`, enabling incremental migration.

### 11.6 XML protections (anti-XXE)

All XML parsers in the system are configured with:

```java
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
```

---

### 11.7 OMS Authorization Revocation and Rotation (Gate 7H)

Until 2026-07-22, `JwtFilter` only validated the JWT signature and expiry — a revoked OMS authorization remained accepted on any request until the token naturally expired (long TTL, by design). As of commit `4a39a88`, `JwtFilter` checks the active authorization in the database on every OMS request: revocation takes effect immediately, independent of token expiry.

**Admin endpoints** (`/api/admin/oms-authorizations/{id}/revogar` and `/rotacionar`, `ROLE_ADMIN`, outside the OMS public contract):

- **Revocation:** idempotent, always HTTP 200; marks the authorization as revoked and creates a `REVOGACAO` audit event.
- **Rotation:** issues a new JWT (new JTI) for the same authorization, invalidating the previous JTI; requires `expectedVersion` (optimistic concurrency — `AUTHORIZATION_CHANGED` if the version doesn't match) and allows reactivating a revoked authorization when the version matches the post-revocation version; creates a `ROTACAO` audit event.
- **Mandatory `Idempotency-Key` (UUID)** on both — replaying the same key returns the same result without reprocessing (same token, same version, no additional audit entry); `IDEMPOTENCY_KEY_CONFLICT` if the key was already used for another authorization/operation.
- **Append-only audit trail** (`oms_fiscal_authorization_audit`, V032): one event per operation, with previous/new version, `Idempotency-Key`, `requestId` and reason (`motivoCodigo`/`motivoDetalhe`) — **the JWT is never stored**, only operation metadata.
- Admin responses always carry `Cache-Control: no-store` and `Pragma: no-cache` — the body contains a JWT and must never be cached by an intermediate proxy/browser.

**Validated in HOM/SP (2026-07-22):** an isolated technical authorization (test environment, unrelated to the real integrator) went through two chained rotations plus a revocation, including idempotent replay of each operation — final version and audit event count match exactly the number of real operations (replays did not duplicate). The real OMS integrator's authorization went through one rotation (initial version `0` → final version `1`), one `ROTACAO` audit event, confirmed idempotent replay with no new audit entry, and the new token verified functional on protected endpoints; JTI changed (confirmed by internal comparison, never displayed).

---

## 12. POST-ISSUANCE FISCAL OPERATIONS

### 12.1 Status query (consSitNFe)

```
GET /api/app/pedidos/{id}/situacao
    │
    └─ PedidoOperacaoService.consultarSituacao(id)
```

Requires: order must have `chaveNfe` set (any state other than `RASCUNHO`). Returns HTTP 422 otherwise.

**Response structure** (`data` of the `Result<Object>` envelope):

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

| Field                                      | Presence    | Source                                        |
|--------------------------------------------|-------------|-----------------------------------------------|
| `pedidoId`, `numero`, `status`, `chaveNfe` | Always      | Local database (`pedidos` table)              |
| `cStat`, `xMotivo`, `nProt`, `dhRecbto`    | Conditional | `nfe_documento` table (if exists for the key) |
| `consultaSefaz`                            | Always      | Live `consSitNFe` call to SEFAZ in real time  |

### 12.2 Cancellation (event 110111)

```
POST /api/app/pedidos/{id}/cancelar
Body: { "justificativa": "at least 15 characters" }
```

Conditions validated by `PedidoOperacaoService.cancelar()`:
1. `status == "AUTORIZADO"` — throws `IllegalStateException` (HTTP 422) for any other status
2. `nfe_documento` exists for the key with `nProt` set — throws `IllegalStateException` (HTTP 422) if absent

The service builds the cancellation event XML, signs it with `AssinaturaXmlService.assinarEvento()`, transmits to the SEFAZ event endpoint, and updates the order status to `"CANCELADO"`.

Response: raw SEFAZ XML in `data` of the `Result<String>` envelope.

### 12.3 Electronic Correction Letter (CC-e, event 110110)

```
POST /api/app/pedidos/{id}/cce
Body: { "correcao": "at least 15 characters" }
```

Condition: `status == "AUTORIZADO"` — throws `IllegalStateException` (HTTP 422) otherwise. The CC-e does not change the order status. SEFAZ limit: 20 CC-e per NF-e access key.

Response: raw SEFAZ XML in `data` of the `Result<String>` envelope.

### 12.4 DANFE — Auxiliary Document of the Electronic Invoice

```
GET /api/fiscal/nfe/{chave}/danfe
```

Returns the DANFE PDF for the given access key. JWT authentication required (any role).

**Success response:**
- HTTP 200
- `Content-Type: application/pdf`
- `Content-Disposition: attachment; filename="danfe-{chave}.pdf"`
- Body: PDF bytes

**Error responses:**

| Code | Cause                                                           |
|------|-----------------------------------------------------------------|
| 400  | Access key length is not 44 digits                              |
| 401  | Missing or invalid JWT token                                    |
| 404  | NF-e not found in `nfe_documento` table                         |
| 422  | NF-e XML not yet available (issuance not processed)             |
| 500  | Internal error during PDF generation                            |

**DANFE architecture:**

| Class                     | Module            | Responsibility                                                      |
|---------------------------|-------------------|---------------------------------------------------------------------|
| `DanfeXmlParser`          | `borurio-fiscal`  | Extracts fields from signed NF-e XML via XPath + `nfe:` namespace   |
| `DanfePdfGenerator`       | `borurio-fiscal`  | Generates A4 PDF with barcode128, items table, HOM watermark        |
| `DanfeService`            | `borurio-fiscal`  | Interface; loads `NfeDocumento` and orchestrates parser + generator |
| `DanfeServiceImpl`        | `borurio-fiscal`  | Implementation; fetches XML by access key, formats dhRecbto         |
| `DanfeController`         | `borurio-web`     | REST controller `GET /api/fiscal/nfe/{chave}/danfe`                 |

**Legal requirement — watermark:**
- When `tpAmb=2` (staging/HOM), the DANFE displays a diagonal "SEM VALOR FISCAL" watermark in light gray.
- Implemented via `PdfPageEventHelper.onEndPage()` (OpenPDF 1.3.30, LGPL).

**Bug fixes applied on 2026-05-20:**

| Bug fixed | Solution |
|---|---|
| `static final DecimalFormat` — not thread-safe in Spring singleton | `dfMoeda()` / `dfQtde()` return a new instance per call |
| Totals formatted via `BigDecimal.toString()` — ignored pt_BR Locale | Routed through `formatDecimal()` with `dfMoeda()` — result: `R$ 91,80` |
| Fixed protocol label regardless of `cStat` | Conditional: `PROTOCOLO DE AUTORIZAÇÃO DE USO` (cStat=100 + nProt present); `RETORNO SEFAZ — HOMOLOGAÇÃO` (tpAmb=2, cStat≠100); `PROTOCOLO NÃO DISPONÍVEL` (other cases) |

### 12.5 Recipient Manifestation (events 210200 / 210210 / 210220 / 210240)

Recipient Manifestation allows the **recipient** of an NF-e to declare their position on a note issued against their CNPJ to SEFAZ. It is governed by NT 2012.004 and must always be sent to the **Ambiente Nacional (AN)** — independent of the issuer's state.

**Endpoint:**

```
POST /api/fiscal/nfe/manifestar
Authorization: Bearer {token}
Content-Type: application/json
```

**Request body:**

```json
{
  "chaveNfe":         "44-digit access key",
  "tipoEvento":       "210200 | 210210 | 210220 | 210240",
  "cnpjDestinatario": "14 digits, no formatting",
  "xJust":            "required only for event 210240 (min 15 / max 255 characters)"
}
```

**Supported events:**

| Code   | Name                          | xJust required |
|--------|-------------------------------|----------------|
| 210200 | Ciência da Operação           | No             |
| 210210 | Confirmação da Operação       | No             |
| 210220 | Desconhecimento da Operação   | No             |
| 210240 | Operação Não Realizada        | Yes            |

**Architectural rules:**

| Rule                     | Value / Explanation                                                                                |
|--------------------------|----------------------------------------------------------------------------------------------------|
| `cOrgao`                 | Always `91` (AN — Ambiente Nacional). NT 2012.004 is mandatory regardless of issuer's UF.         |
| URL                      | `sefazProperties.getManifestacaoEvento()` — distinct from `recepcaoEvento` (SP). Configured per profile in `application-*.yml`. |
| CNPJ in XML              | `cnpjDestinatario` from request body — always the recipient, not the issuer.                      |
| `nSeqEvento`             | Fixed `"01"` — one event per NF-e per transmission.                                               |
| `idEvento` format        | `"ID" + tpEvento(6) + chNFe(44) + nSeqEvento(2)` — 54 characters total, consistent with cancellation (`"ID110111"`) and CC-e (`"ID110110"`). |
| Digital signature        | RSA-SHA1 on `infEvento`, inserted into `evento` — same `AssinaturaXmlService.assinarEvento()` used by cancellation and CC-e. |

**cStat validation in response:**

| cStat (lote) | cStat (evento) | Outcome                           |
|--------------|----------------|-----------------------------------|
| 128          | 135            | Success — event registered        |
| 128          | 136            | Success — event already registered (idempotent) |
| ≠ 128        | —              | Exception — lote rejected         |
| 128          | ≠ 135/136      | Exception — evento rejected       |

**HOM infrastructure limitation:**

The AN HOM endpoint (`hom.nfe.fazenda.gov.br`) returns **HTTP 403** for requests from local/residential IPs. The SEFAZ federal network blocks direct access from non-corporate environments.

- This is a network-level restriction — **not a code or certificate issue**.
- The XML structure, `cOrgao=91`, signature, and URL are all architecturally correct.
- In a corporate network or in PRD, the AN endpoint will be reachable.
- HOM smoke test confirmed the XML is correctly formed; the 403 prevents SEFAZ from processing it in this environment.

**Class map:**

| Class                          | Module            | Responsibility                                                            |
|--------------------------------|-------------------|---------------------------------------------------------------------------|
| `NfeManifestacaoRequest`       | `borurio-fiscal`  | DTO: chaveNfe, tipoEvento, cnpjDestinatario, xJust                        |
| `NfeManifestacaoService`       | `borurio-fiscal`  | Interface: `manifestar(NfeManifestacaoRequest) throws Exception`          |
| `NfeManifestacaoServiceImpl`   | `borurio-fiscal`  | Builds XML, signs, sends SOAP to AN, validates cStat, logs to `nfe_log`   |
| `NfeManifestacaoController`    | `borurio-web`     | `POST /api/fiscal/nfe/manifestar` — validates fields, delegates to service |

---

## 13. cStat=225 INVESTIGATION HISTORY AND SEFAZ-SP STAGING STATUS

> **CURRENT STATUS (2026-07-14): RESOLVED.** The root cause of cStat=225 was identified and fixed on this date. The fiscal engine obtained `cStat=100` ("Autorizado o uso da NF-e") in HOM/SP for an emitting company with fiscal registration accepted by SEFAZ during the staging issuance, both in an internal test and in a test run by the Chinese integrator via the OMS, cross-confirmed on the public portal hom.nfe.fazenda.gov.br. This section documents the full investigation history (~10 weeks) for traceability — including the initial hypothesis, which was incorrect — and the current status per company registration situation.

### 13.1 cStat=225 — "Rejeição: Falha no Schema XML do lote de NFe" (RESOLVED 2026-07-14)

**Real root cause — signature algorithm mismatched against the official schema (identified and fixed on 2026-07-14):**

The engine was signing the XML with RSA-SHA256/SHA-256. The SEFAZ's current official XMLDSig schema (`xmldsig-core-schema_v1.01.xsd`), confirmed in the official `PL_010e_v1.02` package downloaded directly from nfe.fazenda.gov.br (current version, published 2026-07-10), defines the `Algorithm` attributes of `SignatureMethod` and `DigestMethod` with `fixed` — i.e., a single accepted value, no alternative: `http://www.w3.org/2000/09/xmldsig#rsa-sha1` and `http://www.w3.org/2000/09/xmldsig#sha1`. SHA-1 is what the official schema requires to this day, not SHA-256.

The local XSD file used for pre-submission validation (`xsd/oficial/xmldsig-core-schema_v1.01.xsd`) was out of sync with the official one: the `fixed` restrictions had been removed, so local validation accepted any algorithm and "passed" even with the wrong signature algorithm. This masked the problem throughout the earlier investigation — local validation never flagged the mismatch that SEFAZ was rejecting.

**Fix applied:** `AssinaturaXmlService` changed to sign with RSA-SHA1/SHA-1 (`SignatureMethod`/`DigestMethod`). The semantic content of the XMLDSig schema used was aligned with the official `PL_010e_v1.02` package, preserving the official validation restrictions for RSA-SHA1/SHA-1. The local file has non-functional documentation/formatting differences from the original file.

**Additional causes fixed in the same investigation (2026-07-14):** after fixing the signature, more real issues were found and fixed in cascade until full authorization was achieved:

| # | Issue | Fix |
|---|---|---|
| 1 | Missing `indIntermed` group in `<ide>` — required by the current layout | Field added to `Ide` and `NfeXmlBuilder`; filled with `"0"` (direct sale, no intermediary/marketplace) |
| 2 | `indFinal` hardcoded to `"0"` regardless of recipient type | A provisional `indFinal` adjustment was applied during staging, initially inferred from the recipient's document type (CPF → `1`, CNPJ → `0`). A subsequent audit found that this inference does not correctly represent all fiscal scenarios, since `indFinal` depends on the nature of the operation. The current architectural decision is that the value should be explicitly provided by the OMS. **Status: pending final definition in the OMS contract.** |
| 3 | Missing/invalid emitter state tax ID (IE) in the registration | Currently qualified emitting company in HOM: state tax ID accepted by SEFAZ for that HOM issuance, corrected in the registration. A second company tested remains with its state tax ID revoked for inactivity since 2024 — a registration issue external to the company; the fiscal registration must be confirmed and, if necessary, corrected by the company's fiscal representative (see 13.3) |
| 4 | Payload requirements (on the request sent by the OMS) | Correct CFOP per destination state; complete recipient address with IBGE municipality code; standard staging-environment text in the recipient name |

**Result:** `cStat=100` (Autorizado o uso da NF-e) obtained in HOM/SP for the first time in the project's history, for the emitting company with fiscal registration accepted by SEFAZ during the staging issuance, in an internal test and in a cross-test run by the Chinese integrator (CC/Xiao Li) via the OMS, cross-confirmed on the public national portal (hom.nfe.fazenda.gov.br).

**Impact in HOM/PRD:** since v1.9, any `cStat≥200` results in `REJEITADO` and `POST /emitir` returns HTTP 422 `SEFAZ_REJECTED` with `data.cStat`/`data.xMotivo`. With the 2026-07-14 fix, the issuance flow for companies with fiscal registration accepted by SEFAZ now returns `cStat=100` (`AUTORIZADO`) instead of `cStat=225` (`REJEITADO`).

> **Historical record — previous, incorrect hypothesis:** between 2026-05-08 and 2026-07-13, the working hypothesis was that `cStat=225` was an infrastructure limitation of the SEFAZ-SP individual authorization processor (`verAplic=SP_NFE_PL_008i2`), treated as a legacy/orphan component that still required SHA-1 while the rest of the country used SHA-256 (allegedly correct per NT 2019.001), and therefore not a system bug. That hypothesis was **wrong**: SHA-1 is exactly what the official schema requires to this day (confirmed in the most current version of the schema package, published 2026-07-10), and the bug was on Borurio's side. The original technical observations that led to this hypothesis (the `retEnviNFe.cStat=104` / `protNFe.cStat=225` pattern, the two distinct processors `PL009`/`PL_008i2`) remain true as field observations — it is the interpretation of the cause that was incorrect. This record is kept for traceability; the dated technical reports under `docs/report/` document the state of knowledge on each day of the investigation and were not altered.

### 13.2 Real data bug fixed on 2026-07-10 — incomplete emitter registration

During the investigation, a second, distinct, real cause for the same `cStat=225`/same `xMotivo` was identified and fixed on 2026-07-10: the emitter company (auto-created via OMS authorization, which only receives CNPJ/razão social/UF from the A1 certificate — see the multi-CNPJ section) had no address (`logradouro`/`numero`/`bairro`/`codigoMunicipio`/`municipio`/`cep` all missing), producing an incomplete `enderEmit` block in the XML.

**Fix:** address completed via `PUT /api/app/empresas/{id}` (or automatically since v1.9, via `emit*` fields in `POST /api/app/pedidos` — see section 4). Additionally, `NfeGeracaoService.validarEnderecoEmitente()` (v1.9) intercepts this case BEFORE building the XML and calling SEFAZ, returning `EMITTER_ADDRESS_INCOMPLETE` (422) instead of letting SEFAZ reject it on schema grounds. This bug is independent from the signature issue described in 13.1 and remains fixed.

### 13.3 Current status per emitting company registration situation (2026-07-14)

| Situation | cStat obtained |
|---|---|
| Emitting company with fiscal registration accepted by SEFAZ during the staging issuance | `cStat=100` — AUTORIZADO, functional in HOM |
| Emitting company with state tax ID revoked for inactivity | Blocked — registration issue external to the company with SEFAZ, **not a system bug**; the fiscal registration must be confirmed and, if necessary, corrected by the company's fiscal representative. This company must not be used as a production certificate reference |

### 13.4 Certificate cache invalidation

The `EmpresaCertificadoService` cache is automatically invalidated by `EmpresaController.atualizar()` whenever company data is updated via `PUT /api/app/empresas/{id}`. See section 10.4.

---

## 14. OPERATIONAL CHECKLISTS

### 14.1 HOM deployment checklist

```
□ 1. Build: mvn package -DskipTests -q
       └─ Verify: BUILD SUCCESS

□ 2. Copy JAR to the correct container:
       docker cp borurio-web/target/borurio-web-1.0.0.jar \
                 borurio-web-hom:/app/app.jar
       └─ Note: the internal JAR path is /app/app.jar (not /app/borurio-web.jar)

□ 3. Restart:
       docker restart borurio-web-hom

□ 4. Wait for healthcheck:
       docker inspect borurio-web-hom --format "{{.State.Health.Status}}"
       └─ Wait for: healthy

□ 5. Check Flyway in logs:
       docker logs borurio-web-hom --since "..." 2>&1 | grep -i "migrat\|flyway"
       └─ Verify: "Successfully applied N migration(s)" or "is up to date"

□ 6. Login smoke test:
       POST http://localhost:8081/auth/login
       {"username":"admin@company.com","password":"<password>"}
       └─ Verify: HTTP 200, token present

□ 7. Actuator smoke test:
       GET http://localhost:8081/actuator/health
       └─ Verify: {"status":"UP"}

□ 8. API smoke test:
       GET http://localhost:8081/api/test/ping
       └─ Verify: HTTP 200, status="UP"
```

### 14.2 NF-e staging checklist

```
□ 1. Company configured with CNPJ, razaoSocial, UF, IE, CRT
□ 2. A1 PKCS12 certificate available at /app/certificados/pfx/
□ 3. empresa.cert_path pointing to the certificate file
□ 4. sefaz.tpAmb=2 in application.properties (staging)
□ 5. Product registered with NCM (8 digits), CFOP, origem, csosn
□ 6. Order created with valid destCnpjCpf/destRazaoSocial
□ 7. POST /{id}/emitir → HTTP 200, data.soapRetorno not empty
□ 8. Verify chaveNfe: 44 digits (confirms SEFAZ accepted the batch)
□ 9. `cStat=100` expected for a company with fiscal registration accepted by SEFAZ during the staging issuance; `cStat≥200` returns HTTP 422 `SEFAZ_REJECTED` — inspect `data.cStat`/`data.xMotivo` (see section 13)
□ 10. Verify nfe_documento in DB: c_stat, x_motivo, n_prot
□ 11. Verify nfe_log: empresa_id, usuario populated
```

### 14.3 New company validation checklist

```
□ 1. POST /api/app/empresas (requires ADMIN role)
       Body: { cnpj, razaoSocial, uf, ie, crt, logradouro, ... }
□ 2. Verify company created: GET /api/app/empresas/{id}
□ 3. Configure certificate: PUT /api/app/empresas/{id}
       Body: { certPath: "/app/certificados/pfx/company-X.pfx",
               certSenha: "<your_certificate_password>",
               certTipo: "PKCS12" }
□ 4. Test issuance with user whose empresa_id == new company id
□ 5. Check log: [EmpresaCert] Certificado OK | empresaId=X | alias=...
```

### 14.4 End-to-end integration smoke test

Run with Postman collection (`docs/postman/borurio-erp-collection.json`) or curl sequence in HOM (`http://localhost:8081`). See `INTEGRATION_CONTRACT_EN.md` for full payloads.

```
□ 1. GET  /api/test/ping                     → HTTP 200, status="UP"
□ 2. POST /auth/login                        → HTTP 200, token not null
□ 3. POST /api/app/produtos                  → HTTP 200, data.id returned
□ 4. GET  /api/app/produtos?page=0&size=5    → HTTP 200, totalElements ≥ 1
□ 5. POST /api/app/pedidos                   → HTTP 200, data.status="RASCUNHO"
□ 6. POST /api/app/pedidos/{id}/emitir       → HTTP 200, soapRetorno not empty
□ 7. GET  /api/app/pedidos/{id}/situacao     → HTTP 200, chaveNfe populated
□ 8. GET  /api/app/pedidos/{id}              → HTTP 200, itens with snapshot fields

Security checks:
□ 9.  GET  /api/app/pedidos without token    → HTTP 401, success=false
□ 10. GET  /api/app/usuarios with OPERADOR token → HTTP 403, success=false
□ 11. GET  /api/app/pedidos company B token  → HTTP 200, content=[] (tenant isolation)
```

---

## 15. ARCHITECTURE DECISIONS

### DA-01: borurio-fiscal does not import borurio-app

**Decision:** the fiscal module has no compile-time dependency on the business module.

**Motivation:** the fiscal engine must be reusable and testable independently of the business data model. Certificate and credentials are passed via `CertificadoContexto` (pure JDK types).

**Consequence:** all integration between the two domains occurs in the `borurio-web` module, which is the sole coupling point.

---

### DA-02: Immutable fiscal snapshot in pedido_item

**Decision:** when the order is created, the product's fiscal data (NCM, CFOP, CSOSN, origem, unidade) are copied to `pedido_item` and never updated again.

**Motivation:** Brazilian fiscal legislation requires that the NF-e reflect the data at the time of sale. Subsequent product catalog changes must not retroactively modify existing orders.

**Consequence:** issuance always uses data from `pedido_item`. The service does not re-fetch the product at issuance time.

---

### DA-03: Dual audit layer in nfe_log

**Decision:** two distinct records are created per issuance (`ENVIO_NFE` + `TRANSMISSAO_SEFAZ`).

**Motivation:** `NfeTransmitServiceImpl` (fiscal) has access to `SecurityContextHolder` but not to `Empresa`. `NfeGeracaoService` (web) has access to `Empresa` but is not in the same call stack as the transmission. The duplication is a direct consequence of DA-01.

---

### DA-04: ConcurrentHashMap for certificate cache (no TTL)

**Decision:** in-memory cache with no TTL. Explicit invalidation via `EmpresaCertificadoService.invalidar(empresaId)`.

**Motivation:** A1 certificates have a validity of 1 to 3 years. Reloading the KeyStore on every issuance has unnecessary cryptographic overhead.

**Resolution:** ✓ Automatic invalidation implemented — `EmpresaController.atualizar()` calls `empresaCertificadoService.invalidar(id)` after every `PUT /api/app/empresas/{id}`. The cache is cleared on every company update. No gap remains before production.

---

### DA-05: Direct SOAP without CXF/wsimport

**Decision:** the SOAP envelope is built manually as a string, without a generated WSDL client.

**Motivation:** SEFAZ blocks automated WSDL requests (HTTP 403 / HTTP/2). A locally generated WSDL at build time would be required, but the NF-e envelope is stable and well-documented by SEFAZ.

**Consequence:** changes to the SEFAZ SOAP protocol require manual updates to `NfeTransmitServiceImpl`.

---

### DA-06: CertSenhaEncryptor passthrough without key configured

**Decision:** without `CERT_ENCRYPTION_KEY`, the encryptor operates transparently (no encryption) with a `WARN` in the logs.

**Motivation:** allows the system to run in local development without secrets management infrastructure. Enabling encryption is a production requirement (Phase 11).

---

### DA-07: @ConditionalOnProperty in MyBatisConfig

**Decision:** `MyBatisConfig` uses `@ConditionalOnProperty("spring.datasource.url")` instead of `@ConditionalOnBean(DataSource.class)`.

**Motivation:** `@ConditionalOnBean` on a regular `@Configuration` class is evaluated before Spring Boot auto-configurations (including `DataSourceAutoConfiguration`), resulting in a condition that is always false in production — `@MapperScan` is never executed and MyBatis mappers are not registered. `@ConditionalOnProperty` evaluates the property, which is present in dev/hom YAMLs and absent in `@WebMvcTest` test contexts.

---

### DA-08: Separation of empresaId for stock vs. empresa for NF-e in the multi-CNPJ flow

**Decision:** in `PedidoEmissaoService.emitir()`, the `empresaId` used for stock operations is derived from `pedido.getEmpresaId()` (anchor empresa registered in the order at creation time), not from `empresa.getId()` where `empresa` is resolved from the `cnpjEmitente`.

**Motivation:** in the multi-CNPJ model, the product catalog belongs to the OMS client as a whole — not to each CNPJ individually. Products are registered via batch using the OMS token whose `eid` points to the anchor empresa. If stock were operated using the fiscal empresa for the issuing CNPJ (which may have a different `id` from the anchor), the lookup `buscarPorIdEEmpresa(produtoId, empresaFiscal.getId())` would return `null` because the product exists under `empresa_id = anchor.getId()`.

**Consequence:** the `empresa` variable in `PedidoEmissaoService` retains two distinct responsibilities: (1) issuer data in the NF-e XML and (2) OMS certificate selection by CNPJ — both correctly served by the fiscal empresa. Only the `empresaId` for stock is decoupled and pinned to the anchor empresa.

**Implemented:** commit `117a447` on 2026-06-30 — 126/126 tests passing.

---

## 16. ROADMAP TO PRODUCTION

### Phase 10 — Documentation and Swagger ✓ COMPLETED (2026-05-12)

| Item                                           | Status                                                  |
|------------------------------------------------|---------------------------------------------------------|
| Technical manual PT                            | ✓ This document (v2.3)                                  |
| Technical manual EN                            | ✓ `MTF-001_motor-fiscal-nfe_EN.md`                      |
| PT-BR integration contract                     | ✓ `INTEGRATION_CONTRACT_PT-BR.md`                       |
| EN integration contract                        | ✓ `INTEGRATION_CONTRACT_EN.md`                          |
| Annotated Swagger (10 tags, deprecated marked) | ✓ `SwaggerConfig.java` — Sprint 3                       |
| End-to-end Postman collection (49 requests)    | ✓ `docs/postman/borurio-erp-collection.json` — Sprint 3 |

### Phase 11 — PRD Deployment + CI/CD (pending)

| Item                                          | Priority     | Description                                                             |
|-----------------------------------------------|--------------|-------------------------------------------------------------------------|
| Controlled production test (real fiscal transaction, subject to cancellation, supervised by the accountant) | **CRITICAL — BLOCKING** | `cStat=100` in HOM (2026-07-14) does not replace this validation. Authorization in HOM confirms schema/signature compliance; it does not confirm production numbering/series, PRD certificate behavior (`tpAmb=1`), or the real cycle in front of the accountant. Unmet prerequisite for go-live — see section 13 |
| `CERT_ENCRYPTION_KEY` in PRD                  | **CRITICAL** | Generate via `openssl rand -base64 32`; inject via secrets manager      |
| PRD A1 certificates with real CNPJ            | **CRITICAL** | `tpAmb=1`; register company with `cert_path` pointing to PRD cert      |
| CI/CD pipeline                                | **MEDIUM**   | GitHub Actions: test → build → push image → deploy HOM → smoke test   |
| Monitoring                                    | **LOW**      | Prometheus + Loki                                                       |
| ~~Automatic certificate cache invalidation~~  | ~~HIGH~~     | ✓ Implemented — `EmpresaController.atualizar()` calls `invalidar()`   |
| ~~Rate limiting~~                             | ~~MEDIUM~~   | ✓ Implemented — `RateLimitInterceptor` (10 req/min login, 30 emitir)  |
| ~~`nfe_log` retention policy~~                | ~~LOW~~      | ✓ Implemented — `NfeLogRetencaoScheduler` + `@EnableScheduling`       |

### Phase 12-B — DANFE ✓ COMPLETED (2026-05-18)

| Item                                                                   | Status                                            |
|------------------------------------------------------------------------|---------------------------------------------------|
| `DanfeXmlParser` — XPath + `nfe:` namespace over signed XML            | ✓ `borurio-fiscal/danfe/` — 2026-05-18            |
| `DanfePdfGenerator` — OpenPDF 1.3.30, barcode128, watermark in staging | ✓ `borurio-fiscal/danfe/` — 2026-05-18            |
| `DanfeService` + `DanfeServiceImpl`                                    | ✓ `borurio-fiscal/danfe/` — 2026-05-18            |
| `DanfeController` `GET /api/fiscal/nfe/{chave}/danfe`                  | ✓ `borurio-web/controller/fiscal/` — 2026-05-18   |
| `DanfeControllerTest` — 5 scenarios covered                            | ✓ 66/66 tests passing — 2026-05-18                |

---

## 17. NORMATIVE REFERENCES

| Document                                   | Description                                            |
|--------------------------------------------|--------------------------------------------------------|
| AJUSTE SINIEF 07/2005 and amendments       | Establishes the Nota Fiscal Eletrônica                 |
| Manual de Orientação do Contribuinte (MOC) | Version 7.0 — NF-e 4.00 layout                         |
| Nota Técnica 2019.001                      | NF-e 4.00 layout update                                 |
| xmldsig-core-schema_v1.01.xsd (PL_010e_v1.02) | Current official XMLDSig schema — fixes `SignatureMethod`/`DigestMethod` to RSA-SHA1/SHA-1 |
| ABNT NBR ISO/IEC 27001                     | Information security management                        |
| XML-DSig W3C Recommendation                | `https://www.w3.org/TR/xmldsig-core/`                  |
| RFC 5652                                   | Cryptographic Message Syntax (base of PKCS#12)         |

---

*Document MTF-001 — version 2.9 — Borurio ERP Fiscal BR*
*Based on the state validated in HOM on 2026-05-11*  
*Last updated: 2026-07-14 (real root cause of cStat=225 fixed — RSA-SHA1/SHA-1 signature per current official schema, semantic content of the local XSD aligned with the official package; `indIntermed` added; provisional `indFinal` adjustment pending final definition in the OMS contract; emitter state tax ID corrected for the currently qualified emitting company; `cStat=100` obtained in HOM/SP for the first time in the project; section 13 rewritten with the full history)*
*Next revision: after a controlled production test (see section 16)*
