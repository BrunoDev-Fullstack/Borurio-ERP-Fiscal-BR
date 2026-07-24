# Integration Document
## Borurio BR Fiscal Engine — REST API
### ERP Logistics × NF-e 4.00 SEFAZ-SP

| Attribute             | Value                                   |
|-----------------------|-----------------------------------------|
| Version               | 1.11                                    |
| Status                | **VALID FOR HOM INTEGRATION** — HOM is available for the CC's own testing; go-live starts only after the CC's final validation. This translation is behind the PT-BR original (canonical) by several minor revisions between 1.9.1 and 1.11 — see `INTEGRATION_CONTRACT_PT-BR.md` for full intermediate history. |
| Validation date       | 2026-07-22                              |
| Reference environment | HOM — external access provided only during a controlled test window. No fixed URL should be assumed by the integrator. |
| Platform              | Spring Boot 3.3.2 · Java 17 · NF-e 4.00 |
| Validated against     | Source code + HOM tests                 |

---

## Marker Legend

> `[CONTRACT]` Binding rule — the integrator must follow it. Non-compliance results in an error or undefined behavior.

> `[EXAMPLE]` Illustrative payload or value. Actual data varies by environment and setup.

> `[OPERATIONAL]` Behavior observed in HOM or an implementation nuance relevant to the integrator.

---

## 1. Objective and Scope

This document describes the integration contract between the external logistics ERP and the Borurio BR fiscal engine. It covers exclusively the endpoints required for the complete NF-e 4.00 issuance flow.

**Out of scope:**
- User and company management (internal ADMIN endpoints)
- Numbering range cancellation (inutilização)
- Fiscal audit logs (available at `/api/fiscal/nfe/logs`)
- Legacy endpoints under `/api/fiscal/nfe/` (deprecated)

---

## 2. Environments

| Environment   | Base URL                | Purpose                                   |
|---------------|-------------------------|-------------------------------------------|
| DEV           | `http://localhost:8080` | Local development                         |
| HOM           | `https://hom-api.borurio.com` | SEFAZ-SP staging                    |
| PRD           | Defined by operations   | Production — not covered by this document |

> `[CONTRACT]` All integration tests must be executed in HOM before any operation in PRD.

> `[OPERATIONAL]` The HOM environment is exposed externally via Cloudflare Quick Tunnel (HTTPS, TLS 1.3) — no VPN or SSH tunnel required on the integration team's side. **The tunnel URL is temporary and changes on each restart.** Bruno will provide the active URL directly via secure channel before each test session. Verify availability with `GET <tunnel-url>/api/test/ping` before starting the smoke test sequence.

---

## 3. Authentication

### 3.1 Obtaining the Token

> `[CONTRACT]` Every integration session begins by obtaining a JWT token. Without a valid token, all protected endpoints return HTTP 401.

```
POST /auth/login
Content-Type: application/json
```

```json
{
  "username": "user@company.com",
  "password": "password"
}
```

> `[CONTRACT]` The `username` field expects the user's **email address** registered in the system — not a free-form username.

**Success response — HTTP 200:**
```json
{
  "code": 200,
  "message": "Autenticação bem-sucedida",
  "token": "eyJhbGci..."
}
```

> `[CONTRACT]` This is the only route that does not use the standard `Result<>` API envelope. Its response structure is its own: `{ code, message, token }`.

**Failure response — HTTP 401:**
```json
{ "code": 401, "message": "Credenciais inválidas", "token": null }
```

### 3.2 Using the Token

> `[CONTRACT]` The token must be sent in the `Authorization` header on all subsequent requests:

```
Authorization: Bearer eyJhbGci...
```

> `[CONTRACT]` The token expires after **1 hour** (default of `security.jwt.expiration-ms`). After expiration, all endpoints return HTTP 401 and a new login is required.

> `[OPERATIONAL]` Implement token renewal before expiration for long-running flows (e.g., batch imports).

---

### 3.3 OMS Session — Fiscal Authorization via A1 Certificate

> `[CONTRACT]` The external logistics ERP (OMS) **does not use** `POST /auth/login` to authenticate. The OMS opens a session via **fiscal authorization**, sending the issuing company's A1 certificate and receiving a long-lived technical token.

> `[CONTRACT]` **No prior company registration is required.** The system automatically creates the company record from the X.509 certificate Subject (company name extracted from the `CN=` field, state from `ST=`). Only companies with an inactive registration block authorization — in that case the OMS receives `COMPANY_INACTIVE`.

#### Multi-CNPJ Model

> `[CONTRACT]` An OMS client (`codigoEmpresaOms`) can authorize **multiple CNPJs** under a single token. The token identifies the OMS client, not a specific CNPJ. Each CNPJ requires a separate call to this endpoint — the token returned is always the same for the same `codigoEmpresaOms`.

> `[CONTRACT]` When issuing an NF-e for an OMS client with multiple authorized CNPJs, the `cnpjEmitente` field of the order selects which CNPJ (and certificate) will be used to sign and transmit the NF-e to SEFAZ. The informed CNPJ must be authorized for the token (active certificate) **and** have a fiscal registration (state tax ID) accepted by SEFAZ — registration issues for a specific company are reported by SEFAZ in the issuance response (`cStat`/`xMotivo`), they do not prevent the token authorization itself. See section 6.3.

#### Endpoint

```
POST /api/integration/fiscal-authorizations
X-Api-Key: {integrator-technical-key}
Content-Type: application/json
```

> `[CONTRACT]` The `X-Api-Key` header is required. Requests without this header, or with an invalid or revoked key, return HTTP 401 with `errorCode: INVALID_API_KEY`. The key is provided by the Borurio ADMIN.

**Payload fields:**

| Field | Type | Rule |
|---|---|---|
| `codigoEmpresaOms` | String | Required · Max 100 chars · Unique identifier of the OMS client |
| `cnpj` | String | Required · Exactly 14 numeric digits |
| `certBase64` | String | Required · PKCS12 file (.pfx / .p12) encoded in Base64 |
| `certSenha` | String | Required · PKCS12 file password |

> `[CONTRACT]` The `cnpj` sent must match the CNPJ embedded in the X.509 certificate Subject. A mismatch returns `CNPJ_CERTIFICATE_MISMATCH`.

> `[EXAMPLE]` First authorization — CNPJ1 of a new OMS client:
```json
{
  "codigoEmpresaOms": "{{codigoEmpresaOms}}",
  "cnpj":             "12000000000195",
  "certBase64":       "<base64-encoded .pfx file>",
  "certSenha":        "<certificate password>"
}
```

> `[EXAMPLE]` Second authorization — CNPJ2 of the **same** OMS client (returns the same token):
```json
{
  "codigoEmpresaOms": "{{codigoEmpresaOms}}",
  "cnpj":             "98765432000100",
  "certBase64":       "<base64-encoded .pfx of the second CNPJ>",
  "certSenha":        "<password of the second certificate>"
}
```

**Success response — HTTP 200:**
```json
{
  "token":         "eyJhbGci...",
  "empresaId":     1,
  "cnpj":          "12000000000195",
  "razaoSocial":   "Jcho Factory Ltda",
  "tokenExpiraEm": "2026-08-01T13:15:00"
}
```

> `[CONTRACT]` This route **does not use** the standard `Result<>` API envelope. The response structure is its own: `{ token, empresaId, cnpj, razaoSocial, tokenExpiraEm }`. Read `response.token` directly — there is **no** `response.data.token`.

> `[CONTRACT]` The `tokenExpiraEm` field reflects the expiry of the A1 certificate sent. The OMS must store the token and send it in the `Authorization: Bearer {token}` header on all subsequent operations (orders, issuance, status queries, cancellations, CC-e).

> `[CONTRACT]` For OMS clients with **multiple CNPJs**, the token is always the same regardless of which CNPJ was most recently authorized. The `empresaId` in the response refers to the company of the first CNPJ authorized for that `codigoEmpresaOms` (anchor company). The `empresaId` is not needed in order requests.

#### Behavior by Call Scenario

| Scenario | Condition | Behavior |
|---|---|---|
| **A — New OMS client** | `codigoEmpresaOms` never seen before | Creates slot + inserts cert + issues **new token** |
| **B — Same CNPJ, same cert** | Identical cert already active | No database change — **returns existing token** |
| **C — Same CNPJ, new cert** | Same CNPJ, different thumbprint | Deactivates previous cert + inserts new cert + updates `tokenExpiraEm` — **returns existing token (same JTI)** |
| **D — New CNPJ, existing client** | `codigoEmpresaOms` already authorized, new CNPJ | Inserts cert for the new CNPJ — **returns existing token unchanged** |

> `[CONTRACT]` In scenarios B, C, and D the **token does not change**. The OMS does not need to update the stored token when adding or renewing CNPJs for an already-authorized client. Store the token received on the client's first authorization.

#### Updating an Expired or Renewed Certificate

