# Documento de Integração
## Motor Fiscal Borurio BR — API REST
### ERP Logístico × NF-e 4.00 SEFAZ-SP

| Atributo               | Valor                                   |
|------------------------|-----------------------------------------|
| Versão                 | 1.7                                     |
| Status                 | **Aprovado para integração**            |
| Data de validação      | 22-06-2026                              |
| Ambiente de referência | HOM — `https://hom-api.borurio.com`     |
| Plataforma             | Spring Boot 3.3.2 · Java 17 · NF-e 4.00 |
| Validado contra        | Código-fonte + testes em HOM            |

---

## Legenda de Marcadores

> `[CONTRATO]` Regra que o integrador deve seguir obrigatoriamente. O não cumprimento resulta em erro ou comportamento indefinido.

> `[EXEMPLO]` Payload ou valor ilustrativo. Os dados reais variam por ambiente e cadastro.

> `[OPERACIONAL]` Comportamento observado em HOM ou nuance de implementação relevante para o integrador.

---

## 1. Objetivo e Escopo

Este documento descreve o contrato de integração entre o ERP logístico externo e o motor fiscal Borurio BR. Cobre exclusivamente os endpoints necessários para o fluxo completo de emissão de NF-e 4.00.

**Fora do escopo deste documento:**
- Gestão de usuários e empresas (endpoints ADMIN internos)
- Inutilização de faixa de numeração
- Auditoria de logs fiscais (disponível em `/api/fiscal/nfe/logs`)
- Endpoints legados sob `/api/fiscal/nfe/` (deprecated)

---

## 2. Ambientes

| Ambiente   | URL base                | Finalidade                                |
|------------|-------------------------|-------------------------------------------|
| DEV        | `http://localhost:8080` | Desenvolvimento local                     |
| HOM        | `https://hom-api.borurio.com` | Homologação SEFAZ-SP                 |
| PRD        | Definido por operações  | Produção — não coberto por este documento |

> `[CONTRATO]` Todos os testes de integração devem ser executados em HOM antes de qualquer operação em PRD.

> `[OPERACIONAL]` O ambiente HOM é acessível externamente em `https://hom-api.borurio.com` via Cloudflare Tunnel (HTTPS, TLS 1.3). Não é necessário configurar VPN ou túnel SSH do lado do time de integração. Verificar o acesso com `GET https://hom-api.borurio.com/api/test/ping` antes de iniciar a sequência de smoke test.

---

## 3. Autenticação

### 3.1 Obtenção do Token

> `[CONTRATO]` Toda sessão de integração começa com a obtenção de um token JWT. Sem token válido, todos os endpoints protegidos retornam HTTP 401.

```
POST /auth/login
Content-Type: application/json
```

```json
{
  "username": "usuario@empresa.com",
  "password": "senha"
}
```

> `[CONTRATO]` O campo `username` espera o **e-mail** do usuário cadastrado no sistema, não um nome de usuário livre.

**Resposta de sucesso — HTTP 200:**
```json
{
  "code": 200,
  "message": "Autenticação bem-sucedida",
  "token": "eyJhbGci..."
}
```

> `[CONTRATO]` Esta rota é a única que não usa o envelope `Result<>` padrão da API. A estrutura de resposta é própria: `{ code, message, token }`.

**Resposta de falha — HTTP 401:**
```json
{ "code": 401, "message": "Credenciais inválidas", "token": null }
```

### 3.2 Uso do Token

> `[CONTRATO]` O token deve ser enviado no header `Authorization` em todas as requisições subsequentes:

```
Authorization: Bearer eyJhbGci...
```

> `[CONTRATO]` O token expira após **1 hora** (padrão de `security.jwt.expiration-ms`). Após expiração, todos os endpoints retornam HTTP 401 e um novo login é necessário.

> `[OPERACIONAL]` Implementar renovação de token antes da expiração para fluxos de longa duração (ex: importação em lote).

---

### 3.3 Sessão OMS — Autorização Fiscal por Certificado A1

> `[CONTRATO]` O ERP logístico externo (OMS) **não utiliza** `POST /auth/login` para se autenticar. O OMS abre sessão via **autorização fiscal**, enviando o certificado A1 da empresa emitente e recebendo um token técnico de longa duração.

> `[CONTRATO]` **Não é necessário pré-cadastrar a empresa no Borurio.** O sistema cria automaticamente o registro da empresa a partir dos dados do Subject do certificado X.509 (razão social extraída do campo `CN=`, UF do campo `ST=`). Apenas empresas com cadastro inativo bloqueiam a autorização — nesse caso o OMS recebe `COMPANY_INACTIVE`.

#### Modelo multi-CNPJ

> `[CONTRATO]` Um cliente OMS (`codigoEmpresaOms`) pode autorizar **múltiplos CNPJs** com um único token. O token identifica o cliente OMS, não um CNPJ específico. Cada CNPJ requer uma chamada separada a este endpoint — o token retornado é sempre o mesmo para o mesmo `codigoEmpresaOms`.

> `[CONTRATO]` Na emissão de NF-e para um cliente OMS com múltiplos CNPJs, o campo `cnpjEmitente` do pedido seleciona qual CNPJ (e certificado) será utilizado para assinar e transmitir a NF-e à SEFAZ. Ver seção 6.3.

#### Endpoint

```
POST /api/integration/fiscal-authorizations
X-Api-Key: {chave-tecnica-do-integrador}
Content-Type: application/json
```

> `[CONTRATO]` O header `X-Api-Key` é obrigatório. Requisições sem esse header, ou com chave inválida ou revogada, retornam HTTP 401 com `errorCode: INVALID_API_KEY`. A chave é fornecida pelo ADMIN do Borurio.

**Campos do payload:**

| Campo | Tipo | Regra |
|---|---|---|
| `codigoEmpresaOms` | String | Obrigatório · Máx 100 chars · Identificador único do cliente no sistema OMS |
| `cnpj` | String | Obrigatório · Exatamente 14 dígitos numéricos |
| `certBase64` | String | Obrigatório · Arquivo PKCS12 (.pfx / .p12) codificado em Base64 |
| `certSenha` | String | Obrigatório · Senha do arquivo PKCS12 |

> `[CONTRATO]` O `cnpj` enviado deve corresponder ao CNPJ embutido no Subject do certificado X.509. Divergência retorna `CNPJ_CERTIFICATE_MISMATCH`.

> `[EXEMPLO]` Primeira autorização — CNPJ1 de um novo cliente OMS:
```json
{
  "codigoEmpresaOms": "JCHO-001",
  "cnpj":             "12000000000195",
  "certBase64":       "<base64 do arquivo .pfx>",
  "certSenha":        "<senha do certificado>"
}
```

> `[EXEMPLO]` Segunda autorização — CNPJ2 do **mesmo** cliente OMS (retorna o mesmo token):
```json
{
  "codigoEmpresaOms": "JCHO-001",
  "cnpj":             "98765432000100",
  "certBase64":       "<base64 do .pfx do segundo CNPJ>",
  "certSenha":        "<senha do segundo certificado>"
}
```

**Resposta de sucesso — HTTP 200:**
```json
{
  "token":         "eyJhbGci...",
  "empresaId":     1,
  "cnpj":          "12000000000195",
  "razaoSocial":   "Jcho Factory Ltda",
  "tokenExpiraEm": "2026-08-01T13:15:00"
}
```

> `[CONTRATO]` Esta rota **não usa** o envelope `Result<>` padrão da API. A estrutura de resposta é própria: `{ token, empresaId, cnpj, razaoSocial, tokenExpiraEm }`. Leia `response.token` diretamente — **não** existe `response.data.token`.

