# Checklist de Onboarding — OMS Logística × Borurio ERP Fiscal BR

| Atributo               | Valor                                    |
|------------------------|------------------------------------------|
| Versão                 | 1.15                                     |
| Data                   | 2026-08-10                               |
| Ambiente de referência | HOM — release `4a39a88`, disponível para os testes que o CC considerar necessários; acesso externo fornecido apenas durante janela controlada de teste; nenhuma URL fixa deve ser assumida pelo time integrador |
| Documento de suporte   | `docs/manual/INTEGRATION_CONTRACT_EN.md` |
| Status                 | IMPLEMENTADO, VALIDADO EM HOM — token OMS rotacionado, aguardando testes do CC |

> **Nota de status:** uma nova emissão real controlada foi validada em HOM em 22-07-2026 com o release `4a39a88` (`cStat=100`), confirmando o fluxo completo — autenticação, resolução de certificado por CNPJ, validação de CFOP × destino (Bloco 4/5), geração/assinatura/transmissão do XML, persistência da autorização e `<modFrete>2</modFrete>` no XML transmitido. A proteção contra emissão concorrente, o contexto multi-CNPJ nos eventos pós-autorização de emissão, a numeração baseline e o `indFinal` foram cobertos por essa mesma autorização real. O smoke test multi-CNPJ dos eventos pós-emissão (cancelamento, CC-e, consulta, inutilização) permanece pendente — ainda não exercitado contra a SEFAZ real em contexto multi-CNPJ.
>
> **Autenticação:** o token OMS foi rotacionado em 22-07-2026 (o anterior foi invalidado). O novo token será entregue ao CC em canal controlado separado antes do início dos testes dele — não afeta os passos deste checklist, que descreve o mecanismo de obtenção/renovação de token, não um valor específico.

---

## Como usar este checklist

Execute os itens em ordem. Cada bloco depende do anterior. Não avance para o próximo bloco se houver item não concluído marcado como **[BLOQUEANTE]**.

---

## Bloco Local — Execução local (usar enquanto HOM externo estiver pendente)

> Use este bloco se ainda não tiver recebido a URL do túnel Cloudflare da sessão atual (a URL é efêmera, muda a cada reinício, e é fornecida por Bruno via canal seguro antes de cada teste — não existe domínio fixo).
> Quando a URL do HOM externo for fornecida, pule este bloco e siga direto para o Bloco 0.

- [ ] Receber credenciais DEV de Bruno via canal seguro (senha do banco, JWT secret, senha do certificado)
- [ ] Copiar o template: `docker/env/.env.dev.template` → `docker/env/.env.dev`
- [ ] Preencher os campos obrigatórios no `.env.dev` com os valores recebidos
- [ ] Subir o ambiente local:

```bash
docker compose -f docker/docker-compose.dev.yml --env-file docker/env/.env.dev up -d
```

- [ ] Aguardar containers subirem (~30s) e verificar healthcheck:

```
GET http://localhost:8080/api/test/ping
```

Resposta esperada:
```json
{ "status": "UP" }
```

- [ ] A partir daqui, substituir a URL do túnel HOM por `http://localhost:8080` em todos os blocos seguintes
- [ ] **Atenção:** ambiente DEV usa `tpAmb=2` (homologação SEFAZ) — nunca emite NF-e real

---

## Bloco 0 — Pré-requisitos

- [ ] **[BLOQUEANTE]** Receber e-mail e senha de usuário com role `OPERADOR` criado pelo ADMIN
- [ ] **[BLOQUEANTE]** Receber a `X-Api-Key` do integrador OMS — fornecida pelo ADMIN do Borurio via canal seguro
- [ ] **[BLOQUEANTE]** Receber de Bruno a URL efêmera do túnel Cloudflare ativo para esta sessão (via canal seguro — a URL muda a cada reinício, não existe domínio fixo)
- [ ] **[BLOQUEANTE]** Confirmar que o túnel está respondendo: `GET <url-do-túnel>/api/test/ping` antes de iniciar os testes. Comandos de setup em `docs/manual/ROTEIRO_ENTREGA_TIME_CHINES.md` Bloco 1.
- [ ] Ter cliente HTTP configurado (Postman ou equivalente)
- [ ] Importar `docs/postman/borurio-erp-collection.json` (10 pastas, 49 requests)
- [ ] Ler `docs/manual/INTEGRATION_CONTRACT_EN.md` completo antes de executar qualquer chamada

---

## Bloco 0B — Autorização Fiscal OMS (V028 — Multi-CNPJ)