> `[CONTRACT]` To renew the certificate for a specific CNPJ, call this endpoint again with the same `codigoEmpresaOms` and `cnpj`, and the new `.pfx` file. The system deactivates the previous certificate for that CNPJ and activates the new one — the token remains the same (scenario C above).

> `[CONTRACT]` The ADMIN does not need to be contacted to replace the certificate — the OMS performs the renewal directly via this endpoint.

#### Revocation and rotation

> `[OPERATIONAL]` If an API Key or token is compromised, contact the Borurio ADMIN for administrative revocation or rotation. **As of 2026-07-22, the active authorization is checked in the database on every request** — after revocation, the token is rejected immediately with `AUTHORIZATION_REVOKED`, independent of the JWT's natural expiry. Rotation issues a new token (new internal identifier), invalidating the previous one, without interrupting the OMS's access to the service.

> `[CONTRACT]` Revocation and rotation are internal Borurio administrative operations (`/api/admin/oms-authorizations/**`, outside the public contract consumed by the OMS) — the OMS never calls these endpoints directly. The OMS only receives the new token, through a controlled channel, when a rotation occurs.

---

## 4. Multi-Company Context (Multitenancy)

> `[CONTRACT]` The `empresaId` **is not sent in request bodies.** It is extracted automatically from the JWT token by the system.

**How it works internally — user flow:**

1. At login, the user's `empresaId` is embedded in the token as claim `"eid"`
2. On each request, the JWT filter extracts `"eid"` and associates it with the request context
3. All product, order, and customer endpoints automatically scope data by company
4. The context is cleared after each request — no risk of leakage between calls

> `[CONTRACT]` A token issued for company A **only accesses** products, orders, and customers of company A. Attempts to access resources from another company return HTTP 404 (resource not found for the authenticated company).

> `[OPERATIONAL]` Each partner company must have its own user with distinct credentials. Do not share tokens between companies.

**OMS multi-CNPJ context:**

> `[CONTRACT]` The OMS token identifies the **OMS client** (`codigoEmpresaOms`), not a specific CNPJ. Products registered via OMS token belong to the OMS client. For NF-e issuance, the `cnpjEmitente` field of the order determines which certificate is used for signing and SEFAZ transmission (see section 6.3).

> `[OPERATIONAL]` The `empresaId` does not need to be sent in any OMS flow request. The system resolves the issuing company from the order's `cnpjEmitente`.

---

## 5. Mandatory Integration Flow

### 5.1 Call Sequence

> `[CONTRACT]` The sequence below must be followed. Steps cannot be skipped.

```
Step 1 ── POST /auth/login
          └─► Obtain JWT token

Step 2 ── POST /api/app/produtos          (if product does not yet exist)
          └─► Register product with complete fiscal data
              Save: data.id = produtoId

Step 3 ── POST /api/app/pedidos
          └─► Create order in RASCUNHO state with items
              Save: data.id = pedidoId

Step 4 ── POST /api/app/pedidos/{pedidoId}/emitir
          └─► Transmit NF-e to SEFAZ
              Save: data.chaveNfe

Step 5 ── GET  /api/app/pedidos/{pedidoId}/situacao
          └─► Confirm local status + live SEFAZ query
              (optional after AUTORIZADO status, mandatory after AGUARDANDO)
```

**Post-issuance operations (when applicable):**

```
Step 6 ── POST /api/app/pedidos/{pedidoId}/cancelar
          └─► Cancel NF-e (AUTORIZADO status only, within legal deadline)

Step 7 ── POST /api/app/pedidos/{pedidoId}/cce
          └─► Electronic Correction Letter (AUTORIZADO status only)
```

### 5.2 State Transition Diagram

```
                          ┌─────────────────────────────┐
                          │                             │
              POST /emitir│          SEFAZ              │
RASCUNHO ────────────────►│   cStat=100  → AUTORIZADO   │
                          │   cStat=104  → AGUARDANDO   │
                          │   cStat≥200  → REJEITADO    │
                          │   exception  → ERRO          │
                          └─────────────────────────────┘

AUTORIZADO ──► POST /cancelar ──► CANCELADO   (immutable)
AUTORIZADO ──► POST /cce      ──► AUTORIZADO  (status unchanged)
AGUARDANDO ──► GET  /situacao ──► (check current cStat)
REJEITADO  ──► POST /emitir   ──► retry on the SAME order (no new order needed)
ERRO       ──► POST /emitir   ──► retry on the SAME order (no new order needed)
```

> `[CONTRACT]` Since v1.9, `REJEITADO` and `ERRO` are **no longer terminal states**. `POST /emitir` can be called again on the same `pedidoId` — no need to create a new order. Each new attempt generates a fresh NF-e number (`nNF`) and `chaveNfe`, with no duplicate-submission risk to SEFAZ. Only `AUTORIZADO` and `CANCELADO` remain terminal.

> `[OPERATIONAL]` **2026-07-14 correction:** an earlier version of this note claimed that a batch-level `cStat=104` combined with an individual-NF-e `cStat=225` in the same SOAP response would leave the order in `AGUARDANDO`. That was incorrect — any `cStat≥200` (including 225) has always resulted in `REJEITADO`, never `AGUARDANDO`, and since v1.9 it returns HTTP 422 `SEFAZ_REJECTED`, not HTTP 200. The root cause of `cStat=225` itself was identified and fixed on 2026-07-14 (see section 9.3) — the expected result in HOM-SP is now `cStat=100` (`AUTORIZADO`) for a company with fiscal registration accepted by SEFAZ during the staging issuance.

---

## 6. Endpoints by Step

### 6.1 Health Check

> `[CONTRACT]` Does not require authentication.

```
GET /api/test/ping
```

**Response — HTTP 200:**
```json
{
  "status":      "UP",
  "code":        200,
  "message":     "API Borurio ERP Fiscal BR está operacional.",
  "environment": "dev",
  "timestamp":   "2026-05-12T10:00:00.000-03:00"
}
```

> `[CONTRACT]` This route does not use the standard `Result<>` envelope. Its response structure is its own.

> `[OPERATIONAL]` The `"environment"` field reflects the active Spring profile (`spring.profiles.active`). Use it only as a diagnostic indicator, not as a routing discriminator.

---

### 6.2 Product Registration (Step 2)

> `[CONTRACT]` The product must be registered before creating any order that references it. Fiscal fields are **frozen in the order item** at creation time — subsequent product updates do not retroactively affect existing orders.

```
POST /api/app/produtos
Authorization: Bearer {token}
Content-Type: application/json
```

**Fields and rules:**

| Field       | Type    | Rule                                                     |
|-------------|---------|----------------------------------------------------------|
| `codigo`    | String  | Required · Max 60 chars · Unique per company             |
| `descricao` | String  | Required · Max 120 chars                                 |
| `ncm`       | String  | Required · Exactly 8 numeric digits                      |
| `cfop`      | String  | Optional · 4 numeric digits · defaults to `"5102"` (intra-state) if omitted |
| `unidade`   | String  | Required · E.g.: `UN`, `KG`, `PC`, `CX`                  |
| `preco`     | Decimal | Required · Value > 0.01                                  |
| `origem`    | Integer | Required · `0`=Domestic · `1` to `8`=Imported            |
| `csosn`     | String  | Optional · If omitted, item snapshot defaults to `"400"` |

> `[CONTRACT]` CFOP reference: intra-state operation: `5102` · interstate operation: `6102`. CFOP remains the OMS's responsibility — Borurio validates coherence between the informed CFOP and the calculated destination (`idDest`), but does not correct or infer it.

> `[CONTRACT]` **Freight modality (`modFrete`) is not sent by the OMS in this version of the contract — there is no field for it in the payload.** Borurio internally defines `modFrete=2` ("Freight Contracted by Third Party") for every issuance in the current OMS/marketplace flow, reflecting that the marketplace itself contracts the freight (confirmed by the Chinese integrator). This is an internal Borurio fiscal decision, not an OMS-configurable parameter in this version.

> `[CONTRACT]` CSOSN reference (Simples Nacional): `102`=no ST no credit · `103`=exempt by revenue bracket · `300`=immune · `400`=non-contributor · `500`=ICMS previously collected (ST) · `900`=other. Codes `201`, `202`, and `203` (with ST) are not supported in this version of the fiscal engine.

> `[EXAMPLE]` Minimum valid payload:
```json
{
  "codigo":    "PROD-001",
  "descricao": "Test Integration Product",
  "ncm":       "84715011",
  "cfop":      "5102",
  "unidade":   "UN",
  "preco":     100.00,
  "origem":    0,
  "csosn":     "102"
}
```

