# Integration Document
## Borurio BR Fiscal Engine — REST API
### ERP Logistics × NF-e 4.00 SEFAZ-SP

| Attribute             | Value                                   |
|-----------------------|-----------------------------------------|
| Version               | 1.2                                     |
| Status                | **Approved for integration**            |
| Validation date       | 2026-05-22                              |
| Reference environment | HOM — `https://hom-api.borurio.com`     |
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

> `[OPERATIONAL]` The HOM environment is currently validated locally at `http://localhost:8081`. External access is planned through `https://hom-api.borurio.com` (Cloudflare Tunnel, HTTPS, TLS 1.3) — no VPN or SSH tunnel will be required on the integration team's side once the tunnel is active. **This setup is pending** and will be completed by Bruno/Ops according to `ROTEIRO_ENTREGA_TIME_CHINES.md` — Block 1. Until confirmed, do not expect the external URL to respond; use the documentation and Postman collection for review in the meantime. Bruno will notify when `GET https://hom-api.borurio.com/api/test/ping` returns `"status": "UP"`.

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

## 4. Multi-Company Context (Multitenancy)

> `[CONTRACT]` The `empresaId` **is not sent in request bodies.** It is extracted automatically from the JWT token by the system.

**How it works internally:**

1. At login, the user's `empresaId` is embedded in the token as claim `"eid"`
2. On each request, the JWT filter extracts `"eid"` and associates it with the request context
3. All product, order, and customer endpoints automatically scope data by company
4. The context is cleared after each request — no risk of leakage between calls

> `[CONTRACT]` A token issued for company A **only accesses** products, orders, and customers of company A. Attempts to access resources from another company return HTTP 404 (resource not found for the authenticated company).

> `[OPERATIONAL]` Each partner company must have its own user with distinct credentials. Do not share tokens between companies.

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
                          │   exception  → ERRO (500)   │
                          └─────────────────────────────┘