> Este bloco substitui o **Bloco 1** para o OMS. O OMS não faz login com usuário e senha — autentica diretamente com o certificado A1 da empresa emitente.
>
> **Modelo multi-CNPJ (V028+):** um mesmo `codigoEmpresaOms` pode ter múltiplos CNPJs autorizados sob o **mesmo token**. Adicionar um segundo CNPJ não altera nem revoga o token existente. A empresa emitente é **criada automaticamente** a partir dos dados do Subject X.509 do certificado — não é necessário pré-cadastrá-la via ADMIN.

> **[BLOQUEANTE]** Completar o **Bloco 0** antes de executar este bloco.

- [ ] **[BLOQUEANTE]** Ter em mãos:
  - Arquivo do certificado A1 (`.pfx` ou `.p12`) da empresa emitente
  - Senha do arquivo PKCS12
  - CNPJ da empresa emitente (14 dígitos, sem formatação) — deve coincidir com o CNPJ presente no Subject X.509 do certificado
  - Código da empresa no OMS (`codigoEmpresaOms` — identificador único e estável do seu sistema)
  - `X-Api-Key` recebida no Bloco 0

- [ ] **[BLOQUEANTE]** Codificar o arquivo `.pfx` em Base64:
  ```
  # Linux / macOS
  base64 -i certificado.pfx

  # PowerShell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("certificado.pfx"))
  ```

- [ ] **[BLOQUEANTE]** Executar autorização fiscal:
  ```
  POST /api/integration/fiscal-authorizations
  X-Api-Key: {chave-tecnica-fornecida-pelo-admin}
  Content-Type: application/json
  ```
  ```json
  {
    "codigoEmpresaOms": "{{codigoEmpresaOms}}",
    "cnpj":             "{{cnpjEmitenteHom}}",
    "certBase64":       "<base64 do .pfx>",
    "certSenha":        "<senha do certificado>"
  }
  ```

- [ ] Confirmar resposta HTTP 200 com campo `data.token` presente (formato JWT)
- [ ] Confirmar campo `data.tokenExpiraEm` (data de vencimento do certificado A1)
- [ ] Confirmar campo `data.razaoSocial` corresponde à empresa esperada
- [ ] Confirmar campo `data.cnpj` corresponde ao CNPJ do certificado enviado
- [ ] Confirmar campo `data.empresaId` — identifica a empresa âncora do cliente OMS (sempre o primeiro CNPJ autorizado)
- [ ] Guardar o `token` retornado — usar em todas as requisições seguintes como `Authorization: Bearer {token}`

**Respostas de erro esperadas (para validação):**

| Cenário | `errorCode` esperado | HTTP |
|---|---|---|
| `X-Api-Key` ausente ou inválida | `INVALID_API_KEY` | 401 |
| Empresa existe mas está inativa (`ativo=0`) | `COMPANY_INACTIVE` | 422 |
| Base64 do `.pfx` malformado | `INVALID_CERTIFICATE` | 422 |
| Senha do `.pfx` incorreta | `INVALID_CERTIFICATE` | 422 |
| CNPJ enviado ≠ CNPJ do certificado | `CNPJ_CERTIFICATE_MISMATCH` | 422 |
| Certificado vencido | `CERTIFICATE_EXPIRED` | 422 |

> **Nota:** `COMPANY_NOT_FOUND` não ocorre neste endpoint. Se a empresa não existir, ela é criada automaticamente a partir do Subject X.509 do certificado.

**Comportamento de reautorização e multi-CNPJ:**

O Borurio aplica automaticamente um dos quatro cenários ao receber uma chamada de autorização:

| Cenário | Condição | Resultado |
|---|---|---|
| A — Novo cliente OMS | Primeiro uso do `codigoEmpresaOms` | Novo token emitido; empresa auto-criada a partir do X.509 |
| B — Mesmo CNPJ, mesmo cert | Certificado SHA-256 idêntico já registrado | **Token string idêntico** retornado; sem alteração no banco |
| C — Mesmo CNPJ, cert novo | CNPJ já autorizado, thumbprint diferente | Cert anterior desativado; JTI mantido; `tokenExpiraEm` atualizado |
| D — CNPJ novo | `codigoEmpresaOms` existente, CNPJ inédito | CNPJ adicionado; **token idêntico** retornado |

- [ ] Para adicionar um segundo CNPJ ao mesmo cliente OMS (cenário D): repetir o `POST` com o certificado do CNPJ2 e o **mesmo** `codigoEmpresaOms`
- [ ] Confirmar que o `token` retornado é **idêntico** ao anterior — o token não muda ao adicionar CNPJs
- [ ] Para trocar o certificado de um CNPJ já autorizado (cenário C): repetir o `POST` com o novo certificado e o mesmo CNPJ — o JTI é mantido, somente `tokenExpiraEm` é atualizado
- [ ] **Atenção:** o token **não** é revogado ao adicionar CNPJs (cenário D). O mesmo token cobre todos os CNPJs autorizados do cliente OMS

---

## Bloco 1 — Autenticação (usuário interno — não usar para OMS)