> `[CONTRATO]` O campo `tokenExpiraEm` reflete o vencimento do certificado A1 enviado. O OMS deve armazenar o token e usá-lo no header `Authorization: Bearer {token}` em todas as operações subsequentes (pedidos, emissão, consulta, cancelamento, CC-e).

> `[CONTRATO]` Para clientes OMS com **múltiplos CNPJs**, o token é o mesmo independente de qual CNPJ foi autorizado por último. O campo `empresaId` na resposta refere-se à empresa do primeiro CNPJ autorizado para aquele `codigoEmpresaOms` (empresa âncora). O `empresaId` não é enviado nos pedidos — não é necessário.

#### Comportamento por cenário de chamada

| Cenário | Condição | Comportamento |
|---|---|---|
| **A — Novo cliente OMS** | `codigoEmpresaOms` nunca visto | Cria slot + insere cert + emite **novo token** |
| **B — Mesmo CNPJ, mesmo cert** | Cert idêntico já ativo | Nenhuma alteração no banco — **retorna token existente** |
| **C — Mesmo CNPJ, cert novo** | Mesmo CNPJ, thumbprint diferente | Desativa cert anterior + insere cert novo + atualiza `tokenExpiraEm` — **retorna token existente (mesmo JTI)** |
| **D — CNPJ novo, cliente existente** | `codigoEmpresaOms` já autorizado, CNPJ inédito | Insere cert para o novo CNPJ — **retorna token existente sem alteração** |

> `[CONTRATO]` Nos cenários B, C e D o **token não muda**. O OMS não precisa atualizar o token armazenado ao adicionar ou renovar CNPJs de um cliente já autorizado. Armazene o token recebido na primeira autorização do cliente.

#### Atualização de certificado expirado ou renovado

> `[CONTRATO]` Para renovar o certificado de um CNPJ específico, enviar este endpoint novamente com o mesmo `codigoEmpresaOms` e `cnpj`, e o novo arquivo `.pfx`. O sistema desativa o certificado anterior para aquele CNPJ e ativa o novo — o token permanece o mesmo (cenário C acima).

> `[CONTRATO]` Não é necessário acionar o ADMIN do Borurio para trocar o certificado — o próprio OMS realiza a renovação diretamente via este endpoint.

#### Revogação

> `[OPERACIONAL]` Em caso de comprometimento de API Key ou token, acionar o ADMIN do Borurio para revogação administrativa. Após revogação, o token é recusado imediatamente com `AUTHORIZATION_REVOKED` em qualquer requisição — sem aguardar expiração. A reautorização com certificado válido emite um novo token.

---

## 4. Contexto Multiempresa (Multitenancy)

> `[CONTRATO]` O `empresaId` **não é enviado no body das requisições.** Ele é extraído automaticamente do token JWT pelo sistema.

**Como funciona internamente — fluxo de usuário:**

1. No login, o `empresaId` do usuário é embutido no token como claim `"eid"`
2. A cada request, o filtro JWT extrai `"eid"` e associa ao contexto da requisição
3. Todos os endpoints de produtos, pedidos e clientes isolam os dados automaticamente por empresa
4. O contexto é limpo após cada request — sem risco de vazamento entre chamadas

> `[CONTRATO]` Um token gerado para a empresa A **só acessa** produtos, pedidos e clientes da empresa A. Tentativas de acessar recursos de outra empresa retornam HTTP 404 (recurso não encontrado para a empresa autenticada).

> `[OPERACIONAL]` Cada empresa parceira deve ter seu próprio usuário com credenciais distintas. Não compartilhar tokens entre empresas.

**Contexto OMS multi-CNPJ:**

> `[CONTRATO]` O token OMS identifica o **cliente OMS** (`codigoEmpresaOms`), não um CNPJ específico. Produtos cadastrados via token OMS pertencem ao cliente OMS. Pedidos e emissões de NF-e são selecionados por CNPJ — o campo `cnpjEmitente` no pedido determina qual certificado é utilizado na assinatura e transmissão à SEFAZ (ver seção 6.3).

> `[OPERACIONAL]` Não é necessário informar `empresaId` em nenhuma requisição do fluxo OMS. O sistema resolve a empresa emitente a partir do `cnpjEmitente` do pedido.

---

## 5. Fluxo Obrigatório de Integração

### 5.1 Sequência de Chamadas

> `[CONTRATO]` A sequência abaixo deve ser respeitada. Etapas não podem ser puladas.

```
Etapa 1 ── POST /auth/login
           └─► Obter token JWT

Etapa 2 ── POST /api/app/produtos          (se produto ainda não existir)
           └─► Registrar produto com dados fiscais completos
               Guardar: data.id = produtoId

Etapa 3 ── POST /api/app/pedidos
           └─► Criar pedido em estado RASCUNHO com itens
               Guardar: data.id = pedidoId

Etapa 4 ── POST /api/app/pedidos/{pedidoId}/emitir
           └─► Transmitir NF-e para a SEFAZ
               Guardar: data.chaveNfe

Etapa 5 ── GET  /api/app/pedidos/{pedidoId}/situacao
           └─► Confirmar status local + consulta live na SEFAZ
               (opcional após status AUTORIZADO, obrigatório após AGUARDANDO)
```

**Operações pós-emissão (quando aplicável):**

```
Etapa 6 ── POST /api/app/pedidos/{pedidoId}/cancelar
           └─► Cancelar NF-e (somente status AUTORIZADO, dentro do prazo legal)

Etapa 7 ── POST /api/app/pedidos/{pedidoId}/cce
           └─► Carta de Correção Eletrônica (somente status AUTORIZADO)
```

### 5.2 Diagrama de Transição de Estados

```
                          ┌─────────────────────────────┐
                          │                             │
              POST /emitir│          SEFAZ              │
RASCUNHO ────────────────►│   cStat=100  → AUTORIZADO   │
                          │   cStat=104  → AGUARDANDO   │
                          │   cStat≥200  → REJEITADO    │
                          │   exceção    → ERRO (500)   │
                          └─────────────────────────────┘

AUTORIZADO ──► POST /cancelar ──► CANCELADO   (imutável)
AUTORIZADO ──► POST /cce      ──► AUTORIZADO  (status não muda)
AGUARDANDO ──► GET  /situacao ──► (verificar cStat atual)
REJEITADO  ──► (criar novo pedido com dados corrigidos)
ERRO       ──► (verificar logs, avaliar retry manual)
```

---

## 6. Endpoints por Etapa

### 6.1 Verificação de Disponibilidade

> `[CONTRATO]` Não requer autenticação.

```
GET /api/test/ping
```

**Resposta — HTTP 200:**
```json
{
  "status":      "UP",
  "code":        200,
  "message":     "API Borurio ERP Fiscal BR está operacional.",
  "environment": "dev",
  "timestamp":   "2026-05-12T10:00:00.000-03:00"
}
```

> `[CONTRATO]` Esta rota não usa o envelope `Result<>` padrão. A estrutura de resposta é própria.

> `[OPERACIONAL]` O campo `"environment"` reflete o perfil Spring ativo (`spring.profiles.active`). Use-o apenas como indicador de diagnóstico, não como discriminador de roteamento.

---

### 6.2 Cadastro de Produto (Etapa 2)

> `[CONTRATO]` O produto deve ser cadastrado antes de criar qualquer pedido que o referencie. Os campos fiscais do produto são **congelados no item do pedido** no momento da criação — alterações posteriores no produto não retroagem.