AUTORIZADO ──► POST /cancelar ──► CANCELADO   (immutable)
AUTORIZADO ──► POST /cce      ──► AUTORIZADO  (status unchanged)
AGUARDANDO ──► GET  /situacao ──► (check current cStat)
REJEITADO  ──► (create a new corrected order)
ERRO       ──► (check logs, evaluate manual retry)
```

> `[OPERATIONAL]` The `cStat` values in the diagram above refer to the **individual NF-e response** (`infProt/cStat`) returned inside the SOAP envelope — not the batch-level code (`retEnviNFe/cStat`). In HOM-SP specifically, the batch is accepted with `cStat=104` (AGUARDANDO) even though the individual NF-e entry inside the same response shows `cStat=225`. The system reads the batch result first: `cStat=104 → AGUARDANDO`. Therefore **do not expect `REJEITADO` when you see `cStat=225` in HOM** — the order will be `AGUARDANDO`. See section 9.3 for the full HOM-SP behavior.

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
| `cfop`      | String  | Required · Exactly 4 numeric digits                      |
| `unidade`   | String  | Required · E.g.: `UN`, `KG`, `PC`, `CX`                  |
| `preco`     | Decimal | Required · Value > 0.01                                  |
| `origem`    | Integer | Required · `0`=Domestic · `1` to `8`=Imported            |
| `csosn`     | String  | Optional · If omitted, item snapshot defaults to `"400"` |

> `[CONTRACT]` CFOP reference: intra-state operation: `5102` · interstate operation: `6102`.

> `[CONTRACT]` CSOSN reference (Simples Nacional): `102`=no ST no credit · `400`=non-contributor · `500`=ICMS previously collected · `900`=other.

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

### 6.2b Stock Management (Inventory)

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

### 6.3 Creating an Order — RASCUNHO (Step 3)

> `[CONTRACT]` A newly created order always starts in the `RASCUNHO` state. Fields such as `status`, `chaveNfe`, `numero`, and `cnpjEmitente` are populated automatically — **do not send them in the body**.

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

**Per-item fields (`itens[]`):**

| Field           | Type    | Rule                                        |
|-----------------|---------|---------------------------------------------|
| `produtoId`     | Long    | Required · Product must exist and be active |
| `quantidade`    | Decimal | Required · Value > 0                        |
| `valorUnitario` | Decimal | Required · Value > 0                        |

> `[CONTRACT]` Each item's `valorTotal` is calculated automatically as `quantidade × valorUnitario`. The order total is the sum of all items. Do not send these fields.

> `[EXAMPLE]` Minimum valid payload:
```json
{
  "destCnpjCpf":         "12345678000195",
  "destRazaoSocial":     "Destination Company Ltd",
  "destUf":              "SP",
  "destCodigoMunicipio": "3550308",
  "destMunicipio":       "São Paulo",
  "itens": [
    { "produtoId": 7, "quantidade": 2, "valorUnitario": 100.00 }
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

> `[CONTRACT]` Precondition: the order must be in `RASCUNHO` state. Any other state returns HTTP 422.

```
POST /api/app/pedidos/{pedidoId}/emitir
Authorization: Bearer {token}
```

**Response — HTTP 200 (transmission processed):**
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

> `[CONTRACT]` HTTP 200 indicates that the SEFAZ call was processed — **it does not mean the NF-e was authorized.** The actual status is in the order's `status` field (check via `GET /api/app/pedidos/{id}` or `/situacao`).

> `[CONTRACT]` The `data.chaveNfe` field will be an empty string `""` (not `null`) when SEFAZ does not return an access key.

**Response — HTTP 422 (precondition violated):**
```json
{ "code": 422, "message": "Pedido não está em RASCUNHO. Status atual: AUTORIZADO", "data": null }
```

**Response — HTTP 500 (transmission failure):**
```json
{ "code": 500, "message": "Erro interno do servidor", "data": null }
```

> `[OPERATIONAL]` HTTP 500 on `/emitir` means the order was automatically moved to the `"ERRO"` state. Check the order status via `GET /api/app/pedidos/{id}` before any retry attempt.

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

## 7. Order State Machine

> `[CONTRACT]` Valid states and allowed operations per state:

| State        | Meaning                                 | Allowed operations                  |
|--------------|-----------------------------------------|-------------------------------------|
| `RASCUNHO`   | Order created, not yet transmitted      | `/emitir`                           |
| `AUTORIZADO` | NF-e approved by SEFAZ (cStat=100)      | `/situacao`, `/cancelar`, `/cce`    |
| `AGUARDANDO` | Transmitted; SEFAZ confirmation pending | `/situacao`                         |
| `REJEITADO`  | SEFAZ rejected (cStat ≥ 200)            | None — create a new corrected order |
| `CANCELADO`  | NF-e cancelled with protocol            | None — immutable                    |
| `ERRO`       | Technical failure during transmission   | None via API — check logs           |

> `[CONTRACT]` State transitions are managed exclusively by the fiscal engine. The logistics ERP must not assume or force transitions.

> `[OPERATIONAL]` The `ERRO` state is terminal from the API's perspective. Recovery requires analysis of internal logs (`GET /api/fiscal/nfe/logs`).

---

## 8. API Response Format

### 8.1 Standard Success Envelope

> `[CONTRACT]` All `/api/**` endpoints return the `Result<>` envelope, **except** `/auth/login` and `/api/test/ping`, which have their own structure (documented in sections 3.1 and 6.1).

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
{ "code": 400, "message": "Error description",       "data": null }
{ "code": 404, "message": "Recurso não encontrado",  "data": null }
{ "code": 422, "message": "Error description",       "data": null }
{ "code": 500, "message": "Erro interno do servidor","data": null }
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
| 8 | `GET /api/app/pedidos/{id}`                            | HTTP 200 · `data.itens` with snapshot fields |

### 9.2 Security Checks

| Request                                                          | Expected result                                           |
|------------------------------------------------------------------|-----------------------------------------------------------|
| `GET /api/app/pedidos` without `Authorization`                   | HTTP 401 · `"Autenticação necessária"` · `success: false` |
| `GET /api/app/usuarios` with `OPERADOR` role token               | HTTP 403 · `"Acesso negado"` · `success: false`           |
| `GET /api/app/pedidos` with company B token                      | HTTP 200 · `data.content = []` (tenant isolation)         |
| `POST /api/app/pedidos/9999/emitir`                              | HTTP 404 · `data: null`                                   |
| `POST /api/app/pedidos/{id}/cancelar` with empty `justificativa` | HTTP 400 · minimum 15 characters message                  |

### 9.3 Expected Behavior in HOM-SP

> `[OPERATIONAL]` The SEFAZ-SP staging environment (`tpAmb=2`) uses schema `SP_NFE_PL_008i2`, which returns `cStat=225` ("Rejeição: Falha no Schema XML"). **This is a SEFAZ-SP HOM environment limitation and does not occur in PRD.**

Typical result of `POST /api/app/pedidos/{id}/emitir` in HOM-SP:

```
HTTP 200
data.chaveNfe:    "35260512..." (44 digits) → transmission reached SEFAZ
data.soapRetorno: contains cStat=225        → HOM limitation, not a system error
pedido.status:    "AGUARDANDO" → normal in HOM (batch accepted, cStat=104); does not occur in PRD
```

> `[CONTRACT]` To validate the complete flow in HOM, inspect the raw `data.soapRetorno` from `/emitir` and `data.consultaSefaz` from `/situacao` — these fields contain the actual SEFAZ response regardless of the final order status.

---

## 10. Staging Observations

| # | Observation                                              | Impact                                              |
|---|----------------------------------------------------------|-----------------------------------------------------|
| 1 | `cStat=225` is normal behavior in HOM-SP                 | Does not block technical flow validation            |
| 2 | The `"environment"` field in `/ping` reflects the active Spring profile | Use only as a diagnostic indicator, not as a routing discriminator |
| 3 | Token expires in 1 hour                                  | Implement renewal for long-running flows            |
| 4 | Paginated order list does not include items              | Always use `GET /{id}` to retrieve items            |
| 5 | CC-e and cancellation require `nProt` to be available    | Call `/situacao` before cancelling after AGUARDANDO |
| 6 | Product with `estado=0` is rejected in orders            | Verify `estado` before referencing a product        |
| 7 | The `ERRO` state is terminal via API                     | Contact support for manual recovery                 |