- [ ] **[BLOQUEANTE]** `POST /auth/login` com `{"username": "<email>", "password": "<senha>"}`
- [ ] Confirmar resposta com campo `token` presente (formato JWT — 3 segmentos separados por `.`)
- [ ] Configurar header `Authorization: Bearer <token>` em todos os requests seguintes
- [ ] **Atenção:** token expira em 1 hora — implementar renovação automática antes de usar em produção

**Resposta esperada:**
```json
{
  "code": 200,
  "message": "Autenticação bem-sucedida",
  "token": "<jwt>"
}
```

---

## Bloco 2 — Healthcheck

- [ ] `GET /api/test/ping` (sem autenticação)
- [ ] Confirmar `"status": "UP"` na resposta

---

## Bloco 3 — Cadastro de produto

- [ ] **[BLOQUEANTE]** `POST /api/app/produtos` com todos os campos obrigatórios:

| Campo       | Tipo    | Restrição                         |
|-------------|---------|-----------------------------------|
| `codigo`    | string  | obrigatório, não vazio            |
| `descricao` | string  | obrigatório, não vazio            |
| `ncm`       | string  | obrigatório, exatamente 8 dígitos |
| `cfop`      | string  | opcional · default `"5102"` se omitido |
| `unidade`   | string  | obrigatório, não vazio            |
| `preco`     | decimal | obrigatório, mínimo 0.01          |
| `origem`    | integer | obrigatório                       |

- [ ] Confirmar `data.id` retornado na resposta — guardar o `id` do produto
- [ ] Confirmar `data.estado` = `1` (produto ativo)
- [ ] Confirmar `data.csosn` preenchido (default `"400"` se não enviado)

**Campos fiscais opcionais — se não enviados, recebem defaults:**

| Campo     | Default   |
|-----------|-----------|
| `cfop`    | `"5102"`  |
| `csosn`   | `"400"`   |
| `estoque` | `0`       |

**Atenção:** Produto com `estado=0` (inativo) é rejeitado na criação de pedido (`HTTP 400`).

**Estoque — comportamento obrigatório para integração:**

O sistema controla estoque com reserva atômica. A OMS deve garantir que o estoque do produto seja suficiente antes de emitir. Use o endpoint abaixo para consultar saldo antes de enviar o pedido:

```
GET /api/app/produtos/{id}/estoque
```

Resposta:
```json
{
  "code": 200,
  "data": {
    "produtoId": 1,
    "estoqueTotal": "100.0000",
    "estoqueReservado": "10.0000",
    "estoqueDisponivel": "90.0000"
  }
}
```

Para adicionar estoque (entrada de mercadoria), use — **requer role ADMIN:**
```
POST /api/app/produtos/{id}/estoque/entrada
Body: {"quantidade": 50.00, "observacao": "Entrada inicial OMS"}
```

**Busca de produto por código interno (SKU) — fluxo em dois passos:**

Quando a OMS conhece o SKU do produto mas não o `id` interno do Borurio, use o fluxo em dois passos:

```
Passo 1 — GET /api/app/produtos/codigo/{sku}   → recupera produto completo + id
Passo 2 — GET /api/app/produtos/{id}/estoque   → consulta saldo com o id retornado
```

Exemplo (Passo 1):
```
GET /api/app/produtos/codigo/SKU-001
Authorization: Bearer {token}
```
Resposta (campo relevante):
```json
{
  "code": 200,
  "data": {
    "id": 3,
    "codigo": "SKU-001",
    ...
  }
}
```

---

## Bloco 3B — Cadastro em lote de produtos (Batch Upsert)

> Use este bloco quando a OMS precisar sincronizar o catálogo de produtos em lote. O endpoint aceita até 200 produtos por requisição e processa cada um de forma independente — itens inválidos são rejeitados sem bloquear os demais.

- [ ] `POST /api/app/produtos/batch` com payload:

```json
{
  "produtos": [
    { "codigo": "SKU-B01", "descricao": "Produto Batch Novo", "ncm": "84715011", "unidade": "UN", "preco": 99.00 },
    { "codigo": "SKU-B01", "descricao": "Produto Batch Novo", "ncm": "84715011", "unidade": "UN", "preco": 99.00 }
  ]
}
```

- [ ] Confirmar que a resposta é **HTTP 207** (não HTTP 200)
- [ ] Confirmar campos obrigatórios na resposta: `total`, `criados`, `atualizados`, `rejeitados`, `resultados[]`
- [ ] Confirmar `resultados[0].status = "CRIADO"` e `resultados[0].produtoId` não nulo
- [ ] Confirmar `resultados[1].status = "REJEITADO"` com `errorCode = "DUPLICATE_CODIGO_IN_BATCH"` (segundo item com mesmo `codigo`)

**Validar upsert (atualização de produto existente):**