```
POST /api/app/produtos
Authorization: Bearer {token}
Content-Type: application/json
```

**Campos e regras:**

| Campo | Tipo | Regra |
|---|---|---|
| `codigo` | String | Obrigatório · Máx 60 chars · Único por empresa |
| `descricao` | String | Obrigatório · Máx 120 chars |
| `ncm` | String | Obrigatório · Exatamente 8 dígitos numéricos |
| `cfop` | String | Opcional · 4 dígitos numéricos · default `"5102"` (intra-estado) se omitido |
| `unidade` | String | Obrigatório · Ex: `UN`, `KG`, `PC`, `CX` |
| `preco` | Decimal | Obrigatório · Valor > 0.01 |
| `origem` | Inteiro | Obrigatório · `0`=Nacional · `1` a `8`=Importada |
| `csosn` | String | Opcional · Se omitido, snapshot do item usará `"400"` |

> `[CONTRATO]` CFOP de referência: operação interna (mesmo estado): `5102` · operação interestadual: `6102`.

> `[CONTRATO]` CSOSN de referência para Simples Nacional: `102`=sem ST sem crédito · `103`=isento por faixa de receita · `300`=imune · `400`=não contribuinte · `500`=ICMS cobrado anteriormente (ST) · `900`=outros. Os códigos `201`, `202` e `203` (com ST) não estão suportados nesta versão do motor.

> `[EXEMPLO]` Payload mínimo válido:
```json
{
  "codigo":    "PROD-001",
  "descricao": "Produto de Teste Integração",
  "ncm":       "84715011",
  "cfop":      "5102",
  "unidade":   "UN",
  "preco":     100.00,
  "origem":    0,
  "csosn":     "102"
}
```

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "id":           7,
    "empresaId":    1,
    "codigo":       "PROD-001",
    "descricao":    "Produto de Teste Integração",
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

> `[CONTRATO]` Guardar `data.id` como `produtoId` para uso nos itens do pedido.

> `[OPERACIONAL]` Produto com `estado=0` é tratado como inativo. Tentar referenciar um produto inativo em um pedido retorna HTTP 400: `"Produto inativo não pode ser adicionado ao pedido"`.

---

### 6.2b Busca de Produto por Código Interno (SKU)

> `[OPERACIONAL]` Quando a OMS conhece o código interno (SKU) do produto mas não seu `id` no Borurio, utilize este endpoint para resolver o identificador antes de consultar o estoque.

```
GET /api/app/produtos/codigo/{codigo}
Authorization: Bearer {token}
```

| Parâmetro | Regra |
|---|---|
| `codigo` | Código interno do produto (path variable) — mesmo valor cadastrado em `POST /api/app/produtos` |

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "id":        7,
    "empresaId": 1,
    "codigo":    "PROD-001",
    "descricao": "Produto de Teste Integração",
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

> `[CONTRATO]` Guardar `data.id` para uso no fluxo de consulta de estoque.

> `[OPERACIONAL]` Fluxo em dois passos validado pelo time de integração:
> ```
> Passo 1 — GET /api/app/produtos/codigo/{sku}  → recupera produto + id
> Passo 2 — GET /api/app/produtos/{id}/estoque  → consulta saldo com o id retornado
> ```

**Resposta — HTTP 404:**
```json
{ "code": 404, "message": "Recurso não encontrado", "data": null }
```

---

### 6.2c Gestão de Estoque

> `[CONTRATO]` O motor fiscal controla o estoque de produtos com reservas atômicas vinculadas ao ciclo de emissão de NF-e. A OMS deve verificar o estoque disponível antes de submeter um pedido para emissão.

#### Consulta de Saldo de Estoque

```
GET /api/app/produtos/{id}/estoque
Authorization: Bearer {token}
```

**Resposta — HTTP 200:**
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

> `[CONTRATO]` Usar `estoqueDisponivel` para decidir se prossegue com o pedido. Se a quantidade dos itens exceder `estoqueDisponivel`, a chamada `/emitir` retorna HTTP 422 e o pedido permanece em `RASCUNHO`.

#### Entrada Manual de Estoque

```
POST /api/app/produtos/{id}/estoque/entrada
Authorization: Bearer {token}    ← requer role ADMIN
Content-Type: application/json
```

```json
{ "quantidade": 50.00, "observacao": "Entrada inicial OMS" }
```

> `[CONTRATO]` Este endpoint requer role ADMIN. Token de OPERADOR recebe HTTP 403.

#### Ciclo de Estoque Durante a Emissão de NF-e

| Evento                        | Efeito no estoque                                                      |
|-------------------------------|------------------------------------------------------------------------|
| `POST /emitir` chamado        | Reserva atômica — `estoqueDisponivel -= qtd` por item                  |
| HTTP 422 retornado            | Estoque insuficiente — sem reserva; pedido permanece em `RASCUNHO`     |
| Status → `AUTORIZADO`         | Baixa definitiva — `estoqueTotal -= qtd`, reserva liberada             |
| Status → `REJEITADO` / `ERRO` | Reserva desfeita — `estoqueDisponivel += qtd` por item                 |
| Status → `AGUARDANDO`         | Reserva mantida — `estoqueDisponivel` permanece bloqueado              |
| Status → `CANCELADO`          | Estorno — `estoqueTotal += qtd`                                        |

---

### 6.2d Upsert em Lote de Produtos — Batch

> `[OPERACIONAL]` Use este endpoint para sincronização de catálogo OMS quando precisar importar múltiplos produtos de uma vez. Para cadastro individual, use `POST /api/app/produtos` (seção 6.2).

```
POST /api/app/produtos/batch
Authorization: Bearer {token}
Content-Type: application/json
```

**Comportamento por item:**
- Produto novo (código + empresa não encontrado): status **CRIADO**
- Produto existente (mesmo código na mesma empresa): status **ATUALIZADO** — apenas campos de catálogo são atualizados; `estoque`, `estoque_reservado` e `estado` são preservados
- Item com dados inválidos: status **REJEITADO** — o processamento continua para os próximos itens
- Código duplicado no mesmo payload: **REJEITADO** com `DUPLICATE_CODIGO_IN_BATCH` — a segunda ocorrência é rejeitada; a primeira é processada normalmente

> `[CONTRATO]` A resposta é sempre **HTTP 207 Multi-Status** — mesmo que todos os itens tenham sucesso ou todos falhem. Nunca espere HTTP 200 nem HTTP 422 por resultado individual de item.

> `[CONTRATO]` Máximo de **200 produtos por requisição**. Exceder esse limite retorna HTTP 422 com `errorCode: "BATCH_LIMIT_EXCEEDED"` antes de qualquer item ser processado.

> `[CONTRATO]` Uma lista `produtos` vazia retorna HTTP 400.

**Campos por item (`produtos[]`):**

| Campo       | Tipo    | Regra                                                          |
|-------------|---------|----------------------------------------------------------------|
| `codigo`    | String  | Obrigatório · Chave de upsert por empresa                     |
| `descricao` | String  | Obrigatório                                                   |
| `ncm`       | String  | Obrigatório · Exatamente 8 dígitos numéricos                  |
| `cfop`      | String  | Opcional · 4 dígitos numéricos · default `"5102"` se omitido  |
| `unidade`   | String  | Obrigatório                                                   |
| `preco`     | Decimal | Obrigatório · Valor > 0                                       |
| `origem`    | Inteiro | Opcional · default `0` (nacional) se omitido                  |
| `csosn`     | String  | Opcional · default `"400"` se omitido                         |

