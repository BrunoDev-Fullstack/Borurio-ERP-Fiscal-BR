# Integration Document
## Borurio BR Fiscal Engine — REST API
### ERP Logistics × NF-e 4.00 SEFAZ-SP

| Attribute             | Value                                   |
|-----------------------|-----------------------------------------|
| Version               | 1.5                                     |
| Status                | **Approved for integration**            |
| Validation date       | 2026-06-10                              |
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
| `cfop`      | String  | Optional · 4 numeric digits · defaults to `"5102"` (intra-state) if omitted |
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
| `externalOrderId`     | String | Recommended · Max 100 chars · Unique per company · Enables idempotent retry |

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
| `csosn`          | String  | Required · Simples Nacional code (e.g. `"102"`, `"400"`, `"500"`, `"900"`) |

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

**Defined error codes:**

| `errorCode`            | HTTP | Trigger                                                                               |
|------------------------|------|---------------------------------------------------------------------------------------|
| `INVALID_ORDER_STATUS` | 422  | Order is not in the expected state for the operation (e.g., not `RASCUNHO` for `/emitir`, not `AUTORIZADO` for `/cancelar` or `/cce`) |
| `INSUFFICIENT_STOCK`   | 422  | Available stock (`estoqueDisponivel`) is less than the requested quantity for an item |
| `PRODUCT_NOT_FOUND`    | 422  | An item references a `produtoId` that does not exist for the authenticated company    |
| `PRODUCT_INACTIVE`          | 422                          | An item references a product with `estado = 0` (inactive)                                                                         |
| `VALIDATION_ERROR`          | 207 `resultados[].errorCode` | Batch item failed field validation — invalid NCM, blank required field, or price ≤ 0                                              |
| `BATCH_LIMIT_EXCEEDED`      | 422                          | Batch request contains more than 200 products — returned before any item is processed                                             |
| `DUPLICATE_CODIGO_IN_BATCH` | 207 `resultados[].errorCode` | The same `codigo` appears more than once in the same batch payload — the second occurrence is rejected; the first is processed   |

> `[OPERATIONAL]` The OMS must use `errorCode` for all conditional logic. The `message` field is intended for human-readable logs only. The HTTP status alone is not sufficient to distinguish between `INSUFFICIENT_STOCK`, `PRODUCT_NOT_FOUND`, and `PRODUCT_INACTIVE`, all of which return HTTP 422.

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
| 8  | AN HOM endpoint may return HTTP 403 on local networks       | SEFAZ federal infrastructure limitation — does not affect PRD or the main OMS flow      |
| 9  | `POST /batch` always returns HTTP 207 — even when all items succeed | Do not treat HTTP 207 as an error — inspect `resultados[].status` per item |
| 10 | Every response includes `X-Request-Id` in the response header | Use it to correlate OMS requests with API server logs for troubleshooting |

---

## 11. Changelog

| Version | Date       | Change                                                                                      |
|---------|------------|---------------------------------------------------------------------------------------------|
| 1.5     | 2026-06-10 | `POST /api/app/pedidos` — fiscal fields (`codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`) are now **required** in each item and must be sent by the OMS. The system no longer copies fiscal data from the product catalog. Missing field returns HTTP 400. |
| 1.4     | 2026-06-01 | Added `X-Request-Id` traceability header; idempotency via `externalOrderId`; batch upsert (`POST /api/app/produtos/batch`); Manifestação do Destinatário endpoints. |
| 1.3     | 2026-05-27 | Added `cfop` optional on product (`POST /api/app/produtos`); `M3` certificate password rotation; `DANFE` generation clarifications. |
| 1.2     | 2026-05-18 | Added stock control (atomic reservation); `Fase 12-B` DANFE; multi-tenant isolation confirmed in HOM. |