- [ ] Repetir `POST /api/app/produtos/batch` com o mesmo `codigo` (`SKU-B01`) e preço diferente
- [ ] Confirmar `resultados[0].status = "ATUALIZADO"` e `resultados[0].produtoId` igual ao anterior
- [ ] Confirmar que `estoque`, `estoque_reservado` e `estado` do produto não foram alterados

**Validar rejeição por dado inválido:**

- [ ] Enviar item com `ncm: "123"` (menos de 8 dígitos)
- [ ] Confirmar `resultados[n].status = "REJEITADO"` com `errorCode = "VALIDATION_ERROR"`
- [ ] Confirmar que outros itens válidos no mesmo lote foram processados normalmente

**Validar limite do lote:**

- [ ] Enviar `POST /api/app/produtos/batch` com lista vazia (`"produtos": []`) → confirmar **HTTP 400**
- [ ] (Opcional) Enviar mais de 200 itens → confirmar **HTTP 422** com `errorCode = "BATCH_LIMIT_EXCEEDED"`

**Campos NOT atualizados em upsert:** `codigo`, `estoque`, `estoque_reservado`, `estado`

**Defaults aplicados se não enviados:**

| Campo   | Default  |
|---------|----------|
| `cfop`  | `"5102"` |
| `csosn` | `"400"`  |
| `origem`| `0`      |

---

## Bloco 4 — Criação de pedido

- [ ] **[BLOQUEANTE]** `POST /api/app/pedidos` com campos obrigatórios:

| Campo                        | Tipo    | Restrição                                                                              |
|------------------------------|---------|----------------------------------------------------------------------------------------|
| `cnpjEmitente`               | string  | **obrigatório para clientes OMS multi-CNPJ** — 14 dígitos; seleciona o certificado ativo. **Se omitido, o pedido usa a empresa padrão associada ao token** — confirme que essa empresa tem cadastro fiscal (IE) válido perante a SEFAZ antes de testar emissão, ver nota abaixo |

> **Validação obrigatória antes de testar emissão:** a empresa selecionada por `cnpjEmitente` (ou a empresa padrão, se o campo for omitido) precisa ter Inscrição Estadual válida perante a SEFAZ. Uma emissão real em HOM pela empresa padrão atualmente associada ao token do CC retornou `cStat=209` ("IE do emitente inválida") — não é falha de autenticação, XML, assinatura ou arquitetura, é pendência cadastral daquela empresa específica. Para testes de emissão, informe explicitamente o `cnpjEmitente` de uma empresa vinculada ao token com cadastro fiscal aceito pela SEFAZ.
| `destCnpjCpf`                | string  | obrigatório, não vazio                                                                 |
| `destRazaoSocial`            | string  | obrigatório, não vazio                                                                 |
| `externalOrderId`            | string  | opcional — ID externo OMS para idempotência (máx 100 chars)                            |
| `itens`                      | array   | obrigatório, mínimo 1 item                                                             |
| `itens[].produtoId`          | long    | obrigatório                                                 |
| `itens[].quantidade`         | decimal | obrigatório, maior que 0                                    |
| `itens[].valorUnitario`      | decimal | obrigatório, maior que 0                                    |
| `itens[].codigoProduto`      | string  | **obrigatório** — código do produto na NF-e (`cProd`)       |
| `itens[].descricao`          | string  | **obrigatório** — descrição na NF-e (`xProd`)               |
| `itens[].ncm`                | string  | **obrigatório** — exatamente 8 dígitos                      |
| `itens[].cfop`               | string  | **obrigatório** — 4 dígitos (ex: `"5102"`, `"6102"`)        |
| `itens[].unidade`            | string  | **obrigatório** — ex: `UN`, `KG`, `PC`, `CX`               |
| `itens[].origem`             | integer | **obrigatório** — `0`=Nacional · `1`–`8`=Importada          |
| `itens[].csosn`              | string  | **obrigatório** — ex: `"102"`, `"400"`, `"500"`, `"900"`    |
| `emitLogradouro`             | string  | opcional — endereço do emitente (v1.9, ver nota abaixo)     |
| `emitNumero`                 | string  | opcional                                                    |
| `emitBairro`                 | string  | opcional                                                    |
| `emitCodigoMunicipio`        | string  | opcional — código IBGE 7 dígitos                            |
| `emitMunicipio`              | string  | opcional                                                    |
| `emitCep`                    | string  | opcional                                                    |

> **Atenção:** Os campos fiscais acima são obrigatórios e devem ser enviados pelo OMS em cada item. O sistema **não** copia dados fiscais do produto cadastrado. Se algum campo estiver ausente, o pedido é rejeitado com **HTTP 400**.