**Campos NÃO atualizados no upsert (produto existente):** `codigo`, `estoque`, `estoque_reservado`, `estado`

> `[EXEMPLO]` Requisição com 3 itens — 1 novo, 1 existente, 1 inválido:
```json
{
  "produtos": [
    { "codigo": "SKU-001", "descricao": "Produto A", "ncm": "84715011", "unidade": "UN", "preco": 100.00 },
    { "codigo": "SKU-002", "descricao": "Produto B", "ncm": "84715011", "unidade": "UN", "preco": 50.00 },
    { "codigo": "SKU-BAD", "descricao": "Invalido",  "ncm": "123",      "unidade": "UN", "preco": 10.00 }
  ]
}
```

**Resposta — HTTP 207 Multi-Status:**
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

> `[CONTRATO]` Cada entrada em `resultados` corresponde 1:1 ao `produtos[]` de entrada por posição e `codigo`. Um item `REJEITADO` nunca bloqueia o processamento dos itens seguintes.

> `[CONTRATO]` Use `resultados[].status` (`CRIADO` / `ATUALIZADO` / `REJEITADO`) para o resultado por item. Use `resultados[].errorCode` (quando presente) para tratamento programático de itens rejeitados.

> `[CONTRATO]` `resultados[].produtoId` contém o ID do banco para itens `CRIADO` e `ATUALIZADO`, e é `null` para `REJEITADO`.

---

### 6.3 Criação de Pedido — RASCUNHO (Etapa 3)

> `[CONTRATO]` O pedido recém-criado inicia obrigatoriamente em estado `RASCUNHO`. Campos como `status`, `chaveNfe` e `numero` são preenchidos automaticamente — **não enviar no body**.

```
POST /api/app/pedidos
Authorization: Bearer {token}
Content-Type: application/json
```

**Campos do cabeçalho:**

| Campo                 | Tipo                | Regra                                                                 |
|-----------------------|---------------------|-----------------------------------------------------------------------|
| `destCnpjCpf`         | String              | Obrigatório · CNPJ (14 dígitos) ou CPF (11 dígitos) · somente números |
| `destRazaoSocial`     | String              | Obrigatório                                                           |
| `destUf`              | String              | Recomendado · Sigla do estado: `SP`, `RJ`, `MG`...                    |
| `destLogradouro`      | String              | Recomendado · Melhora aprovação SEFAZ                                 |
| `destNumero`          | String              | Recomendado                                                           |
| `destBairro`          | String              | Recomendado                                                           |
| `destCodigoMunicipio` | String              | Recomendado · Código IBGE 7 dígitos                                   |
| `destMunicipio`       | String              | Recomendado                                                           |
| `destCep`             | String              | Recomendado                                                           |
| `naturezaOperacao`    | String              | Opcional · Default server-side: `"VENDA DE MERCADORIA"`               |
| `cnpjEmitente`        | String              | **Obrigatório para OMS multi-CNPJ** · 14 dígitos numéricos · CNPJ que deve assinar e emitir a NF-e · Se omitido no fluxo OMS, usa o CNPJ do primeiro certificado autorizado para o cliente |
| `externalOrderId`     | String              | Recomendado · Máx 100 chars · Único por empresa · Habilita retry idempotente |

> `[CONTRATO]` **Idempotência:** se `externalOrderId` for informado e já existir um pedido com esse identificador para a mesma empresa, o sistema retorna o pedido existente sem criar duplicata. Protege contra emissão dupla de NF-e por timeout ou retry do OMS. Se omitido, cada chamada cria um novo pedido.

> `[CONTRATO]` O escopo do `externalOrderId` é por empresa: o mesmo valor usado pela empresa A não conflita com a empresa B.

**Campos de cada item (`itens[]`):**

> `[CONTRATO]` Todos os campos fiscais abaixo são **obrigatórios** e devem ser enviados pelo OMS em cada item. O sistema **não** copia dados fiscais do produto cadastrado — o que o OMS enviar é exatamente o que vai para o XML da NF-e. Se algum campo fiscal obrigatório estiver ausente, o pedido é rejeitado com HTTP 400.

| Campo            | Tipo    | Regra                                                              |
|------------------|---------|--------------------------------------------------------------------|
| `produtoId`      | Long    | Obrigatório · Produto deve existir e estar ativo                   |
| `quantidade`     | Decimal | Obrigatório · Valor > 0                                            |
| `valorUnitario`  | Decimal | Obrigatório · Valor > 0                                            |
| `codigoProduto`  | String  | Obrigatório · Código do produto na NF-e (`cProd`)                  |
| `descricao`      | String  | Obrigatório · Descrição do produto na NF-e (`xProd`)               |
| `ncm`            | String  | Obrigatório · Exatamente 8 dígitos numéricos                       |
| `cfop`           | String  | Obrigatório · 4 dígitos (ex: `"5102"` dentro do estado, `"6102"` interestadual) |
| `unidade`        | String  | Obrigatório · Ex: `UN`, `KG`, `PC`, `CX`                          |
| `origem`         | Integer | Obrigatório · `0`=Nacional · `1`–`8`=Importada                    |
| `csosn`          | String  | Obrigatório · Simples Nacional: `"102"`, `"103"`, `"300"`, `"400"`, `"500"`, `"900"` (não enviar `201`/`202`/`203`) |

> `[CONTRATO]` `valorTotal` de cada item é calculado automaticamente como `quantidade × valorUnitario`. O total do pedido é a soma dos itens. Não enviar esses campos.

> `[EXEMPLO]` Payload válido com campos fiscais por item:
```json
{
  "externalOrderId":     "OMS-20260601-0001",
  "destCnpjCpf":         "12345678000195",
  "destRazaoSocial":     "Empresa Destinatária Ltda",
  "destUf":              "SP",
  "destCodigoMunicipio": "3550308",
  "destMunicipio":       "São Paulo",
  "itens": [
    {
      "produtoId":     7,
      "quantidade":    2,
      "valorUnitario": 100.00,
      "codigoProduto": "SKU-OMS-001",
      "descricao":     "Produto Teste Integração",
      "ncm":           "84715011",
      "cfop":          "6102",
      "unidade":       "UN",
      "origem":        0,
      "csosn":         "102"
    }
  ]
}
```

**Resposta — HTTP 200:**
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
    "destRazaoSocial": "Empresa Destinatária Ltda",
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
        "descricao":     "Produto de Teste Integração",
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

> `[CONTRATO]` Guardar `data.id` como `pedidoId` para as etapas seguintes.

> `[OPERACIONAL]` Os campos fiscais do item (`codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`) são copiados do produto no momento da criação. Essa cópia é imutável — alterações posteriores no cadastro do produto não afetam pedidos existentes.

---

### 6.4 Emissão de NF-e (Etapa 4)

> `[CONTRATO]` Sem body. O motor constrói o XML NF-e 4.00 internamente a partir do snapshot fiscal dos itens.

> `[CONTRATO]` Pré-condição: pedido deve estar em estado `RASCUNHO`. Qualquer outro estado retorna HTTP 422.

```
POST /api/app/pedidos/{pedidoId}/emitir
Authorization: Bearer {token}
```

**Resposta — HTTP 200 (transmissão processada):**
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

> `[CONTRATO]` HTTP 200 indica que a chamada à SEFAZ foi processada — **não indica que a NF-e foi autorizada.** O status real está no campo `status` do pedido (verificar via `GET /api/app/pedidos/{id}` ou `/situacao`).

> `[CONTRATO]` O campo `data.chaveNfe` será uma string vazia `""` (não `null`) quando a SEFAZ não retornar chave de acesso.