**Response — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "id":           7,
    "empresaId":    1,
    "codigo":       "PROD-001",
    "descricao":    "Test Integration Product",
    "ncm":          "84715011",
    "cfop":         "5102",
    "unidade":      "UN",
    "preco":        100.00,
    "origem":       0,
    "csosn":        "102",
    "estado":       null,
    "estoque":      null,
    "criadoEm":     "2026-05-12T10:00:00",
    "atualizadoEm": null
  }
}
```

> `[CONTRACT]` Save `data.id` as `produtoId` for use in order items.

> `[OPERATIONAL]` A product with `estado=0` is treated as inactive. Attempting to reference an inactive product in an order returns HTTP 400: `"Produto inativo não pode ser adicionado ao pedido"`.

---

### 6.2b Product Lookup by Internal Code (SKU)

> `[OPERATIONAL]` When the OMS knows the product's internal code (SKU) but not its `id` in Borurio, use this endpoint to resolve the identifier before querying stock.

```
GET /api/app/produtos/codigo/{codigo}
Authorization: Bearer {token}
```

| Parameter | Rule |
|---|---|
| `codigo` | Product's internal code (path variable) — the same value registered in `POST /api/app/produtos` |

**Response — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "id":        7,
    "empresaId": 1,
    "codigo":    "PROD-001",
    "descricao": "Test Integration Product",
    "ncm":       "84715011",
    "cfop":      "5102",
    "unidade":   "UN",
    "preco":     100.00,
    "origem":    0,
    "csosn":     "102",
    "estado":    1
  }
}
```

> `[CONTRACT]` Save `data.id` for use in the stock query flow.

> `[OPERATIONAL]` Two-step flow validated by the integration team:
> ```
> Step 1 — GET /api/app/produtos/codigo/{sku}  → retrieves product + id
> Step 2 — GET /api/app/produtos/{id}/estoque  → queries stock balance using the returned id
> ```

**Response — HTTP 404:**
```json
{ "code": 404, "message": "Recurso não encontrado", "data": null }
```

---

### 6.2c Stock Management (Inventory)

> `[CONTRACT]` The fiscal engine controls product inventory with atomic reservations tied to the NF-e issuance cycle. The OMS must verify available stock before submitting an order for issuance.

#### Checking Stock Balance

```
GET /api/app/produtos/{id}/estoque
Authorization: Bearer {token}
```

**Response — HTTP 200:**
```json
{
  "code": 200,
  "data": {
    "produtoId":         1,
    "estoqueTotal":      "100.0000",
    "estoqueReservado":  "10.0000",
    "estoqueDisponivel": "90.0000"
  }
}
```

> `[CONTRACT]` Use `estoqueDisponivel` to decide whether to proceed with the order. If the item quantity exceeds `estoqueDisponivel`, the `/emitir` call returns HTTP 422 and the order stays in `RASCUNHO`.

#### Manual Stock Entry

```
POST /api/app/produtos/{id}/estoque/entrada
Authorization: Bearer {token}    ← ADMIN role required
Content-Type: application/json
```

```json
{ "quantidade": 50.00, "observacao": "Initial stock entry" }
```

> `[CONTRACT]` This endpoint requires the ADMIN role. An OPERADOR token receives HTTP 403.

#### Stock Lifecycle During NF-e Issuance

| Event                         | Stock effect                                                         |
|-------------------------------|----------------------------------------------------------------------|
| `POST /emitir` called         | Atomic reservation — `estoqueDisponivel -= qty` per item             |
| HTTP 422 returned             | Insufficient stock — no reservation made; order stays in `RASCUNHO` |
| Status → `AUTORIZADO`         | Definitive write-off — `estoqueTotal -= qty`, reservation released   |
| Status → `REJEITADO` / `ERRO` | Reservation undone — `estoqueDisponivel += qty` per item             |
| Status → `AGUARDANDO`         | Reservation held — `estoqueDisponivel` remains blocked               |
| Status → `CANCELADO`          | Stock reversal — `estoqueTotal += qty`                               |

---

### 6.2d Bulk Product Upsert — Batch

> `[OPERATIONAL]` Use this endpoint for OMS catalog synchronization when importing multiple products at once. For single product creation, use `POST /api/app/produtos` (section 6.2).

```
POST /api/app/produtos/batch
Authorization: Bearer {token}
Content-Type: application/json
```

**Behavior per item:**
- New product (code + company not found): status **CRIADO**
- Existing product (same code and same company): status **ATUALIZADO** — only catalog fields are updated; `estoque`, `estoque_reservado`, and `estado` are preserved
- Item with invalid data: status **REJEITADO** — processing continues to the next item
- Duplicate code in the same payload: **REJEITADO** with `DUPLICATE_CODIGO_IN_BATCH` — the second occurrence is rejected; the first is processed normally

> `[CONTRACT]` The response is always **HTTP 207 Multi-Status** — even if all items succeed or all fail. Never expect HTTP 200 or a per-item HTTP 422 here.

> `[CONTRACT]` Maximum **200 products per request**. Exceeding this limit returns HTTP 422 with `errorCode: "BATCH_LIMIT_EXCEEDED"` before any item is processed.

> `[CONTRACT]` An empty `produtos` list returns HTTP 400.

**Fields per item (`produtos[]`):**

| Field       | Type    | Rule                                                          |
|-------------|---------|---------------------------------------------------------------|
| `codigo`    | String  | Required · Upsert key per company                            |
| `descricao` | String  | Required                                                     |
| `ncm`       | String  | Required · Exactly 8 numeric digits                          |
| `cfop`      | String  | Optional · 4 numeric digits · defaults to `"5102"` if omitted |
| `unidade`   | String  | Required                                                     |
| `preco`     | Decimal | Required · Value > 0                                         |
| `origem`    | Integer | Optional · defaults to `0` (domestic) if omitted             |
| `csosn`     | String  | Optional · defaults to `"400"` if omitted                    |

**Fields NOT updated on upsert (existing product):** `codigo`, `estoque`, `estoque_reservado`, `estado`

> `[EXAMPLE]` Request with 3 items — 1 new, 1 existing, 1 invalid:
```json
{
  "produtos": [
    { "codigo": "SKU-001", "descricao": "Product A", "ncm": "84715011", "unidade": "UN", "preco": 100.00 },
    { "codigo": "SKU-002", "descricao": "Product B", "ncm": "84715011", "unidade": "UN", "preco": 50.00 },
    { "codigo": "SKU-BAD", "descricao": "Bad item",  "ncm": "123",      "unidade": "UN", "preco": 10.00 }
  ]
}
```

**Response — HTTP 207 Multi-Status:**
```json
{
  "total":       3,
  "criados":     1,
  "atualizados": 1,
  "rejeitados":  1,
  "resultados": [
    { "codigo": "SKU-001", "status": "CRIADO",     "produtoId": 101 },
    { "codigo": "SKU-002", "status": "ATUALIZADO", "produtoId": 87  },
    { "codigo": "SKU-BAD", "status": "REJEITADO",  "produtoId": null, "errorCode": "VALIDATION_ERROR", "message": "NCM deve ter exatamente 8 dígitos numéricos." }
  ]
}
```

> `[CONTRACT]` Each entry in `resultados` maps 1:1 to the input `produtos[]` by position and `codigo`. A `REJEITADO` item never blocks processing of subsequent items.

> `[CONTRACT]` Use `resultados[].status` (`CRIADO` / `ATUALIZADO` / `REJEITADO`) for per-item outcome. Use `resultados[].errorCode` (when present) for programmatic error handling on rejected items.

> `[CONTRACT]` `resultados[].produtoId` contains the database ID for `CRIADO` and `ATUALIZADO` items, and is `null` for `REJEITADO`.

---

### 6.3 Creating an Order — RASCUNHO (Step 3)

> `[CONTRACT]` A newly created order always starts in the `RASCUNHO` state. Fields such as `status`, `chaveNfe`, and `numero` are populated automatically — **do not send them in the body**.

```
POST /api/app/pedidos
Authorization: Bearer {token}
Content-Type: application/json
```

**Order header fields:**

| Field                 | Type   | Rule                                                         |
|-----------------------|--------|--------------------------------------------------------------|
| `destCnpjCpf`         | String | Required · CNPJ (14 digits) or CPF (11 digits) · digits only |
| `destRazaoSocial`     | String | Required                                                     |
| `destUf`              | String | Recommended · State abbreviation: `SP`, `RJ`, `MG`...        |
| `destLogradouro`      | String | Recommended · Improves SEFAZ approval                        |
| `destNumero`          | String | Recommended                                                  |
| `destBairro`          | String | Recommended                                                  |
| `destCodigoMunicipio` | String | Recommended · 7-digit IBGE code                              |
| `destMunicipio`       | String | Recommended                                                  |
| `destCep`             | String | Recommended                                                  |
| `naturezaOperacao`    | String | Optional · Server-side default: `"VENDA DE MERCADORIA"`      |
| `cnpjEmitente`        | String | **Required for OMS multi-CNPJ** · 14 numeric digits · CNPJ that must sign and issue the NF-e · If omitted in an OMS flow, the system uses the CNPJ of the first authorized certificate for that client |

