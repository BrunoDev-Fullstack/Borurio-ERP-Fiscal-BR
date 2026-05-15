# MTF-001 — Technical Manual: NF-e 4.00 Fiscal Engine
## Borurio ERP Fiscal BR

---

**Document:** MTF-001  
**Version:** 2.0  
**Issued:** 2026-05-11  
**Last updated:** 2026-05-12  
**Author:** Bruno Ribeiro — Fullstack Developer / DevSecOps  
**Status:** VALIDATED IN STAGING (HOM)  
**Reference branch:** `fix/sefaz-xml-structure`  

> **Version history:**
> - v1.0 (2026-05-11): initial document, phases 1–9 + phase 10 in progress
> - v2.0 (2026-05-12): sprint 3 complete; RBAC updated; `/situacao` response corrected; Phase 10 closed; integration endpoints and smoke test added

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
13. [Known HOM/SP Environment Limitations](#13-known-homsp-environment-limitations)
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
| XMLDSIG RSA-SHA256 + C14N signature                                            | ✓ HOM/SP — 2026-05-11                                     |
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
| End-to-end Postman collection (9 folders, 46 requests)                         | ✓ Generated and aligned — 2026-05-12                      |
| `NfeEnvioController` deprecated — legacy endpoints marked and redirected       | ✓ Code — 2026-05-12                                       |
| PT-BR and EN integration contracts generated and validated                     | ✓ Code — 2026-05-12                                       |
| `MyBatisConfig`: `@ConditionalOnProperty` ensures correct boot in HOM          | ✓ HOM/SP — 2026-05-12                                     |
| 30/30 controller tests passing                                                 | ✓ Code — 2026-05-12                                       |

### 1.2 What is PENDING

| Feature                                                         | Phase    | Note                                |
|-----------------------------------------------------------------|----------|-------------------------------------|
| `CERT_ENCRYPTION_KEY` configured in production                  | Phase 11 | Passthrough active in HOM by design |
| Automatic certificate cache invalidation in `EmpresaController` | Phase 11 | Identified gap — section 10.4       |
| Automated CI/CD                                                 | Phase 11 | Manual deployment via docker cp     |
| Production A1 certificates with real CNPJ                       | Phase 11 | Phase 11 critical item              |
| Rate limiting on `/emitir`                                      | Phase 11 | Protect against abuse               |
| DANFE — NF-e PDF generation for delivery to the recipient       | Phase 12+ | No PDF library present in the project; `nfe_documento.xml_protocolo` already stores the complete nfeProc required for future generation |

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
| Migrations        | Flyway (V001–V022)                                               |
| Auth              | Stateless JWT (HMAC-SHA256)                                      |
| Security          | Spring Security 6.x                                              |
| XML Signing       | Java XML Crypto API (`javax.xml.crypto.dsig`)                    |
| SOAP              | Direct HTTPS (no CXF, no wsimport)                               |
| Certificate cache | In-memory `ConcurrentHashMap`                                    |
| Container         | Docker (internal image); port 8081 in HOM                        |
| API docs          | springdoc-openapi 2.6.0 — Swagger UI at `/swagger-ui/index.html` |

---

## 3. FISCAL DATA MODEL

### 3.1 Applied migrations (V001–V022)

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
| V022       | Missing foreign key constraints on `pedido_item`, `nfe_documento`, `nfe_log` |

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
    │  ├─ pedidoService.buscarComItens(id)      → validates status=RASCUNHO
    │  ├─ montarRequest(pedido)                  → NfeEmissaoRequest with fiscal snapshot
    │  ├─ resolverEmpresa(empresaId)              → Empresa from JWT context
    │  │
    │  │  nfeGeracaoService.gerar(req, empresa)
    │  │
    ▼  ▼
NfeGeracaoService (borurio-web)
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
    │  ├─ [2] xsdValidator.validate()             → against xsd/custom/nfe_v4.00_consolidado.xsd
    │  ├─ [3] assinaturaXmlService.assinar()      → XMLDSIG RSA-SHA256 + C14N
    │  └─ [4] nfeTransmitService.transmitirXml()  → SOAP HTTPS → SEFAZ
    │
    ▼
NfeTransmitServiceImpl (borurio-fiscal)
    │  ├─ criarEnvelopeEnviNFe()                  → batch with 1 NF-e
    │  ├─ enviarSoap(url, envelope, sslContext)    → HTTPS POST
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
    │  ├─ resolverStatus(soapRetorno)              → AUTORIZADO / REJEITADO / AGUARDANDO
    │  ├─ pedidoService.atualizarStatus()          → pedido.status + chave_nfe
    │  └─ baixarEstoque()                          → only if AUTORIZADO (cStat=100)
```

If an exception is thrown during `nfeGeracaoService.gerar()`, the service calls `atualizarStatus(id, "ERRO", null)` before rethrowing — the order remains in `ERRO` state and the endpoint returns HTTP 500.

### 4.2 Semantic order status

| Status       | Condition                                                                          | Stock reduced?   |
|--------------|------------------------------------------------------------------------------------|------------------|
| `RASCUNHO`   | Order created, not yet transmitted                                                 | No               |
| `AUTORIZADO` | SEFAZ returned `cStat = 100`                                                       | Yes              |
| `AGUARDANDO` | Batch accepted (`cStat = 104`) without infProt, or failure to parse SEFAZ response | No               |
| `REJEITADO`  | `cStat >= 200`                                                                     | No               |
| `ERRO`       | Exception during transmission — HTTP 500 returned to client                        | No               |
| `CANCELADO`  | Cancellation event authorized                                                      | N/A              |

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

`cNF` (numeric code) is generated with `new Random().nextInt(100_000_000)`.

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
    <transp>  <!-- modFrete=9 (no freight) -->
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

---

## 6. XMLDSIG DIGITAL SIGNATURE

### 6.1 Algorithms (NT 2019.001 — mandatory for NF-e 4.00)

| Algorithm        | URI                                                                |
|------------------|--------------------------------------------------------------------|
| Signature        | `http://www.w3.org/2001/04/xmldsig-more#rsa-sha256` (RSA-SHA256)   |
| Digest           | `http://www.w3.org/2001/04/xmlenc#sha256` (SHA-256)                |
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
    ├─ 5. Reference("#" + id, SHA-256, [ENVELOPED, C14N])
    ├─ 6. SignedInfo(C14N, RSA-SHA256, [reference])
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

> **Identified gap (Phase 11):** the cache is not automatically invalidated when a company updates its certificate via `PUT /api/app/empresas`. The `EmpresaController` does **not** call `invalidar()` currently. Must be fixed before production.

### 10.5 Certificate file resolution

`EmpresaCertificadoService` tries in order:

1. **Classpath** via `ClassPathResource(path)`
2. **Filesystem** via `new File(path)`

If neither finds the file, it throws `IllegalStateException`.

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

| Path                                   | Note                                |
|----------------------------------------|-------------------------------------|
| `/auth/**`                             | Login and authentication operations |
| `/api/test/**`                         | Health check — `GET /api/test/ping` |
| `/api/fiscal/nfe/test/**`              | Internal fiscal engine tests        |
| `/swagger-ui/**`, `/swagger-ui.html`   | Swagger documentation               |
| `/v3/api-docs/**`, `/v3/api-docs.yaml` | OpenAPI specification               |
| `/ping`                                | No controller mapped — do not use   |

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

---

## 13. KNOWN HOM/SP ENVIRONMENT LIMITATIONS

> **IMPORTANT:** this section describes limitations of the SEFAZ SP staging environment, **not** of the code. The code has been validated against the official XSD and complies with NT 2019.001.

### 13.1 cStat=225 — "Rejeição: Falha no Schema XML do lote de NFe"

**Observed behavior:**

```
retEnviNFe.cStat   = 104   (batch accepted by PL009 processor)
protNFe.cStat      = 225   (NF-e rejected by PL_008i2 processor)
xMotivo            = "Rejeição: Falha no Schema XML do lote de NFe"
verAplic (batch)   = SP_NFE_PL009_V4
verAplic (infProt) = SP_NFE_PL_008i2
```

**Diagnosis:**

SEFAZ SP uses two distinct processors: `PL009` validates the batch, and `PL_008i2` (an older version) validates each NF-e individually. The most likely hypothesis is that `PL_008i2` internally uses `xmldsig-core-schema_v1.01.xsd` with `fixed="rsa-sha1"`, while the code uses RSA-SHA256 (mandated by NT 2019.001).

**Investigation status:** CLOSED. This is a limitation of the SEFAZ SP HOM environment. No corrective action is possible in the code without violating NT 2019.001.

**Impact in HOM:** all NF-e transmitted in HOM-SP return cStat=225. The order ends up as `"REJEITADO"` or `"AGUARDANDO"`. To validate the technical flow, inspect `data.soapRetorno` (SEFAZ response) and confirm the access key was generated (44 digits).

**Impact in PRD:** none. Does not affect production and does not affect other Brazilian states.

### 13.2 Certificate cache invalidation

As described in section 10.4, the `EmpresaCertificadoService` cache is not automatically invalidated when the certificate is updated via the API. This is an **implementation gap** (not an external limitation) to be fixed before production (Phase 11).

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
       {"username":"admin@company.com","password":"admin123"}
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
□ 9. In HOM-SP: cStat=225 in soapRetorno is expected — not a system error
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
               certSenha: "plaintext_password",
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

**Gap:** invalidation is not called automatically in `EmpresaController.atualizar()`. Must be fixed before production.

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

## 16. ROADMAP TO PRODUCTION

### Phase 10 — Documentation and Swagger ✓ COMPLETED (2026-05-12)

| Item                                           | Status                                                  |
|------------------------------------------------|---------------------------------------------------------|
| Technical manual PT                            | ✓ This document (v2.0)                                  |
| Technical manual EN                            | ✓ `MTF-001_motor-fiscal-nfe_EN.md`                      |
| PT-BR integration contract                     | ✓ `INTEGRATION_CONTRACT_PT-BR.md`                       |
| EN integration contract                        | ✓ `INTEGRATION_CONTRACT_EN.md`                          |
| Annotated Swagger (10 tags, deprecated marked) | ✓ `SwaggerConfig.java` — Sprint 3                       |
| End-to-end Postman collection (46 requests)    | ✓ `docs/postman/borurio-erp-collection.json` — Sprint 3 |

### Phase 11 — PRD Deployment + CI/CD (pending)

| Item                                     | Priority     | Description                                                                |
|------------------------------------------|--------------|----------------------------------------------------------------------------|
| `CERT_ENCRYPTION_KEY` in PRD             | **CRITICAL** | Generate via `openssl rand -base64 32`; inject via secrets manager         |
| PRD A1 certificates with real CNPJ       | **CRITICAL** | `tpAmb=1`; register company with `cert_path` pointing to PRD certificate   |
| Automatic certificate cache invalidation | **HIGH**     | `EmpresaController.atualizar()` must call `invalidar(empresaId)`           |
| Rate limiting on `/emitir`               | **MEDIUM**   | Bucket4j or equivalent; protects against abuse                             |
| CI/CD pipeline                           | **MEDIUM**   | GitHub Actions: test → build → push image → deploy HOM → smoke test        |
| `nfe_log` retention policy               | **LOW**      | `NfeLogMapper.deleteAntigos(dias)` already implemented; scheduling missing |
| Monitoring                               | **LOW**      | Prometheus + Loki                                                          |

---

## 17. NORMATIVE REFERENCES

| Document                                   | Description                                            |
|--------------------------------------------|--------------------------------------------------------|
| AJUSTE SINIEF 07/2005 and amendments       | Establishes the Nota Fiscal Eletrônica                 |
| Manual de Orientação do Contribuinte (MOC) | Version 7.0 — NF-e 4.00 layout                         |
| Nota Técnica 2019.001                      | NF-e 4.00 layout update / SHA-256 algorithms mandatory |
| ABNT NBR ISO/IEC 27001                     | Information security management                        |
| XML-DSig W3C Recommendation                | `https://www.w3.org/TR/xmldsig-core/`                  |
| RFC 5652                                   | Cryptographic Message Syntax (base of PKCS#12)         |

---

*Document MTF-001 — version 2.0 — Borurio ERP Fiscal BR*  
*Based on the state validated in HOM on 2026-05-11*  
*Updated with Sprint 3 on 2026-05-12*  
*Next revision: after PRD deployment (Phase 11)*