**Resposta — HTTP 422 (pré-condição violada):**
```json
{ "code": 422, "message": "Pedido não está em RASCUNHO. Status atual: AUTORIZADO", "data": null }
```

**Resposta — HTTP 500 (falha de transmissão):**
```json
{ "code": 500, "message": "Erro interno do servidor", "data": null }
```

> `[OPERACIONAL]` HTTP 500 no `/emitir` significa que o pedido foi automaticamente movido para o estado `"ERRO"`. Verificar o estado via `GET /api/app/pedidos/{id}` antes de qualquer nova tentativa.

---

### 6.5 Consulta de Situação (Etapa 5)

> `[CONTRATO]` Pré-condição: pedido deve ter `chaveNfe` preenchida. Chamar antes da emissão retorna HTTP 422.

```
GET /api/app/pedidos/{pedidoId}/situacao
Authorization: Bearer {token}
```

**Resposta — HTTP 200:**
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

> `[CONTRATO]` Os campos `cStat`, `xMotivo`, `nProt` e `dhRecbto` são **opcionais** na resposta — presentes somente se o documento fiscal já foi registrado internamente.

> `[CONTRATO]` O campo `consultaSefaz` (XML bruto da chamada `consSitNFe`) está **sempre presente**.

| Campo                                      | Presença    | Origem                              |
|--------------------------------------------|-------------|-------------------------------------|
| `pedidoId`, `numero`, `status`, `chaveNfe` | Sempre      | Banco de dados local                |
| `cStat`, `xMotivo`, `nProt`, `dhRecbto`    | Condicional | Tabela `nfe_documento` (se existir) |
| `consultaSefaz`                            | Sempre      | Chamada live `consSitNFe` à SEFAZ   |

---

### 6.6 Cancelamento de NF-e (Etapa 6)

> `[CONTRATO]` Pré-condições obrigatórias:
> 1. `status == "AUTORIZADO"` — única condição aceita
> 2. Protocolo de autorização (`nProt`) deve estar disponível no documento fiscal interno

```
POST /api/app/pedidos/{pedidoId}/cancelar
Authorization: Bearer {token}
Content-Type: application/json

{ "justificativa": "Motivo com no mínimo 15 caracteres" }
```

| Campo           | Regra                |
|-----------------|----------------------|
| `justificativa` | Mínimo 15 caracteres |

> `[EXEMPLO]` Justificativa válida: `"Erro no pedido — cliente solicitou cancelamento"`

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": "<retEvento>...</retEvento>"
}
```

> `[CONTRATO]` `data` é o XML bruto retornado pela SEFAZ. Após cancelamento bem-sucedido, o status do pedido é atualizado para `"CANCELADO"` (imutável).

**Resposta — HTTP 422 (pré-condição violada):**
```json
{ "code": 422, "message": "Cancelamento só é permitido para pedidos com status AUTORIZADO. Status atual: AGUARDANDO", "data": null }
```

---

### 6.7 Carta de Correção Eletrônica — CC-e (Etapa 7)

> `[CONTRATO]` Pré-condição: `status == "AUTORIZADO"`. A CC-e **não altera o status do pedido**.

```
POST /api/app/pedidos/{pedidoId}/cce
Authorization: Bearer {token}
Content-Type: application/json

{ "correcao": "Texto de correção com no mínimo 15 caracteres" }
```

| Campo      | Regra                                                         |
|------------|---------------------------------------------------------------|
| `correcao` | Mínimo 15 caracteres · Máximo 20 CC-e por NF-e (limite SEFAZ) |

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": "<retEvento>...</retEvento>"
}
```

---

### 6.8 DANFE — Documento Auxiliar da Nota Fiscal Eletrônica

> `[CONTRATO]` O DANFE é o documento PDF complementar da NF-e emitida. É gerado a partir do XML assinado armazenado em `nfe_documento`. Requer autenticação JWT (qualquer role).

```
GET /api/fiscal/nfe/{chaveNfe}/danfe
Authorization: Bearer {token}
```

| Parâmetro  | Regra                                        |
|------------|----------------------------------------------|
| `chaveNfe` | Exatamente 44 dígitos numéricos (path var)   |

**Resposta de sucesso — HTTP 200:**
- `Content-Type: application/pdf`
- `Content-Disposition: attachment; filename="danfe-{chaveNfe}.pdf"`
- Body: bytes do PDF

**Respostas de erro:**

| Código | Causa                                                |
|--------|------------------------------------------------------|
| 400    | Chave de acesso não tem 44 dígitos                   |
| 401    | Token ausente ou inválido                            |
| 404    | NF-e não encontrada na tabela `nfe_documento`        |
| 500    | Erro interno durante a geração do PDF                |

> `[OPERACIONAL]` Em HOM (homologação, `tpAmb=2`), o DANFE exibe marca d'água diagonal "SEM VALOR FISCAL" em cinza claro. A marca d'água é suprimida em PRD (`tpAmb=1`).

> `[OPERACIONAL]` O rótulo de protocolo no DANFE é condicional: `PROTOCOLO DE AUTORIZAÇÃO DE USO` quando autorizada (cStat=100 + nProt presente); `RETORNO SEFAZ — HOMOLOGAÇÃO` em homologação quando ainda não autorizada (tpAmb=2, cStat≠100).

---

### 6.9 Manifestação do Destinatário

> `[OPERACIONAL]` A Manifestação do Destinatário é um conjunto de eventos fiscais enviados pelo **destinatário** de uma NF-e à SEFAZ para registrar sua posição sobre a nota recebida. Não faz parte do fluxo principal de emissão OMS → NF-e.

```
POST /api/fiscal/nfe/manifestar
Authorization: Bearer {token}
Content-Type: application/json
```

**Payload:**

| Campo | Tipo | Regra |
|---|---|---|
| `chaveNfe` | String | Obrigatório · Exatamente 44 dígitos numéricos |
| `tipoEvento` | String | Obrigatório · Um dos 4 valores válidos abaixo |
| `cnpjDestinatario` | String | Obrigatório · 14 dígitos numéricos (sem formatação) |
| `xJust` | String | Condicional · Obrigatório somente para `210240` · Mínimo 15 / máximo 255 caracteres |

**Tipos de evento suportados:**

| Código | Descrição |
|---|---|
| `210200` | Ciência da Operação |
| `210210` | Confirmação da Operação |
| `210220` | Desconhecimento da Operação |
| `210240` | Operação Não Realizada |

> `[EXEMPLO]` Payload para Ciência da Operação:
```json
{
  "chaveNfe":         "35260554393421000159550010000000351199116560",
  "tipoEvento":       "210200",
  "cnpjDestinatario": "54393421000159"
}
```

> `[EXEMPLO]` Payload para Operação Não Realizada (xJust obrigatório):
```json
{
  "chaveNfe":         "35260554393421000159550010000000351199116560",
  "tipoEvento":       "210240",
  "cnpjDestinatario": "54393421000159",
  "xJust":            "Mercadoria não recebida pelo destinatário conforme acordado"
}
```

**Resposta de sucesso — HTTP 200:**
```json
{
  "code":    200,
  "success": true,
  "data":    "135 - Evento registrado e vinculado a NF-e"
}
```

**Resposta de erro (dados inválidos ou rejeição SEFAZ) — HTTP 200:**
```json
{
  "code":    500,
  "success": false,
  "message": "Falha ao registrar manifestação: <detalhe do erro>"
}
```