> `[OPERATIONAL]` When an OMS client has more than one CNPJ linked to the same token, **do not rely on the default CNPJ** (first authorized certificate) — send `cnpjEmitente` explicitly in every order. A historical/deactivated certificate linked to the same token may be resolved by default and cause a SEFAZ rejection due to a registration issue (`cStat=209`, invalid emitter state tax ID) even when another CNPJ of the same OMS client is active and fit for issuance.
| `externalOrderId`     | String | Recommended · Max 100 chars · Unique per company · Enables idempotent retry |
| `emitLogradouro`      | String | Optional · Emitter address (see note below) |
| `emitNumero`          | String | Optional |
| `emitBairro`          | String | Optional |
| `emitCodigoMunicipio` | String | Optional · 7-digit IBGE code |
| `emitMunicipio`       | String | Optional |
| `emitCep`             | String | Optional |

> `[CONTRACT]` **Emitter address (v1.9).** When a company is first authorized (`POST /api/integration/fiscal-authorizations`), it is auto-created with only CNPJ, razão social and UF — data extracted from the A1 certificate, which carries no address. If the emitter company's registration is missing address data, `POST /emitir` returns `EMITTER_ADDRESS_INCOMPLETE` without ever calling SEFAZ (see section 8.2a). To fix this, send the 6 `emit*` fields above in `POST /api/app/pedidos` — the system automatically fills in **only the missing fields** on the company record (never overwrites an address that's already set). No need to resend on every order — once complete, the record stays complete.

> `[CONTRACT]` **Idempotency:** If `externalOrderId` is provided and an order already exists for the same company with that identifier, the system returns the existing order unchanged — no duplicate is created. This protects against duplicate NF-e issuance caused by OMS timeout or network retry. If omitted, each call always creates a new order.

> `[CONTRACT]` The `externalOrderId` scope is per company: the same value used by company A does not conflict with company B.

**Per-item fields (`itens[]`):**

> `[CONTRACT]` All fiscal fields below are **required** and must be sent by the OMS on every item. The system does **not** copy fiscal data from the product catalog — what the OMS sends is exactly what goes into the NF-e XML. If any required fiscal field is missing, the order is rejected with HTTP 400.

| Field            | Type    | Rule                                                      |
|------------------|---------|-----------------------------------------------------------|
| `produtoId`      | Long    | Required · Product must exist and be active               |
| `quantidade`     | Decimal | Required · Value > 0                                      |
| `valorUnitario`  | Decimal | Required · Value > 0                                      |
| `codigoProduto`  | String  | Required · Product code as it appears in the NF-e (`cProd`) |
| `descricao`      | String  | Required · Product description as it appears in the NF-e (`xProd`) |
| `ncm`            | String  | Required · Exactly 8 numeric digits                       |
| `cfop`           | String  | Required · 4 numeric digits (e.g. `"5102"` intra-state, `"6102"` interstate) |
| `unidade`        | String  | Required · E.g.: `UN`, `KG`, `PC`, `CX`                  |
| `origem`         | Integer | Required · `0`=Domestic · `1`–`8`=Imported               |
| `csosn`          | String  | Required · Simples Nacional: `"102"`, `"103"`, `"300"`, `"400"`, `"500"`, `"900"` (do not send `201`/`202`/`203`) |

> `[CONTRACT]` Each item's `valorTotal` is calculated automatically as `quantidade × valorUnitario`. The order total is the sum of all items. Do not send these fields.

> `[EXAMPLE]` Valid payload with fiscal fields per item:
```json
{
  "externalOrderId":     "OMS-20260601-0001",
  "destCnpjCpf":         "12345678000195",
  "destRazaoSocial":     "Destination Company Ltd",
  "destUf":              "SP",
  "destCodigoMunicipio": "3550308",
  "destMunicipio":       "São Paulo",
  "itens": [
    {
      "produtoId":     7,
      "quantidade":    2,
      "valorUnitario": 100.00,
      "codigoProduto": "SKU-OMS-001",
      "descricao":     "Test Integration Product",
      "ncm":           "84715011",
      "cfop":          "6102",
      "unidade":       "UN",
      "origem":        0,
      "csosn":         "102"
    }
  ]
}
```

**Response — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "id":              42,
    "empresaId":       1,
    "numero":          "PED-00000042",
    "externalOrderId": "OMS-20260601-0001",
    "cnpjEmitente":    "12000000000195",
    "destCnpjCpf":     "12345678000195",
    "destRazaoSocial": "Destination Company Ltd",
    "destUf":          "SP",
    "naturezaOperacao":"VENDA DE MERCADORIA",
    "serieNfe":        "1",
    "status":          "RASCUNHO",
    "chaveNfe":        null,
    "valorTotal":      200.00,
    "dataPedido":      "2026-05-12T10:05:00",
    "dataAtualizacao": null,
    "itens": [
      {
        "id":            88,
        "pedidoId":      42,
        "produtoId":     7,
        "quantidade":    2.00,
        "valorUnitario": 100.00,
        "valorTotal":    200.00,
        "codigoProduto": "PROD-001",
        "descricao":     "Test Integration Product",
        "ncm":           "84715011",
        "cfop":          "5102",
        "unidade":       "UN",
        "origem":        0,
        "csosn":         "102"
      }
    ]
  }
}
```

> `[CONTRACT]` Save `data.id` as `pedidoId` for the following steps.

> `[OPERATIONAL]` The fiscal snapshot fields in each item (`codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`) are copied from the product at creation time. This copy is immutable — subsequent changes to the product catalog do not affect existing orders.

---

### 6.4 Issuing the NF-e (Step 4)

> `[CONTRACT]` No request body. The fiscal engine constructs the NF-e 4.00 XML internally from the items' fiscal snapshot.

> `[CONTRACT]` Precondition (v1.9): the order must be in `RASCUNHO`, `REJEITADO`, or `ERRO`. Any other state (`AUTORIZADO`, `AGUARDANDO`, `CANCELADO`) returns HTTP 422 with `errorCode: INVALID_ORDER_STATUS`. Calling `/emitir` on a `REJEITADO` or `ERRO` order **reuses the same order** — do not create a new order to retry.

```
POST /api/app/pedidos/{pedidoId}/emitir
Authorization: Bearer {token}
```

**Response — HTTP 200 (SEFAZ authorized or still processing):**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "chaveNfe":    "35260512000000000000550010000000421000000424",
    "soapRetorno": "<nfeProc ...>...</nfeProc>"
  }
}
```

> `[CONTRACT]` Since v1.9, HTTP 200 only occurs when the NF-e was **authorized** (`AUTORIZADO`) or is **pending** (`AGUARDANDO`). A SEFAZ rejection does **not** return HTTP 200 anymore — see `SEFAZ_REJECTED` below. The real status can still be confirmed via `GET /api/app/pedidos/{id}` or `/situacao`.

> `[CONTRACT]` The `data.chaveNfe` field will be an empty string `""` (not `null`) when SEFAZ does not return an access key.

**Response — HTTP 422 (state precondition violated):**
```json
{ "code": 422, "message": "Pedido não pode ser emitido no status atual: AUTORIZADO. Permitido apenas para RASCUNHO, REJEITADO ou ERRO.", "data": null, "errorCode": "INVALID_ORDER_STATUS", "retryable": false }
```

**Response — HTTP 422 (SEFAZ rejected the NF-e):**
```json
{
  "code": 422,
  "message": "NF-e rejeitada pela SEFAZ: Rejeição: Falha no Schema XML do lote de NFe",
  "data": { "cStat": 225, "xMotivo": "Rejeição: Falha no Schema XML do lote de NFe" },
  "errorCode": "SEFAZ_REJECTED",
  "retryable": false
}
```

> `[CONTRACT]` `SEFAZ_REJECTED` exposes the real `cStat`/`xMotivo` in `data` — no need to parse the raw `soapRetorno` to find the reason. `retryable: false` because the cause is usually bad data (NCM, CFOP, CSOSN, address) that will repeat on an unmodified retry. Fix the cause and call `/emitir` again on the same `pedidoId`.

**Response — HTTP 422 (emitter registration incomplete — SEFAZ is never called):**
```json
{ "code": 422, "message": "Cadastro do emitente incompleto.", "data": null, "errorCode": "EMITTER_ADDRESS_INCOMPLETE", "retryable": false }
```

**Response — HTTP 503 (network failure calling SEFAZ):**
```json
{ "code": 503, "message": "SEFAZ temporariamente indisponível.", "data": null, "errorCode": "SEFAZ_UNAVAILABLE", "retryable": true }
```

> `[CONTRACT]` `SEFAZ_TIMEOUT`/`SEFAZ_UNAVAILABLE` are the only `/emitir` failures with `retryable: true` — retrying unchanged is safe in these cases.