> **CFOP é snapshot do pedido (20-07-2026):** o CFOP enviado pelo OMS é gravado exatamente como recebido — o Borurio nunca calcula nem corrige o CFOP a partir do cadastro do produto. A validação de compatibilidade com o destino da operação ocorre somente na emissão (`POST /emitir`, Bloco 5), não na criação do pedido.

> **Endereço do emitente (v1.9):** empresas autorizadas via certificado A1 (Bloco 0B) são criadas automaticamente só com CNPJ/razão social/UF — sem endereço, porque o certificado não carrega esse dado. Se o cadastro estiver incompleto, `/emitir` bloqueia com `EMITTER_ADDRESS_INCOMPLETE` (ver Bloco 5). Envie os 6 campos `emit*` acima em qualquer `POST /api/app/pedidos` pra completar automaticamente **apenas os campos ausentes** do cadastro — não sobrescreve endereço já preenchido. Não precisa reenviar em todo pedido depois de completo.

> **`indFinal` e `indIntermed` — não fazem parte do payload:** `indFinal` (indicador de consumidor final) é resolvido internamente a partir do cadastro da empresa emitente. `indIntermed` (indicador de intermediador/marketplace) é serializado internamente como `"0"` no escopo atual (venda direta, sem intermediador) — esse valor não representa regra universal para toda NF-e. O OMS não deve enviar nenhum dos dois campos no payload atual; nenhuma alteração foi feita no contrato de integração. Um cenário de venda via marketplace/plataforma de terceiro permanece evolução futura, condicionada a definição de negócio.

- [ ] Confirmar `data.status` = `"RASCUNHO"` na resposta
- [ ] Confirmar `data.id` retornado — guardar o `id` do pedido
- [ ] Confirmar que os itens na resposta refletem os dados fiscais enviados pelo OMS: `codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`

**Nota:** `naturezaOperacao` é definido automaticamente como `"VENDA DE MERCADORIA"` se não enviado. `serieNfe` padrão é `"1"`.

**Idempotência via `externalOrderId`:**

Quando `externalOrderId` é enviado, um segundo POST com o mesmo valor retorna o pedido já criado — sem criar duplicata.

- [ ] Criar pedido com `"externalOrderId": "OMS-TEST-IDEM-001"` → guardar `data.id` retornado (ex: `42`)
- [ ] Repetir exatamente o mesmo POST com `"externalOrderId": "OMS-TEST-IDEM-001"` → confirmar `data.id` igual a `42`
- [ ] Confirmar que nenhum segundo pedido foi criado no sistema para esse `externalOrderId`

**Nota:** Pedidos enviados sem `externalOrderId` nunca são deduplicados — cada POST cria um pedido distinto.

**Série e numeração da NF-e (atualizado 20-07-2026):** a série NÃO é mais resolvida na criação do pedido — `serieNfe` vem sempre `null` na resposta do `POST`. A série é resolvida junto com o número, atomicamente, só no início de cada tentativa de emissão, a partir da configuração vigente da empresa naquele momento (`PUT /api/integration/fiscal-numbering/{cnpj}`, proposta em `INTEGRATION_CONTRACT_PT-BR.md` seção 6.10 — ainda não confirmada pelo CC nem implantada em HOM). **A OMS não deve enviar série nem número da NF-e no payload de cada pedido** — se enviar, o valor é ignorado silenciosamente, sem erro.

**Cenários negativos — `errorCode`:**

Erros de negócio retornam `HTTP 422` com campo `errorCode` identificável pela OMS. Não dependa do campo `message` para lógica de integração — ele pode mudar entre versões.

- [ ] Tentar criar pedido com `produtoId` inexistente → confirmar `HTTP 422` com `"errorCode": "PRODUCT_NOT_FOUND"`
- [ ] Tentar criar pedido com produto com `estado=0` (inativo) → confirmar `HTTP 422` com `"errorCode": "PRODUCT_INACTIVE"`
- [ ] Tentar criar pedido com `quantidade` maior que o estoque disponível → confirmar `HTTP 422` com `"errorCode": "INSUFFICIENT_STOCK"`
- [ ] Tentar criar pedido com item sem `cfop` → confirmar `HTTP 400` com mensagem `"cfop é obrigatório no item"`
- [ ] Tentar criar pedido com item sem `ncm` → confirmar `HTTP 400` com mensagem `"ncm é obrigatório no item"`
- [ ] Tentar criar pedido com item sem `codigoProduto` → confirmar `HTTP 400` com mensagem `"codigoProduto é obrigatório no item"`
- [ ] (Multi-CNPJ) Tentar criar pedido com `cnpjEmitente` não autorizado para o cliente OMS → confirmar `HTTP 403` com `"errorCode": "CNPJ_NOT_AUTHORIZED"`

Formato de resposta de erro de negócio:
```json
{
  "code": 422,
  "message": "<descrição legível — não usar em lógica>",
  "data": null,
  "errorCode": "INSUFFICIENT_STOCK"
}
```