> `[CONTRATO]` O campo `success: false` com `code: 500` indica rejeição pela SEFAZ ou dado inválido (chave malformada, tipo de evento incorreto, xJust ausente para 210240). O status HTTP sempre retorna 200; o código semântico está no envelope.

> `[CONTRATO]` Autenticação obrigatória. Request sem token retorna HTTP 401.

> `[OPERACIONAL]` **Limitação HOM:** o endpoint AN (Ambiente Nacional) da SEFAZ HOM pode retornar HTTP 403 a partir de redes residenciais/locais. Esse comportamento é de infraestrutura da SEFAZ federal, não um erro da API. O endpoint `/manifestar` funcionará normalmente em ambiente corporativo e PRD.

---

## 7. Máquina de Estados do Pedido

> `[CONTRATO]` Tabela de estados válidos e operações permitidas por estado:

| Estado       | Significado                             | Operações permitidas                  |
|--------------|-----------------------------------------|---------------------------------------|
| `RASCUNHO`   | Pedido criado, não transmitido          | `/emitir`                             |
| `AUTORIZADO` | NF-e aprovada pela SEFAZ (cStat=100)    | `/situacao`, `/cancelar`, `/cce`      |
| `AGUARDANDO` | Transmitido; confirmação SEFAZ pendente | `/situacao`                           |
| `REJEITADO`  | SEFAZ recusou (cStat ≥ 200)             | Nenhuma — criar novo pedido corrigido |
| `CANCELADO`  | NF-e cancelada com protocolo            | Nenhuma — imutável                    |
| `ERRO`       | Falha técnica durante transmissão       | Nenhuma via API — verificar logs      |

> `[CONTRATO]` As transições de estado são gerenciadas exclusivamente pelo motor fiscal. O ERP logístico não deve assumir ou forçar transições.

> `[OPERACIONAL]` O estado `ERRO` é terminal do ponto de vista da API. A recuperação exige análise nos logs internos (`GET /api/fiscal/nfe/logs`).

---

## 8. Padrão de Resposta da API

### 8.1 Envelope Padrão de Sucesso

> `[CONTRATO]` Todos os endpoints `/api/**` retornam o envelope `Result<>`, **exceto** `/auth/login`, `/api/test/ping` e `/api/integration/fiscal-authorizations`, que têm estrutura própria (documentadas nas seções 3.1, 3.3 e 6.1).

```json
{ "code": 200, "message": "Sucesso", "data": { ... } }
```

### 8.2 Respostas de Erro

> `[CONTRATO]` Erros HTTP 401 e 403 têm estrutura diferente dos demais — campo `"success": false` em vez de `"data"`:

```json
{ "code": 401, "message": "Autenticação necessária", "success": false }
{ "code": 403, "message": "Acesso negado",           "success": false }
```

> `[CONTRATO]` Todos os outros erros seguem o envelope padrão com `"data": null`:

```json
{ "code": 400, "message": "Descrição do erro",       "data": null }
{ "code": 404, "message": "Recurso não encontrado",  "data": null }
{ "code": 422, "message": "Descrição do erro",       "data": null }
{ "code": 500, "message": "Erro interno do servidor","data": null }
```

> `[CONTRATO]` Erros de validação de campo (`@Valid`) retornam HTTP 422 com o **mapa de erros em `data`**:

```json
{
  "code": 422,
  "message": "Dados inválidos",
  "data": {
    "destCnpjCpf": "CNPJ/CPF do destinatário é obrigatório"
  }
}
```

**Tabela de referência:**

| HTTP       | Trigger                                                 | Estrutura de `data`                   |
|------------|---------------------------------------------------------|---------------------------------------|
| 400        | JSON malformado ou parâmetro inválido de negócio        | `null`                                |
| 401        | Token ausente ou expirado                               | Campo `"success": false` (sem `data`) |
| 403        | Role insuficiente                                       | Campo `"success": false` (sem `data`) |
| 404        | ID não encontrado na empresa autenticada                | `null`                                |
| 422 @Valid | Campo inválido na entidade                              | `{ "campo": "mensagem" }`             |
| 422 estado | Operação não permitida no estado atual                  | `null`                                |
| 500        | Falha não tratada (incluindo erro de transmissão SEFAZ) | `null`                                |

### 8.2a Códigos de Erro de Negócio (`errorCode`)

> `[CONTRATO]` Erros de negócio incluem o campo `errorCode` além dos campos padrão `code` e `message`. Use `errorCode` como identificador estável para tratamento programático — **não faça parse do campo `message`**, que está em português e pode mudar.

**Resposta de erro com `errorCode`:**
```json
{
  "code":      422,
  "message":   "Estoque insuficiente para \"Produto A\" (disponível: 0.0000, solicitado: 5.00)",
  "data":      null,
  "errorCode": "INSUFFICIENT_STOCK"
}
```

> `[CONTRATO]` O campo `errorCode` está presente **somente em respostas de erro de negócio**. Respostas de sucesso (`code: 200`) não o incluem.

**Códigos de erro definidos:**

| `errorCode`            | HTTP | Gatilho                                                                                        |
|------------------------|------|------------------------------------------------------------------------------------------------|
| `INVALID_ORDER_STATUS` | 422  | Pedido não está no estado esperado para a operação (ex: não é `RASCUNHO` para `/emitir`; não é `AUTORIZADO` para `/cancelar` ou `/cce`) |
| `INSUFFICIENT_STOCK`   | 422  | Estoque disponível (`estoqueDisponivel`) é inferior à quantidade solicitada para o item        |
| `PRODUCT_NOT_FOUND`    | 422  | Um item referencia `produtoId` que não existe para a empresa autenticada                      |
| `PRODUCT_INACTIVE`          | 422                          | Um item referencia produto com `estado = 0` (inativo)                                                                               |
| `VALIDATION_ERROR`          | 207 `resultados[].errorCode` | Item do batch falhou na validação de campos — NCM inválido, campo obrigatório vazio ou preço ≤ 0                                     |
| `BATCH_LIMIT_EXCEEDED`      | 422                          | A requisição batch contém mais de 200 produtos — retornado antes de qualquer item ser processado                                     |
| `DUPLICATE_CODIGO_IN_BATCH` | 207 `resultados[].errorCode` | O mesmo `codigo` aparece mais de uma vez no mesmo payload batch — a segunda ocorrência é rejeitada; a primeira é processada normalmente |
| `INVALID_API_KEY`           | 401                          | Header `X-Api-Key` ausente, inválido, expirado ou revogado — necessário para `POST /api/integration/fiscal-authorizations` |
| `COMPANY_INACTIVE`          | 422                          | Empresa com o CNPJ informado existe no Borurio, mas está marcada como inativa — acionar o ADMIN para reativação |
| `INVALID_CERTIFICATE`       | 422                          | Certificado A1 inválido — base64 malformado, PKCS12 corrompido, senha incorreta ou nenhum certificado X.509 encontrado no arquivo |
| `CNPJ_CERTIFICATE_MISMATCH` | 422                          | CNPJ enviado no payload não corresponde ao CNPJ embutido no Subject do certificado X.509 |
| `CERTIFICATE_EXPIRED`       | 422                          | Certificado A1 está expirado — substituir pelo certificado renovado e reautorizar |
| `AUTHORIZATION_REVOKED`     | 401                          | Token OMS foi revogado administrativamente — reautorizar com certificado válido ou aguardar resolução pelo ADMIN |
| `CNPJ_NOT_AUTHORIZED`       | 403                          | CNPJ informado em `cnpjEmitente` não possui autorização ativa para este cliente OMS — realizar `POST /api/integration/fiscal-authorizations` com o certificado desse CNPJ antes de emitir |
| `CERT_NOT_FOUND_FOR_CNPJ`   | 422                          | Nenhum certificado ativo encontrado para o CNPJ emitente — verificar se a autorização fiscal foi realizada para esse CNPJ |