> `[OPERATIONAL]` An unexpected (unclassified) exception returns HTTP 500 with `retryable: false` and no `errorCode`. Check the order's status via `GET /api/app/pedidos/{id}` before deciding whether to retry.

---

### 6.5 Checking Fiscal Status (Step 5)

> `[CONTRACT]` Precondition: the order must have a `chaveNfe` set. Calling this before issuance returns HTTP 422.

```
GET /api/app/pedidos/{pedidoId}/situacao
Authorization: Bearer {token}
```

**Response — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
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
}
```

> `[CONTRACT]` The fields `cStat`, `xMotivo`, `nProt`, and `dhRecbto` are **conditional** — present only if the fiscal document has been internally registered.

> `[CONTRACT]` The `consultaSefaz` field (raw XML from the live `consSitNFe` call) is **always present**.

| Field                                      | Presence    | Source                            |
|--------------------------------------------|-------------|-----------------------------------|
| `pedidoId`, `numero`, `status`, `chaveNfe` | Always      | Local database                    |
| `cStat`, `xMotivo`, `nProt`, `dhRecbto`    | Conditional | Table `nfe_documento` (if exists) |
| `consultaSefaz`                            | Always      | Live `consSitNFe` call to SEFAZ   |

---

### 6.6 Cancelling an NF-e (Step 6)

> `[CONTRACT]` Mandatory preconditions:
> 1. `status == "AUTORIZADO"` — the only accepted state
> 2. Authorization protocol (`nProt`) must be available in the internal fiscal document

```
POST /api/app/pedidos/{pedidoId}/cancelar
Authorization: Bearer {token}
Content-Type: application/json

{ "justificativa": "Reason with at least 15 characters" }
```

| Field           | Rule                  |
|-----------------|-----------------------|
| `justificativa` | Minimum 15 characters |

> `[EXAMPLE]` Valid justification: `"Erro no pedido — cliente solicitou cancelamento"`

**Response — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": "<retEvento>...</retEvento>"
}
```

> `[CONTRACT]` `data` is the raw XML returned by SEFAZ. After a successful cancellation, the order status is updated to `"CANCELADO"` (immutable).

**Response — HTTP 422 (precondition violated):**
```json
{ "code": 422, "message": "Cancelamento só é permitido para pedidos com status AUTORIZADO. Status atual: AGUARDANDO", "data": null }
```

---

### 6.7 Electronic Correction Letter — CC-e (Step 7)

> `[CONTRACT]` Precondition: `status == "AUTORIZADO"`. The CC-e **does not change the order status**.

```
POST /api/app/pedidos/{pedidoId}/cce
Authorization: Bearer {token}
Content-Type: application/json

{ "correcao": "Correction text with at least 15 characters" }
```

| Field      | Rule                                                           |
|------------|----------------------------------------------------------------|
| `correcao` | Minimum 15 characters · Maximum 20 CC-e per NF-e (SEFAZ limit) |