---

## Bloco 5 — Emissão NF-e

> **v1.9:** `/emitir` aceita pedidos em `RASCUNHO`, `REJEITADO` ou `ERRO` — reemissão usa o **mesmo `pedidoId`**, não cria pedido novo. Uma rejeição da SEFAZ não retorna mais `HTTP 200` — retorna `HTTP 422` com `errorCode: SEFAZ_REJECTED`.

- [ ] **[BLOQUEANTE]** `POST /api/app/pedidos/{id}/emitir` (sem body)
- [ ] Se `HTTP 200`: confirmar que `data.chaveNfe` tem exatamente **44 dígitos** — o lote foi aceito e autorizado ou está aguardando
- [ ] Se `HTTP 422` com `errorCode: EMITTER_ADDRESS_INCOMPLETE`: cadastro da empresa emitente sem endereço — enviar campos `emit*` (Bloco 4) e repetir
- [ ] Se `HTTP 422` com `errorCode: SEFAZ_REJECTED`: verificar `data.cStat`/`data.xMotivo` — corrigir a causa e chamar `/emitir` de novo **no mesmo pedido**
- [ ] Guardar `data.soapRetorno` para diagnóstico se necessário

**Comportamento de estoque durante a emissão:**

| Evento                        | Estoque                                                         |
|-------------------------------|-----------------------------------------------------------------|
| `POST /emitir` chamado        | Reserva atômica — `estoqueDisponivel -= qtd` para cada item     |
| `HTTP 422` retornado          | Estoque insuficiente — reserva não feita; pedido em `RASCUNHO`  |
| Status → `AUTORIZADO`         | Baixa definitiva — `estoqueTotal -= qtd`, reserva liberada      |
| Status → `REJEITADO` ou `ERRO`| Reserva desfeita — `estoqueDisponivel += qtd` para cada item    |
| Status → `AGUARDANDO`         | Reserva mantida — `estoqueDisponivel` permanece bloqueado       |
| Status → `CANCELADO`          | Estorno — `estoqueTotal += qtd`                                 |

**Empresas sem controle de estoque:** para clientes OMS configurados internamente com `controleEstoqueAtivo=false`, nenhuma linha da tabela acima ocorre — `/emitir` nunca reserva, baixa nem estorna estoque, e `INSUFFICIENT_STOCK` nunca é retornado. Configuração feita pelo Borurio, não pelo OMS.

**Comportamento esperado em HOM/SP (corrigido v1.9):**

| Situação                     | O que observar                                                                             |
|------------------------------|--------------------------------------------------------------------------------------------|
| Lote aceito e autorizado pela SEFAZ | `HTTP 200` · `chaveNfe` com 44 dígitos                                              |
| `cStat=225` (`SEFAZ_REJECTED`) | `cStat=225` indica falha de validação do XML/schema. Verificar `data.xMotivo` e o XML transmitido. Desde a correção estrutural de 14-07-2026, esse não é o resultado esperado do fluxo atual. `HTTP 422`, `retryable: false`. |
| Problema cadastral do emitente | Inscrição Estadual inapta, cassada ou outro dado de cadastro inválido junto à Receita/SEFAZ deve ser tratado conforme o `cStat` e o `xMotivo` efetivamente retornados pela SEFAZ na tentativa — cada rejeição tem código próprio, verificar caso a caso. |
| `cStat=100`                  | `AUTORIZADO` — obtido em HOM/SP em 14-07-2026, em teste interno e em teste do integrador chinês via OMS, usando J. ZHENG BIJOUTERIAS (referência operacional atual de HOM). Reconfirmado em 21-07-2026 com o release atual, já incluindo a validação de CFOP × destino (Bloco 4/5). Validação em PRD (`tpAmb=1`) ainda não realizada — ver seção de pendências no `MTF-001` |
| `HTTP 422` + `errorCode: "INVALID_ORDER_STATUS"` | Pedido não está em `RASCUNHO`/`REJEITADO`/`ERRO` — verificar `status` antes de tentar novamente |
| `HTTP 422` + `errorCode: "SEFAZ_REJECTED"`        | SEFAZ processou e rejeitou — ver `data.cStat`/`data.xMotivo`; corrigir e reemitir no mesmo pedido |
| `HTTP 422` + `errorCode: "EMITTER_ADDRESS_INCOMPLETE"` | Cadastro do emitente incompleto — SEFAZ nem foi chamada; completar endereço (Bloco 4) |
| `HTTP 422` + `errorCode: "CFOP_DESTINATION_MISMATCH"` | **(20-07-2026)** CFOP de algum item incompatível com o destino da operação — interna exige CFOP iniciado por `5`, interestadual por `6`. SEFAZ nem foi chamada, nenhum `nNF` foi consumido. Corrigir o CFOP enviado e chamar `/emitir` de novo no mesmo pedido. `retryable: false`. |
| `HTTP 409` + `errorCode: "EMISSAO_EM_ANDAMENTO"` | Outra chamada já assumiu a emissão deste pedido. Não criar pedido novo. Aguardar um intervalo curto, consultar `GET /api/app/pedidos/{id}/situacao` e repetir `/emitir` somente se a situação ainda permitir. `retryable: true`. |
| `HTTP 422` + `errorCode: "IND_FINAL_PADRAO_INVALIDO"` | Configuração fiscal inválida no cadastro da empresa emitente (interna ao Borurio) — não é um erro corrigível pelo OMS; acionar a operação responsável pelo Borurio. `retryable: false`. |
| `HTTP 503` + `errorCode: "SEFAZ_TIMEOUT"`/`"SEFAZ_UNAVAILABLE"` | Falha de rede transitória — `retryable: true`, seguro reemitir sem alterar nada |
| `HTTP 422` + `errorCode: "LOCAL_PROCESSING_FAILURE"` (10-08-2026) | Falha comprovadamente local, antes de qualquer possibilidade de transmissão à SEFAZ — não é rejeição SEFAZ, timeout nem resultado incerto. `retryable: false` — corrigir a causa antes de chamar `/emitir` de novo no mesmo pedido |
| `HTTP 500`                                        | Exceção não classificada, sem evidência de fase — pedido vai para `ERRO`, `retryable: false`        |