> `[OPERACIONAL]` O OMS deve usar `errorCode` para toda lógica condicional. O campo `message` é destinado a logs legíveis por humanos. O HTTP status isolado não é suficiente para distinguir `INSUFFICIENT_STOCK`, `PRODUCT_NOT_FOUND` e `PRODUCT_INACTIVE`, que todos retornam HTTP 422.

---

### 8.3 Referência de Mensagens da API

> `[OPERACIONAL]` O campo `message` em todas as respostas da API é sempre em português. A tabela abaixo documenta cada mensagem fixa do sistema, seu significado e quando ela ocorre.

| Mensagem (campo `message`)                      | Significado                                   | Quando ocorre                                                         |
|-------------------------------------------------|-----------------------------------------------|-----------------------------------------------------------------------|
| `Autenticação bem-sucedida`                     | Login realizado com sucesso                   | Credenciais válidas no `POST /auth/login`                            |
| `Credenciais inválidas`                         | E-mail ou senha incorretos                    | Credenciais erradas no login                                          |
| `Erro interno de autenticação`                  | Falha interna no servidor durante login       | Exceção não tratada no fluxo de autenticação                          |
| `Autenticação necessária`                       | Token ausente ou expirado                     | Request sem `Authorization: Bearer` válido                            |
| `Acesso negado`                                 | Role insuficiente para o endpoint             | Role `OPERADOR` acessando rota exclusiva de `ADMIN`                   |
| `Sucesso`                                       | Operação concluída com sucesso                | Qualquer resposta `code: 200` da API                                  |
| `Registro não encontrado`                       | ID não existe para a empresa autenticada      | `GET /{id}` sem correspondência no banco                              |
| `Recurso não encontrado`                        | Resultado vazio para o parâmetro solicitado   | PathVariable ou chave sem registro correspondente                     |
| `Erro interno do servidor`                      | Exceção não tratada (incluindo erros SEFAZ)   | Falha técnica ou erro de transmissão fiscal                           |
| `Dados inválidos`                               | Falha em validação `@Valid` de campo          | Body com campos fora das regras — retorna mapa de erros em `data`     |
| `Falha na operação`                             | Violação de regra de negócio                  | Operação não permitida no estado atual do pedido                      |
| `Requisição inválida`                           | Body malformado ou parâmetro inesperado       | JSON inválido ou tipo de dado incorreto                               |
| `CNPJ/CPF do destinatário é obrigatório`        | Campo `destCnpjCpf` ausente ou vazio          | Criação de pedido sem CNPJ/CPF do destinatário (HTTP 422)             |
| `Razão social do destinatário é obrigatória`    | Campo `destRazaoSocial` ausente               | Criação de pedido sem razão social (HTTP 422)                         |
| `UF do destinatário é obrigatória`              | Campo `destUf` ausente                        | Criação de pedido sem UF do destinatário (HTTP 422)                   |

> `[OPERACIONAL]` Mensagens de validação (HTTP 422) são retornadas em `data` como mapa chave→mensagem, onde a chave é o nome do campo JSON que falhou.

---

### 8.4 Paginação

> `[CONTRATO]` Endpoints de listagem aceitam `page` (0-based) e `size` (máx 100):

```
GET /api/app/pedidos?page=0&size=20
GET /api/app/produtos?page=0&size=20
```

**Resposta:**
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

> `[OPERACIONAL]` O endpoint `GET /api/app/pedidos` (lista paginada) **não inclui os itens de cada pedido**. Para obter os itens, usar `GET /api/app/pedidos/{id}`.

---

### 8.5 Rastreamento de Requisições — `X-Request-Id`

> `[OPERACIONAL]` Toda resposta da API inclui um header `X-Request-Id` com UUID que identifica unicamente a requisição nos logs do servidor.

**Comportamento:**
- Se a OMS enviar um header `X-Request-Id` na requisição, o sistema usa esse valor e o retorna no header de resposta
- Se o header estiver ausente ou em branco, o sistema gera um UUID aleatório automaticamente
- O valor aparece em todas as linhas de log do servidor para aquela requisição — habilitando correlação ponta a ponta

> `[OPERACIONAL]` Para correlacionar logs da OMS com logs do servidor da API, inclua um ID de correlação na requisição:
```
POST /api/app/produtos/batch
X-Request-Id: oms-batch-20260601-001
```
O header de resposta conterá: `X-Request-Id: oms-batch-20260601-001`

> `[OPERACIONAL]` Respostas de erro de negócio também incluem `requestId` no corpo JSON:
```json
{
  "code":      422,
  "message":   "O lote excede o limite máximo de 200 produtos por requisição.",
  "data":      null,
  "errorCode": "BATCH_LIMIT_EXCEEDED",
  "requestId": "oms-batch-20260601-001"
}
```

> `[OPERACIONAL]` Respostas HTTP 401 incluem `X-Request-Id` no header de resposta mesmo antes da autenticação ser resolvida — permitindo à OMS correlacionar tentativas de autenticação falhas com os logs do servidor.

---

## 9. Smoke Test — Ambiente HOM

> `[CONTRATO]` A sequência abaixo deve ser executada e validada em HOM antes da integração em PRD.

### 9.1 Sequência de Validação

| # | Request                                              | Critério de PASS                               |
|---|------------------------------------------------------|------------------------------------------------|
| 1 | `GET /api/test/ping`                                 | HTTP 200 · `status = "UP"`                     |
| 2 | `POST /auth/login`                                   | HTTP 200 · `token` não nulo                    |
| 3 | `POST /api/app/produtos`                             | HTTP 200 · `data.id` retornado                 |
| 4 | `GET /api/app/produtos?page=0&size=5`                | HTTP 200 · `data.totalElements ≥ 1`            |
| 5 | `POST /api/app/pedidos` (com `produtoId` do passo 3) | HTTP 200 · `data.status = "RASCUNHO"`          |
| 6 | `POST /api/app/pedidos/{id}/emitir`                  | HTTP 200 · `data.soapRetorno` não vazio        |
| 7 | `GET /api/app/pedidos/{id}/situacao`                 | HTTP 200 · `data.chaveNfe` preenchida          |
| 8  | `GET /api/app/pedidos/{id}`                                            | HTTP 200 · `data.itens` com campos de snapshot                       |
| 9  | `POST /api/app/produtos/batch` (1 produto novo, `codigo: "BATCH-001"`) | HTTP 207 · `criados=1` · `resultados[0].status = "CRIADO"`          |
| 10 | `POST /api/app/produtos/batch` (mesmo produto novamente)               | HTTP 207 · `atualizados=1` · `resultados[0].status = "ATUALIZADO"`  |
| 11 | Inspecionar headers de resposta de qualquer requisição                 | Header `X-Request-Id` presente · formato UUID                        |

### 9.1b Smoke Test OMS Multi-CNPJ

> `[OPERACIONAL]` Executar esta sequência complementar para validar o fluxo multi-CNPJ quando o cliente OMS possuir mais de um CNPJ autorizado.