**Response — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": "<retEvento>...</retEvento>"
}
```

---

### 6.8 DANFE — Auxiliary Document of the Electronic Invoice

> `[CONTRACT]` The DANFE is the PDF companion document for an issued NF-e. It is generated from the signed XML stored in `nfe_documento`. JWT authentication required (any role).

```
GET /api/fiscal/nfe/{chaveNfe}/danfe
Authorization: Bearer {token}
```

| Parameter  | Rule                                        |
|------------|---------------------------------------------|
| `chaveNfe` | Exactly 44 numeric digits (path variable)   |

**Success response — HTTP 200:**
- `Content-Type: application/pdf`
- `Content-Disposition: attachment; filename="danfe-{chaveNfe}.pdf"`
- Body: PDF bytes

**Error responses:**

| Code | Cause                                                |
|------|------------------------------------------------------|
| 400  | Access key is not exactly 44 digits                  |
| 401  | Missing or invalid JWT token                         |
| 404  | NF-e not found in `nfe_documento` table              |
| 500  | Internal error during PDF generation                 |

> `[OPERATIONAL]` In HOM (staging, `tpAmb=2`), the DANFE displays a diagonal "SEM VALOR FISCAL" (NO FISCAL VALUE) watermark in light gray. This watermark is suppressed in PRD (`tpAmb=1`).

> `[OPERATIONAL]` The protocol label on the DANFE is conditional: `PROTOCOLO DE AUTORIZAÇÃO DE USO` when authorized (cStat=100 + nProt present); `RETORNO SEFAZ — HOMOLOGAÇÃO` in staging when not yet authorized (tpAmb=2, cStat≠100).

---

### 6.9 Recipient Manifestation (Manifestação do Destinatário)

> `[OPERATIONAL]` Recipient Manifestation is a set of fiscal events sent by the **recipient** of an NF-e to SEFAZ to formally register their position on a received invoice. It is not part of the main OMS → NF-e issuance flow.

```
POST /api/fiscal/nfe/manifestar
Authorization: Bearer {token}
Content-Type: application/json
```

**Payload:**

| Field | Type | Rule |
|---|---|---|
| `chaveNfe` | String | Required · Exactly 44 numeric digits |
| `tipoEvento` | String | Required · One of the 4 valid event codes below |
| `cnpjDestinatario` | String | Required · 14 numeric digits (no formatting) |
| `xJust` | String | Conditional · Required only for `210240` · Minimum 15 / maximum 255 characters |

**Supported event types:**

| Code | Description |
|---|---|
| `210200` | Acknowledgement of the Transaction (Ciência da Operação) |
| `210210` | Confirmation of the Transaction (Confirmação da Operação) |
| `210220` | Disclaimer of the Transaction (Desconhecimento da Operação) |
| `210240` | Transaction Not Completed (Operação Não Realizada) |

> `[EXAMPLE]` Payload for Acknowledgement of Transaction:
```json
{
  "chaveNfe":         "35260554393421000159550010000000351199116560",
  "tipoEvento":       "210200",
  "cnpjDestinatario": "54393421000159"
}
```

> `[EXAMPLE]` Payload for Transaction Not Completed (xJust required):
```json
{
  "chaveNfe":         "35260554393421000159550010000000351199116560",
  "tipoEvento":       "210240",
  "cnpjDestinatario": "54393421000159",
  "xJust":            "Goods not received by recipient as agreed"
}
```

**Success response — HTTP 200:**
```json
{
  "code":    200,
  "success": true,
  "data":    "135 - Evento registrado e vinculado a NF-e"
}
```

**Error response (invalid data or SEFAZ rejection) — HTTP 200:**
```json
{
  "code":    500,
  "success": false,
  "message": "Falha ao registrar manifestação: <error detail>"
}
```

> `[CONTRACT]` The `success: false` with `code: 500` indicates SEFAZ rejection or invalid input (malformed key, invalid event type, missing xJust for 210240). The HTTP status always returns 200; the semantic code is in the envelope.

> `[CONTRACT]` Authentication is required. A request without a token returns HTTP 401.

> `[OPERATIONAL]` **HOM Limitation:** the SEFAZ national environment (AN) endpoint may return HTTP 403 from residential/local networks. This is a SEFAZ federal infrastructure behavior, not an API error. The `/manifestar` endpoint will work normally in corporate environments and PRD.

---

## 7. Order State Machine

> `[CONTRACT]` Valid states and allowed operations per state:

| State        | Meaning                                 | Allowed operations                  |
|--------------|-----------------------------------------|-------------------------------------|
| `RASCUNHO`   | Order created, not yet transmitted      | `/emitir`                           |
| `AUTORIZADO` | NF-e approved by SEFAZ (cStat=100)      | `/situacao`, `/cancelar`, `/cce`    |
| `AGUARDANDO` | Transmitted; SEFAZ confirmation pending | `/situacao`                         |
| `REJEITADO`  | SEFAZ rejected (cStat ≥ 200)            | `/emitir` (retry on the same order, v1.9) |
| `CANCELADO`  | NF-e cancelled with protocol            | None — immutable                    |
| `ERRO`       | Technical failure during transmission   | `/emitir` (retry on the same order, v1.9) |

> `[CONTRACT]` State transitions are managed exclusively by the fiscal engine. The logistics ERP must not assume or force transitions.

> `[CONTRACT]` Since v1.9, `REJEITADO` and `ERRO` are no longer terminal via the API — `POST /emitir` can be called again on the same `pedidoId` at any time after fixing the cause (see section 6.4). Only `AUTORIZADO` and `CANCELADO` are terminal.

---

## 8. API Response Format

### 8.1 Standard Success Envelope

> `[CONTRACT]` All `/api/**` endpoints return the `Result<>` envelope, **except** `/auth/login`, `/api/test/ping` and `/api/integration/fiscal-authorizations`, which have their own structure (documented in sections 3.1, 3.3 and 6.1).

```json
{ "code": 200, "message": "Sucesso", "data": { ... } }
```

### 8.2 Error Responses

> `[CONTRACT]` HTTP 401 and 403 errors have a different structure — a `"success": false` field instead of `"data"`:

```json
{ "code": 401, "message": "Autenticação necessária", "success": false }
{ "code": 403, "message": "Acesso negado",           "success": false }
```

> `[CONTRACT]` All other errors follow the standard envelope with `"data": null`:

```json
{ "code": 400, "message": "Error description",        "data": null, "retryable": false }
{ "code": 404, "message": "Recurso não encontrado",   "data": null, "retryable": false }
{ "code": 422, "message": "Error description",        "data": null, "retryable": false }
{ "code": 500, "message": "Erro interno do servidor", "data": null, "retryable": false }
```

> `[CONTRACT]` Field validation errors (`@Valid`) return HTTP 422 with the **error map in `data`**:

```json
{
  "code": 422,
  "message": "Dados inválidos",
  "data": {
    "destCnpjCpf": "CNPJ/CPF do destinatário é obrigatório"
  }
}
```

**Reference table:**

| HTTP       | Trigger                                                | `data` structure                     |
|------------|--------------------------------------------------------|--------------------------------------|
| 400        | Malformed JSON or invalid business parameter           | `null`                               |
| 401        | Token absent or expired                                | `"success": false` field (no `data`) |
| 403        | Insufficient role                                      | `"success": false` field (no `data`) |
| 404        | ID not found for the authenticated company             | `null`                               |
| 422 @Valid | Invalid entity field                                   | `{ "field": "message" }`             |
| 422 state  | Operation not allowed in current state                 | `null`                               |
| 500        | Unhandled failure (including SEFAZ transmission error) | `null`                               |

### 8.2a Business Error Codes (`errorCode`)

> `[CONTRACT]` Business errors include an `errorCode` field alongside the standard `code` and `message` fields. Use `errorCode` as the stable programmatic identifier — **do not parse the `message` field**, which is in Brazilian Portuguese and may change.

**Error response with `errorCode`:**
```json
{
  "code":      422,
  "message":   "Estoque insuficiente para \"Product A\" (disponível: 0.0000, solicitado: 5.00)",
  "data":      null,
  "errorCode": "INSUFFICIENT_STOCK"
}
```

> `[CONTRACT]` The `errorCode` field is present **only in business error responses**. Successful responses (`code: 200`) do not include it.

> `[CONTRACT]` Since v1.9, every error response (not only business ones) includes the boolean field `retryable`. `retryable: true` means resending the exact same request is safe (transient failure — timeout, unavailability). `retryable: false` means the underlying data/cause must be fixed first — resending unchanged repeats the same error. **Do not infer retry behavior from `message`** (free-text Portuguese) — always use `retryable`.

**Defined error codes:**

| `errorCode`            | HTTP | `retryable` | Trigger                                                                               |
|------------------------|------|-------------|------------------------------------------------------------------------------------------|
| `INVALID_ORDER_STATUS` | 422  | false | Order is not in the expected state for the operation (e.g., not `RASCUNHO`/`REJEITADO`/`ERRO` for `/emitir`, not `AUTORIZADO` for `/cancelar` or `/cce`) |
| `INSUFFICIENT_STOCK`   | 422  | false | Available stock (`estoqueDisponivel`) is less than the requested quantity for an item — does not occur for companies with stock control disabled (see note below) |
| `PRODUCT_NOT_FOUND`    | 422  | false | An item references a `produtoId` that does not exist for the authenticated company    |
| `PRODUCT_INACTIVE`          | 422                          | false | An item references a product with `estado = 0` (inactive)                                                                         |
| `VALIDATION_ERROR`          | 207 `resultados[].errorCode` | false | Batch item failed field validation — invalid NCM, blank required field, or price ≤ 0                                              |
| `BATCH_LIMIT_EXCEEDED`      | 422                          | false | Batch request contains more than 200 products — returned before any item is processed                                             |
| `DUPLICATE_CODIGO_IN_BATCH` | 207 `resultados[].errorCode` | false | The same `codigo` appears more than once in the same batch payload — the second occurrence is rejected; the first is processed   |
| `INVALID_API_KEY`           | 401                          | false | `X-Api-Key` header missing, invalid, expired, or revoked — required for `POST /api/integration/fiscal-authorizations` |
| `COMPANY_INACTIVE`          | 422                          | false | Company with the given CNPJ exists in Borurio but is marked inactive — contact the ADMIN to reactivate it |
| `INVALID_CERTIFICATE`       | 422                          | false | Invalid A1 certificate — malformed base64, corrupted PKCS12, wrong password, or no X.509 certificate found in the file |
| `CNPJ_CERTIFICATE_MISMATCH` | 422                          | false | CNPJ sent in the payload does not match the CNPJ embedded in the X.509 certificate Subject |
| `CERTIFICATE_EXPIRED`       | 422                          | false | A1 certificate is expired — replace with the renewed certificate and reauthorize |
| `AUTHORIZATION_REVOKED`     | 401                          | false | OMS token has been administratively revoked — reauthorize with a valid certificate or wait for ADMIN resolution |
| `CNPJ_NOT_AUTHORIZED`       | 403                          | false | CNPJ provided in `cnpjEmitente` has no active authorization for this OMS client — call `POST /api/integration/fiscal-authorizations` with that CNPJ's certificate before issuing |
| `CERT_NOT_FOUND_FOR_CNPJ`   | 422                          | false | No active certificate found for the issuing CNPJ — verify that the fiscal authorization was completed for that CNPJ |
| `EMITTER_ADDRESS_INCOMPLETE` | 422 | false | **(v1.9)** Emitter company registration is missing address data (`logradouro`/`numero`/`bairro`/`codigoMunicipio`/`municipio`/`cep`) — blocked before ever calling SEFAZ. Send the `emit*` fields in `POST /api/app/pedidos` (section 6.3) and retry. |
| `SEFAZ_REJECTED`            | 422 | false | **(v1.9)** SEFAZ processed the call and rejected the NF-e (cStat ≥ 200). `data.cStat`/`data.xMotivo` carry the real reason. Usually bad data — fix it and call `/emitir` again on the same order. |
| `SEFAZ_TIMEOUT`             | 503 | **true** | **(v1.9)** Timeout calling SEFAZ — transient network failure. |
| `SEFAZ_UNAVAILABLE`         | 503 | **true** | **(v1.9)** SEFAZ unreachable (connection refused/DNS) — transient network failure. |
| `XML_SCHEMA_INVALID`        | 422 | false | **(v1.9)** Generated XML failed local schema validation before being signed/transmitted — a data problem, not a network one. |

> `[OPERATIONAL]` The OMS must use `errorCode` for all conditional logic. The `message` field is intended for human-readable logs only. The HTTP status alone is not sufficient to distinguish between `INSUFFICIENT_STOCK`, `PRODUCT_NOT_FOUND`, and `PRODUCT_INACTIVE`, all of which return HTTP 422.

> `[CONTRACT]` Stock control is optional per company. By default, every company validates, reserves, and writes off stock normally in `/emitir` — unchanged behavior. For OMS clients that don't track stock, Borurio can disable this validation per company (internal configuration, not exposed via the integration API). When disabled, `/emitir` never returns `INSUFFICIENT_STOCK` and the product balance is never changed at any step (issuance, rejection, or cancellation).

---

### 8.3 Portuguese Message Translation Reference

> `[OPERATIONAL]` The `message` field in all API responses is in **Brazilian Portuguese**. The table below provides the English translation for every fixed message the integration may receive.

| Portuguese message (as returned by the API) | English meaning | Typical trigger |
|---|---|---|
| `Autenticação bem-sucedida` | Authentication successful | Valid login |
| `Credenciais inválidas` | Invalid credentials | Wrong email or password |
| `Erro interno de autenticação` | Internal authentication error | Unexpected server error during login |
| `Autenticação necessária` | Authentication required | Missing or expired Bearer token |
| `Acesso negado` | Access denied | Role `OPERADOR` accessing an `ADMIN`-only route |
| `Sucesso` | Success | Any successful API response |
| `Registro não encontrado` | Record not found | `GET` by ID for a non-existent resource |
| `Recurso não encontrado` | Resource not found | Path variable resolves to an empty result |
| `Erro interno do servidor` | Internal server error | Unhandled exception (including SEFAZ errors) |
| `Dados inválidos` | Invalid data | `@Valid` bean validation failure (HTTP 422) |
| `Falha na operação` | Operation failed | Business rule violation |
| `Requisição inválida` | Invalid request | Malformed request body |
| `CNPJ/CPF do destinatário é obrigatório` | Recipient CNPJ/CPF is required | Missing `destCnpjCpf` field in order creation |
| `Razão social do destinatário é obrigatória` | Recipient company name is required | Missing `destRazaoSocial` field |
| `UF do destinatário é obrigatória` | Recipient state (UF) is required | Missing `destUf` field |

> `[OPERATIONAL]` Validation error messages (HTTP 422) are returned in `data` as a field-keyed map. Each key is the JSON field name that failed, and each value is the Portuguese validation message. Use the table above to translate them.

---

### 8.4 Pagination

> `[CONTRACT]` Listing endpoints accept `page` (0-based) and `size` (max 100):

```
GET /api/app/pedidos?page=0&size=20
GET /api/app/produtos?page=0&size=20
```

**Response:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "content":       [ ... ],
    "page":          0,
    "size":          20,
    "totalElements": 150,
    "totalPages":    8,
    "first":         true,
    "last":          false
  }
}
```

> `[OPERATIONAL]` The `GET /api/app/pedidos` endpoint (paginated list) **does not include the items of each order**. To retrieve items, use `GET /api/app/pedidos/{id}`.

---

### 8.5 Request Tracing — `X-Request-Id`

> `[OPERATIONAL]` Every API response includes an `X-Request-Id` header containing a UUID that uniquely identifies the request in the server logs.

**Behavior:**
- If the OMS sends an `X-Request-Id` header in the request, the system uses that value and echoes it back in the response
- If the header is absent or blank, the system generates a random UUID automatically
- The value appears in all server-side log lines for that request — enabling end-to-end correlation

> `[OPERATIONAL]` To correlate OMS logs with API server logs, include a correlation ID in the request:
```
POST /api/app/produtos/batch
X-Request-Id: oms-batch-20260601-001
```
Response header will contain: `X-Request-Id: oms-batch-20260601-001`

> `[OPERATIONAL]` Business error responses also include `requestId` in the JSON body:
```json
{
  "code":      422,
  "message":   "O lote excede o limite máximo de 200 produtos por requisição.",
  "data":      null,
  "errorCode": "BATCH_LIMIT_EXCEEDED",
  "requestId": "oms-batch-20260601-001"
}
```

> `[OPERATIONAL]` HTTP 401 responses include `X-Request-Id` in the response header even before authentication is resolved — allowing the OMS to correlate failed authentication attempts with server-side logs.

---

## 9. Smoke Test — HOM Environment

> `[CONTRACT]` The sequence below must be executed and validated in HOM before integrating in PRD.

### 9.1 Validation Sequence

| # | Request                                                | PASS criterion                               |
|---|--------------------------------------------------------|----------------------------------------------|
| 1 | `GET /api/test/ping`                                   | HTTP 200 · `status = "UP"`                   |
| 2 | `POST /auth/login`                                     | HTTP 200 · `token` not null                  |
| 3 | `POST /api/app/produtos`                               | HTTP 200 · `data.id` returned                |
| 4 | `GET /api/app/produtos?page=0&size=5`                  | HTTP 200 · `data.totalElements ≥ 1`          |
| 5 | `POST /api/app/pedidos` (with `produtoId` from step 3) | HTTP 200 · `data.status = "RASCUNHO"`        |
| 6 | `POST /api/app/pedidos/{id}/emitir`                    | HTTP 200 · `data.soapRetorno` not empty      |
| 7 | `GET /api/app/pedidos/{id}/situacao`                   | HTTP 200 · `data.chaveNfe` populated         |
| 8  | `GET /api/app/pedidos/{id}`                                           | HTTP 200 · `data.itens` with snapshot fields                        |
| 9  | `POST /api/app/produtos/batch` (1 new product, `codigo: "BATCH-001"`) | HTTP 207 · `criados=1` · `resultados[0].status = "CRIADO"`         |
| 10 | `POST /api/app/produtos/batch` (same product again)                   | HTTP 207 · `atualizados=1` · `resultados[0].status = "ATUALIZADO"` |
| 11 | Inspect response headers of any request                               | `X-Request-Id` header present · UUID format                         |

### 9.1b OMS Multi-CNPJ Smoke Test

> `[OPERATIONAL]` Run this complementary sequence to validate the multi-CNPJ flow when the OMS client has more than one authorized CNPJ.

| # | Request | PASS criterion |
|---|---|---|
| M1 | `POST /api/integration/fiscal-authorizations` — CNPJ1 of the OMS client | HTTP 200 · `token` returned · company auto-created |
| M2 | `POST /api/integration/fiscal-authorizations` — CNPJ2 of the **same** `codigoEmpresaOms` | HTTP 200 · **same `token`** as M1 |
| M3 | `POST /api/integration/fiscal-authorizations` — CNPJ1, same cert re-sent (scenario B) | HTTP 200 · same token · no database change |
| M4 | `POST /api/app/pedidos` with `cnpjEmitente: "{CNPJ2}"` using the OMS token | HTTP 200 · `data.cnpjEmitente` = CNPJ2 · `data.status = "RASCUNHO"` |
| M5 | `POST /api/app/pedidos/{id}/emitir` for order from M4 | HTTP 200 · NF-e signed with CNPJ2's certificate |
| M6 | `POST /api/app/pedidos` with `cnpjEmitente: "{CNPJ3-not-authorized}"` | HTTP 403 · `errorCode: "CNPJ_NOT_AUTHORIZED"` |

> `[CONTRACT]` Test M2 is the most important acceptance criterion for the multi-CNPJ flow: it confirms that the token does not change when a second CNPJ is added to an existing OMS client.

### 9.1c Emitter Address & Reissue Smoke Test (v1.9)

> `[OPERATIONAL]` Run this sequence when first authorizing a new company, or to validate the reissue flow after a rejection.

| # | Request | PASS criterion |
|---|---|---|
| R1 | `POST /api/app/pedidos/{id}/emitir` for a new company (address still incomplete) | HTTP 422 · `errorCode: "EMITTER_ADDRESS_INCOMPLETE"` · `retryable: false` · SEFAZ is never called |
| R2 | `POST /api/app/pedidos` with `emit*` fields filled in, same company | HTTP 200 · company registration completed (check via `GET /api/app/empresas/{id}` if you have ADMIN access) |
| R3 | `POST /api/app/pedidos/{id}/emitir` for the order created in R2 | HTTP 200 (`AUTORIZADO`/`AGUARDANDO`) or HTTP 422 `SEFAZ_REJECTED` — but **no longer** `EMITTER_ADDRESS_INCOMPLETE` |
| R4 | If R3 returned `SEFAZ_REJECTED` or the order ended up `ERRO`: `POST /api/app/pedidos/{id}/emitir` **on the same `pedidoId`** | HTTP 200 or a new `SEFAZ_REJECTED` — `chaveNfe` different from the previous attempt · no need to create a new order |
| R5 | `POST /api/app/pedidos/{id}/emitir` for an `AUTORIZADO` order | HTTP 422 · `errorCode: "INVALID_ORDER_STATUS"` · `retryable: false` |

> `[CONTRACT]` Test R4 is the acceptance criterion for the reissue flow: it confirms `REJEITADO`/`ERRO` are not terminal and the same `pedidoId` can be reused.

### 9.2 Security Checks

| Request                                                          | Expected result                                           |
|------------------------------------------------------------------|-----------------------------------------------------------|
| `GET /api/app/pedidos` without `Authorization`                   | HTTP 401 · `"Autenticação necessária"` · `success: false` |
| `GET /api/app/usuarios` with `OPERADOR` role token               | HTTP 403 · `"Acesso negado"` · `success: false`           |
| `GET /api/app/pedidos` with company B token                      | HTTP 200 · `data.content = []` (tenant isolation)         |
| `POST /api/app/pedidos/9999/emitir`                              | HTTP 404 · `data: null`                                   |
| `POST /api/app/pedidos/{id}/cancelar` with empty `justificativa` | HTTP 400 · minimum 15 characters message                  |

### 9.3 Expected Behavior in HOM-SP

> `[OPERATIONAL]` **2026-07-14 correction:** the root cause of `cStat=225` was identified and fixed — the engine was signing the XML with the wrong signature algorithm (RSA-SHA256, when the SEFAZ's current official XMLDSig schema requires RSA-SHA1). With the fix, the expected result in HOM-SP for a company with fiscal registration accepted by SEFAZ during the staging issuance is `cStat=100` (`AUTORIZADO`) — confirmed with the currently qualified emitting company, including in a test run by the Chinese integrator via the OMS. `cStat=225` is no longer the expected behavior; if it occurs, treat it as a real rejection and inspect `data.xMotivo`. Since v1.9, `cStat≥200` results in `REJEITADO` and `POST /emitir` returns HTTP 422 with `errorCode: SEFAZ_REJECTED` — not HTTP 200 (see section 6.4).

> `[OPERATIONAL]` The `verAplic` in the SEFAZ response indicates which processor answered (e.g., `SP_NFE_PL_008i2`, `SP_NFE_PL009_V4`) — it can differ between the batch level (`retEnviNFe`) and the individual protocol level (`protNFe/infProt`) in the same response. That difference alone is not, by itself, evidence of an error — `xMotivo` in `data.xMotivo` is the reliable source for the real reason behind any rejection.

Expected result of `POST /api/app/pedidos/{id}/emitir` in HOM-SP for a company with fiscal registration accepted by SEFAZ during the staging issuance:

```
HTTP 200
data.chaveNfe: <44 digits>
pedido.status: AUTORIZADO
data.cStat:    100
data.xMotivo:  "Autorizado o uso da NF-e"
```

If the NF-e is rejected (bad data, invalid/revoked state tax ID, etc.), the result is:

```
HTTP 422
errorCode: SEFAZ_REJECTED
data.cStat:    <code returned by SEFAZ>
data.xMotivo:  <reason returned by SEFAZ>
pedido.status: REJEITADO → can be reissued on the same order after fixing the cause (v1.9)
```

> `[CONTRACT]` To validate the complete flow in HOM, inspect the raw `data.soapRetorno` from `/emitir` (when HTTP 200) or `data.cStat`/`data.xMotivo` (when `SEFAZ_REJECTED`), and `data.consultaSefaz` from `/situacao` — these fields contain the actual SEFAZ response regardless of the final order status.

---

## 10. Staging Observations

| # | Observation                                              | Impact                                              |
|---|----------------------------------------------------------|-----------------------------------------------------|
| 1 | `cStat=225` — root cause fixed on 2026-07-14 (signature algorithm); the expected result is now `cStat=100` for a company with fiscal registration accepted by SEFAZ during the staging issuance | If `cStat=225` still occurs, treat it as a real rejection and check `data.xMotivo` |
| 2 | The `"environment"` field in `/ping` reflects the active Spring profile | Use only as a diagnostic indicator, not as a routing discriminator |
| 3 | Token expires in 1 hour                                  | Implement renewal for long-running flows            |
| 4 | Paginated order list does not include items              | Always use `GET /{id}` to retrieve items            |
| 5 | CC-e and cancellation require `nProt` to be available    | Call `/situacao` before cancelling after AGUARDANDO |
| 6 | Product with `estado=0` is rejected in orders            | Verify `estado` before referencing a product        |
| 7 | `REJEITADO` and `ERRO` are no longer terminal (v1.9)      | Reissue on the same order via `/emitir` after fixing the cause — do not create a new order |
| 8  | AN HOM endpoint may return HTTP 403 on local networks       | SEFAZ federal infrastructure limitation — does not affect PRD or the main OMS flow      |
| 9  | `POST /batch` always returns HTTP 207 — even when all items succeed | Do not treat HTTP 207 as an error — inspect `resultados[].status` per item |
| 10 | Every response includes `X-Request-Id` in the response header | Use it to correlate OMS requests with API server logs for troubleshooting |
| 11 | OMS token does not change when a new CNPJ is added (scenarios B, C, D) | The OMS does not need to update the stored token when expanding CNPJ coverage for a client |
| 12 | `cnpjEmitente` omitted in OMS order uses the anchor CNPJ (first authorized) | OMS clients with a single CNPJ can omit this field with no impact |
| 13 | Company auto-created at OMS authorization has no address (only what the certificate carries) | Send `emit*` fields in `POST /api/app/pedidos` (section 6.3) to complete the registration |
| 14 | Every error now includes the `retryable` field (v1.9) | Use this field to decide automatic retry — do not parse `message` |

---

## 11. Changelog

| Version | Date       | Change                                                                                      |
|---------|------------|---------------------------------------------------------------------------------------------|
| 1.11    | 2026-07-22 | **No API payload change.** Confirmed and documented: `modFrete` is not sent by the OMS in this version — Borurio internally defines `modFrete=2` (Third Party) for the current OMS/marketplace flow, reflecting that the marketplace contracts the freight (confirmed by the Chinese integrator). OMS token revocation/rotation moves from an operational description to an implemented and HOM-validated mechanism: active authorization checked on every request (revocation takes effect immediately, independent of JWT expiry); admin endpoints (`/api/admin/oms-authorizations/**`) confirmed outside the OMS's public contract. New real authorization obtained in HOM (`cStat=100`) with `modFrete=2` confirmed in the transmitted XML. This entry catches up the English translation, which had fallen behind versions 1.10 (PT-BR only) and 1.9.2 (PT-BR only) — see `INTEGRATION_CONTRACT_PT-BR.md` for that intermediate history. |
| 1.9.1   | 2026-07-14 | Documentation correction (no API contract change): the root cause of `cStat=225` was identified and fixed in the fiscal engine (RSA-SHA1/SHA-1 signature algorithm, per the SEFAZ's current official XMLDSig schema, instead of RSA-SHA256). Expected result in HOM/SP is now `cStat=100` for a company with fiscal registration accepted by SEFAZ during the staging issuance — confirmed with the currently qualified emitting company in an internal test and in a test run by the Chinese integrator via the OMS. Sections 9.3 and 10 corrected — `cStat=225` is no longer described as expected behavior/environment limitation. Full details in `MTF-001_motor-fiscal-nfe_EN.md` section 13. |
| 1.9     | 2026-07-10 | **Staging session with CC — 3 OMS integration improvements:** (1) `POST /emitir` now accepts reissue on the same order for `REJEITADO`/`ERRO` (not only `RASCUNHO`) — each attempt generates a fresh `chaveNfe`; (2) `POST /api/app/pedidos` accepts an optional emitter address (`emitLogradouro`/`emitNumero`/`emitBairro`/`emitCodigoMunicipio`/`emitMunicipio`/`emitCep`) to auto-complete the company registration when incomplete (companies auto-created via certificate have no address); (3) every error now includes `retryable` (boolean) and new `errorCode` values: `EMITTER_ADDRESS_INCOMPLETE`, `SEFAZ_REJECTED`, `SEFAZ_TIMEOUT`, `SEFAZ_UNAVAILABLE`, `XML_SCHEMA_INVALID`. `POST /emitir` no longer returns HTTP 200 when SEFAZ rejects the NF-e — it returns HTTP 422 `SEFAZ_REJECTED` with `data.cStat`/`data.xMotivo`. Section 9.3 corrected: `cStat=225` is no longer described as a "HOM-only limitation" — it is a real rejection that can indicate bad data (found during a real staging session with an OMS client: emitter registration missing an address). |
| 1.8     | 2026-07-10 | Stock control is now optional per company (`controleEstoqueAtivo`, internal configuration, active by default). Companies with the flag disabled never receive `INSUFFICIENT_STOCK` in `/emitir` and never have their balance changed at any step (reservation, write-off, reversal, or cancellation). No behavior change for existing companies. |
| 1.7     | 2026-06-22 | **OMS Multi-CNPJ (V028):** an OMS client (`codigoEmpresaOms`) can authorize multiple CNPJs under a single token. Company auto-created from X.509 Subject (no ADMIN pre-registration required). `cnpjEmitente` field added to order for certificate selection at issuance. Behavior per scenario (A/B/C/D) documented — token never changes in scenarios B, C, D. New `errorCode` values: `COMPANY_INACTIVE`, `CNPJ_NOT_AUTHORIZED`, `CERT_NOT_FOUND_FOR_CNPJ`. Removed: `COMPANY_NOT_FOUND` (company is now auto-created). OMS multi-CNPJ smoke test added (section 9.1b). |
| 1.6.1   | 2026-06-18 | Documentation fix: `POST /api/integration/fiscal-authorizations` response **does not use** the `Result<>` envelope — DTO returned directly at the root (`token`, `empresaId`, `cnpj`, `razaoSocial`, `tokenExpiraEm`). Sections 3.3 and 8.1 corrected. |
| 1.6     | 2026-06-17 | OMS Session via A1 Certificate — `POST /api/integration/fiscal-authorizations` with `X-Api-Key` header; no user login for the OMS; one technical token per company; reauthorization (certificate replacement) and revocation documented. New `errorCode` values: `INVALID_API_KEY`, `COMPANY_NOT_FOUND`, `INVALID_CERTIFICATE`, `CNPJ_CERTIFICATE_MISMATCH`, `CERTIFICATE_EXPIRED`, `AUTHORIZATION_REVOKED`. |
| 1.5     | 2026-06-10 | `POST /api/app/pedidos` — fiscal fields (`codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`) are now **required** in each item and must be sent by the OMS. The system no longer copies fiscal data from the product catalog. Missing field returns HTTP 400. |
| 1.4     | 2026-06-01 | Added `X-Request-Id` traceability header; idempotency via `externalOrderId`; batch upsert (`POST /api/app/produtos/batch`); Manifestação do Destinatário endpoints. |
| 1.3     | 2026-05-27 | Added `cfop` optional on product (`POST /api/app/produtos`); `M3` certificate password rotation; `DANFE` generation clarifications. |
| 1.2     | 2026-05-18 | Added stock control (atomic reservation); `Fase 12-B` DANFE; multi-tenant isolation confirmed in HOM. |