- [ ] Tentar emitir pedido com `status` = `AUTORIZADO`/`AGUARDANDO`/`CANCELADO` → confirmar `HTTP 422` com `"errorCode": "INVALID_ORDER_STATUS"`
- [ ] (v1.9) Se um pedido ficar `REJEITADO` ou `ERRO`: chamar `/emitir` de novo **no mesmo `pedidoId`** → confirmar que não precisa criar pedido novo e que `chaveNfe` retornada é diferente da tentativa anterior

---

## Bloco 6 — Consulta de situação

- [ ] `GET /api/app/pedidos/{id}/situacao`
- [ ] Confirmar campos sempre presentes: `pedidoId`, `numero`, `status`, `chaveNfe`, `consultaSefaz`
- [ ] Verificar `status` — ver máquina de estados abaixo

**Máquina de estados do pedido:**

| Status       | Significado                         | Próxima ação permitida    |
|--------------|-------------------------------------|---------------------------|
| `RASCUNHO`   | Criado, não transmitido             | Emitir                    |
| `AUTORIZADO` | cStat=100 — aprovado pela SEFAZ     | Cancelar / CC-e           |
| `AGUARDANDO` | cStat=104 ou resposta não parseável | Consultar novamente       |
| `REJEITADO`  | cStat ≥ 200 — SEFAZ recusou         | Emitir de novo (mesmo pedido, v1.9) |
| `ERRO`       | Exceção durante transmissão         | Emitir de novo (mesmo pedido, v1.9) |
| `CANCELADO`  | Cancelamento autorizado             | Nenhuma — imutável        |

---

## Bloco 7 — Operações pós-autorização (opcional em HOM)

Estes itens só são executáveis quando `status = "AUTORIZADO"`. Desde 14-07-2026 o ambiente HOM/SP retorna `cStat=100` para empresas com cadastro fiscal aceito pela SEFAZ na emissão de homologação (ver Bloco 5) — os testes abaixo são realizáveis normalmente nessa condição.

- [ ] `POST /api/app/pedidos/{id}/cancelar` com `{"justificativa": "<texto mínimo 15 chars>"}`
- [ ] `POST /api/app/pedidos/{id}/cce` com `{"correcao": "<texto mínimo 15 chars>"}`

**Contexto fiscal multi-CNPJ nestes eventos:** empresa, certificado e UF são resolvidos pelo CNPJ real da operação — não por configuração global nem pela empresa-âncora do cliente OMS. O CNPJ embutido na chave de acesso é validado automaticamente contra esse contexto antes de prosseguir. Esse comportamento está implementado e coberto por teste automatizado; smoke test real multi-CNPJ desses eventos em HOM ainda está pendente.

| `errorCode` | HTTP | `retryable` | Situação |
|---|---|---|---|
| `DOCUMENTO_CNPJ_DIVERGENTE` | 422 | `false` | O CNPJ da chave de acesso do documento não corresponde ao CNPJ da empresa resolvida para a operação — a execução falha explicitamente em vez de prosseguir com contexto/certificado de outra empresa |

---

## Bloco 7B — Manifestação do Destinatário (opcional — quando a OMS recebe NF-e de terceiros)

> Este bloco é independente do fluxo de emissão OMS→Borurio. Use quando a empresa precisar se posicionar perante a SEFAZ sobre uma NF-e recebida.