| # | Request | Critério de PASS |
|---|---|---|
| M1 | `POST /api/integration/fiscal-authorizations` — CNPJ1 do cliente OMS | HTTP 200 · `token` retornado · empresa auto-criada |
| M2 | `POST /api/integration/fiscal-authorizations` — CNPJ2 do **mesmo** `codigoEmpresaOms` | HTTP 200 · **mesmo `token`** da chamada M1 |
| M3 | `POST /api/integration/fiscal-authorizations` — CNPJ1, mesmo cert reenviado (cenário B) | HTTP 200 · mesmo token · nenhuma alteração no banco |
| M4 | `POST /api/app/pedidos` com `cnpjEmitente: "{CNPJ2}"` usando o token OMS | HTTP 200 · `data.cnpjEmitente` = CNPJ2 · `data.status = "RASCUNHO"` |
| M5 | `POST /api/app/pedidos/{id}/emitir` do pedido M4 | HTTP 200 · NF-e assinada com cert do CNPJ2 |
| M6 | `POST /api/app/pedidos` com `cnpjEmitente: "{CNPJ3-nao-autorizado}"` | HTTP 403 · `errorCode: "CNPJ_NOT_AUTHORIZED"` |

> `[CONTRATO]` O teste M2 é o critério de aceitação mais importante do fluxo multi-CNPJ: confirma que o token não muda ao adicionar um segundo CNPJ ao mesmo cliente OMS.

### 9.2 Verificações de Segurança

| Request                                                         | Resultado esperado                                        |
|-----------------------------------------------------------------|-----------------------------------------------------------|
| `GET /api/app/pedidos` sem `Authorization`                      | HTTP 401 · `"Autenticação necessária"` · `success: false` |
| `GET /api/app/usuarios` com token de role `OPERADOR`            | HTTP 403 · `"Acesso negado"` · `success: false`           |
| `GET /api/app/pedidos` com token da empresa B                   | HTTP 200 · `data.content = []` (isolamento tenant)        |
| `POST /api/app/pedidos/9999/emitir`                             | HTTP 404 · `data: null`                                   |
| `POST /api/app/pedidos/{id}/cancelar` com `justificativa` vazia | HTTP 400 · mensagem de mínimo 15 caracteres               |

### 9.3 Comportamento Esperado em HOM-SP

> `[OPERACIONAL]` O ambiente de homologação SEFAZ-SP (`tpAmb=2`) utiliza o schema `SP_NFE_PL_008i2`, que retorna `cStat=225` ("Rejeição: Falha no Schema XML"). **Este comportamento é uma limitação do ambiente HOM da SEFAZ-SP e não ocorre em PRD.**

Resultado típico de `POST /api/app/pedidos/{id}/emitir` em HOM-SP:

```
HTTP 200
data.chaveNfe:    "35260512..." (44 dígitos) → transmissão chegou à SEFAZ
data.soapRetorno: contém cStat=225           → limitação HOM, não é erro do sistema
pedido.status:    "AGUARDANDO" → normal em HOM (lote aceito, cStat=104); não ocorre em PRD
```

> `[CONTRATO]` Para validar o fluxo completo em HOM, verificar `data.soapRetorno` bruto do `/emitir` e `data.consultaSefaz` da situação — esses campos contêm a resposta real da SEFAZ independente do status final do pedido.

---

## 10. Observações de Homologação

| # | Observação                                                 | Impacto                                                 |
|---|------------------------------------------------------------|---------------------------------------------------------|
| 1 | `cStat=225` é comportamento normal em HOM-SP               | Não bloqueia validação do fluxo técnico                 |
| 2 | O campo `"environment"` no `/ping` reflete o perfil Spring ativo       | Usar apenas como indicador de diagnóstico               |
| 3 | Token expira em 1 hora                                     | Implementar renovação em fluxos longos                  |
| 4 | Lista paginada de pedidos não inclui itens                 | Sempre usar `GET /{id}` para obter itens                |
| 5 | CC-e e cancelamento exigem `nProt` disponível              | Consultar `/situacao` antes de cancelar após AGUARDANDO |
| 6 | Produto com `estado=0` é rejeitado no pedido               | Verificar `estado` antes de referenciar produto         |
| 7 | Estado `ERRO` é terminal via API                           | Acionar suporte para recuperação manual                 |
| 8  | Endpoint AN HOM pode retornar HTTP 403 em redes locais      | Limitação da SEFAZ federal — não afeta PRD nem fluxo OMS principal          |
| 9  | `POST /batch` sempre retorna HTTP 207 — mesmo com todos os itens com sucesso | Não tratar HTTP 207 como erro — inspecionar `resultados[].status` por item |
| 10 | Toda resposta inclui `X-Request-Id` no header de resposta   | Usar para correlacionar requisições OMS com logs do servidor para diagnóstico |
| 11 | Token OMS não muda ao adicionar novo CNPJ (cenários B, C, D) | O OMS não precisa atualizar o token ao expandir a cobertura de CNPJs de um cliente |
| 12 | `cnpjEmitente` omitido no pedido OMS usa o CNPJ âncora (primeiro autorizado) | Clientes OMS com único CNPJ podem omitir o campo sem impacto |

---

## 11. Changelog

| Versão | Data       | Alteração                                                                                      |
|--------|------------|-----------------------------------------------------------------------------------------------|
| 1.7    | 22-06-2026 | **OMS Multi-CNPJ (V028):** um cliente OMS (`codigoEmpresaOms`) pode autorizar múltiplos CNPJs com um único token. Empresa auto-criada a partir do Subject X.509 (sem pré-cadastro ADMIN). Campo `cnpjEmitente` adicionado ao pedido para seleção do certificado na emissão. Comportamento por cenário (A/B/C/D) documentado — token nunca muda nos cenários B, C, D. Novos `errorCode`: `COMPANY_INACTIVE`, `CNPJ_NOT_AUTHORIZED`, `CERT_NOT_FOUND_FOR_CNPJ`. Removido: `COMPANY_NOT_FOUND` (empresa agora auto-criada). Smoke test OMS multi-CNPJ adicionado (seção 9.1b). |
| 1.6.1  | 18-06-2026 | Correção de documentação: resposta de `POST /api/integration/fiscal-authorizations` **não usa** o envelope `Result<>` — DTO retornado diretamente na raiz (campos `token`, `empresaId`, `cnpj`, `razaoSocial`, `tokenExpiraEm`). Seções 3.3 e 8.1 corrigidas. |
| 1.6    | 17-06-2026 | Sessão OMS por Certificado A1 — `POST /api/integration/fiscal-authorizations` com header `X-Api-Key`; sem login de usuário para o OMS; token técnico por empresa; reautorização (troca de certificado) e revogação documentadas. Novos `errorCode`: `INVALID_API_KEY`, `COMPANY_NOT_FOUND`, `INVALID_CERTIFICATE`, `CNPJ_CERTIFICATE_MISMATCH`, `CERTIFICATE_EXPIRED`, `AUTHORIZATION_REVOKED`. |
| 1.5    | 10-06-2026 | `POST /api/app/pedidos` — campos fiscais (`codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`) passam a ser **obrigatórios** em cada item e devem ser enviados pelo OMS. O sistema não copia mais dados fiscais do produto cadastrado. Campo ausente retorna HTTP 400. |
| 1.4    | 01-06-2026 | Header de rastreabilidade `X-Request-Id`; idempotência via `externalOrderId`; batch upsert (`POST /api/app/produtos/batch`); endpoints de Manifestação do Destinatário. |
| 1.3    | 27-05-2026 | `cfop` opcional no produto (`POST /api/app/produtos`); rotação de senha de certificado (M3); esclarecimentos sobre geração de DANFE. |
| 1.2    | 18-05-2026 | Controle de estoque com reserva atômica; Fase 12-B DANFE; isolamento multi-tenant confirmado em HOM. |