- [ ] `POST /api/fiscal/nfe/manifestar` com body:

```json
{
  "chaveNfe":         "<44 dígitos>",
  "tipoEvento":       "210200",
  "cnpjDestinatario": "<14 dígitos sem formatação>"
}
```

| Evento | Nome                        | `xJust` obrigatório |
|--------|-----------------------------|---------------------|
| 210200 | Ciência da Operação         | Não                 |
| 210210 | Confirmação da Operação     | Não                 |
| 210220 | Desconhecimento da Operação | Não                 |
| 210240 | Operação Não Realizada      | Sim (mín 15 chars)  |

- [ ] Confirmar resposta `HTTP 200`, `"success": true`
- [ ] Confirmar que `nfe_log` registra o evento com status `SUCCESS`

**Nota HOM:** O endpoint AN HOM (`hom.nfe.fazenda.gov.br`) retorna HTTP 403 de IPs residenciais/locais — limitação da infraestrutura federal. A funcionalidade estará disponível em ambiente corporativo ou PRD.

---

## Bloco 8 — Verificações de segurança

- [ ] Confirmar que request sem token retorna `HTTP 401` com `{"code": 401, "message": "Autenticação necessária", "success": false}`
- [ ] Confirmar que usuário `OPERADOR` acessando `/api/app/usuarios` retorna `HTTP 403`
- [ ] Confirmar que `empresa_id` NÃO é enviado no body de nenhuma requisição — é extraído automaticamente do JWT
- [ ] Confirmar que toda resposta contém o header `X-Request-Id` (UUID) — presente mesmo em respostas HTTP 401
- [ ] (Opcional) Enviar `X-Request-Id: oms-teste-001` na requisição e confirmar que o mesmo valor é retornado no header de resposta

---

## Bloco 9 — Bloqueadores para integração PRD

Estes itens não são responsabilidade do time chinês, mas bloqueiam o go-live em PRD:

| Bloqueador                                         | Responsável       | Status   |
|----------------------------------------------------|-------------------|----------|
| Configurar certificado A1 da empresa emitente aprovada para PRD, com CNPJ, Inscrição Estadual, credenciamento e situação cadastral ativos e previamente validados (`tpAmb=1`) | Operações / Bruno | BLOQUEADO PARA PRD |
| `CERT_ENCRYPTION_KEY` configurada por mecanismo seguro de gestão de segredos, fora do repositório e dos arquivos versionados | Operações / Bruno | BLOQUEADO PARA PRD |
| URL de PRD definida e acessível                    | Operações         | BLOQUEADO PARA PRD |
| Gerar `X-Api-Key` de produção para o integrador OMS e entregá-la ao CC via canal seguro | Bruno / Operações | BLOQUEADO PARA PRD |
| Executar Bloco 0B (autorização fiscal) em HOM — empresa 1 (CNPJ1) | CC / Xiao Li | PENDENTE TÉCNICO |
| Executar Bloco 0B para empresa 2 (CNPJ2) — adiciona segundo CNPJ ao mesmo token (cenário D) | CC / Xiao Li | PENDENTE TÉCNICO |
| Validar M5 smoke test com certificado A1 real em HOM (emissão SEFAZ com cnpjEmitente multi-CNPJ) | CC / Xiao Li | CONCLUÍDO — validado internamente em 22-07-2026 (`cStat=100`, empresa vinculada com IE aceita pela SEFAZ nessa emissão em HOM); validação pelo próprio CC ainda pendente |

> CNPJ1 e CNPJ2 representam empresas emitentes habilitadas para a rodada de HOM. Os valores reais serão fornecidos por canal seguro antes do teste.
>
> **Nota separada:** a JCHO GLOBAL LTDA está registrada em HOM com Inscrição Estadual cassada por inatividade — impedimento cadastral externo, conhecido, não corrigível por código. Não deve ser usada nos testes atuais de autorização enquanto permanecer com situação cadastral impeditiva, nem como referência de certificado de produção. Esta nota não associa a JCHO GLOBAL a CNPJ1 ou CNPJ2 acima.

> **Nota — contingência:** Contingência da NF-e ainda não implementada. A modalidade, os critérios de ativação, a reconciliação e os testes deverão ser definidos antes de PRD, com base na documentação oficial vigente e validação fiscal.

---

## Referências

| Documento                        | Caminho                                       |
|----------------------------------|-----------------------------------------------|
| Contrato de integração (EN)      | `docs/manual/INTEGRATION_CONTRACT_EN.md`      |
| Manual técnico motor fiscal (EN) | `docs/manual/MTF-001_motor-fiscal-nfe_EN.md`  |
| Postman collection               | `docs/postman/borurio-erp-collection.json`    |
| Swagger UI (HOM)                 | `<url-do-túnel>/swagger-ui/index.html`        |
