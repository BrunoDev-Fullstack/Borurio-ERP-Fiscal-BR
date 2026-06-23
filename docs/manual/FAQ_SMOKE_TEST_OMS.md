# FAQ — Smoke Test OMS em HOM

| Atributo               | Valor                              |
|------------------------|------------------------------------|
| Versão                 | 1.3                                |
| Data                   | 2026-06-22                         |
| Ambiente de referência | HOM — URL temporária por sessão (Cloudflare Tunnel) |
| Relacionado a          | `CHECKLIST_OMS_ONBOARDING.md`      |

Perguntas frequentes durante a execução do smoke test no ambiente HOM.

---

**1. Não enviei `cfop` ao cadastrar o produto. Isso é um problema?**

Não. O campo `cfop` é opcional. Se não for enviado, o Borurio aplica automaticamente o valor `"5102"` (operação intra-estado). Confirme no retorno da criação do produto: `data.cfop` virá preenchido com `"5102"` mesmo que o campo não tenha sido enviado no payload.

---

**2. Não enviei `csosn` ao cadastrar o produto. Qual valor será usado?**

O sistema aplica o padrão `"400"` (não contribuinte do ICMS) quando o campo não é enviado. O valor aparecerá preenchido na resposta da criação do produto e também no snapshot fiscal dos itens do pedido — campo `csosn` dentro de `data.itens[]`.

---

**3. O pedido foi criado mas o campo `chaveNfe` está `null`. Errei alguma coisa?**

Não. O campo `chaveNfe` fica `null` enquanto o pedido estiver em `RASCUNHO`. Ele só é preenchido após o `POST /api/app/pedidos/{id}/emitir`, quando a NF-e é transmitida para a SEFAZ. Antes da emissão, `null` é o comportamento correto.

---

**4. O `POST /emitir` retornou `cStat=225` no `soapRetorno`. Isso é um erro do OMS?**

Não. O `cStat=225` ("Rejeição: Falha no Schema XML") é um comportamento esperado no ambiente de homologação da SEFAZ-SP. Ele ocorre porque o processador `SP_NFE_PL_008i2` do HOM utiliza um schema mais restritivo que o de produção. A integração não tem erro.

Os indicadores corretos de que o fluxo funcionou são:

- `data.chaveNfe` com 44 dígitos — confirma que o lote chegou à SEFAZ
- `pedido.status = "AGUARDANDO"` — comportamento normal em HOM

Esse comportamento não ocorrerá em PRD.

---

**5. Como verifico o saldo de estoque do produto antes de emitir?**

Use o endpoint:

```
GET /api/app/produtos/{id}/estoque
Authorization: Bearer {token}
```

Resposta esperada:

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

Use o campo `estoqueDisponivel` para decidir se o pedido pode ser emitido. Se a quantidade solicitada exceder `estoqueDisponivel`, o `/emitir` retorna HTTP 422 e o pedido permanece em `RASCUNHO`.

---

**6. Preciso enviar o campo `serieNfe` na criação do pedido?**

Não. O campo `serieNfe` não deve ser enviado no body. O Borurio controla a série internamente. A resposta da criação do pedido mostrará `"serieNfe": "1"` como padrão — não é necessário enviar nem armazenar esse valor no OMS.

---

**7. Qual é a URL do ambiente HOM para os testes?**

A URL externa do HOM é gerada via Cloudflare Quick Tunnel e muda a cada reinício do servidor. Não utilize uma URL salva de sessão anterior.

Antes de iniciar cada sessão de testes, avise para que a URL ativa seja informada. Em seguida, confirme que o ambiente está operacional com:

```
GET {tunnel-url}/api/test/ping
```

Resposta esperada:

```json
{
  "status":      "UP",
  "environment": "hom"
}
```

Se o campo `"environment"` retornar `"hom"`, o ambiente está correto e os testes podem começar.

---

**8. O que é `externalOrderId` e quando devo usá-lo?**

`externalOrderId` é um identificador externo enviado pela OMS no corpo do `POST /api/app/pedidos`. Ele permite que a OMS crie pedidos de forma idempotente: se o mesmo `externalOrderId` for enviado mais de uma vez (ex: retry após timeout de rede), o Borurio retorna o pedido já existente sem criar duplicata.

Regras:
- O campo é **opcional**. Se omitido, cada POST cria um pedido distinto.
- A unicidade é por empresa: dois clientes diferentes podem usar o mesmo valor sem conflito.
- Máximo 100 caracteres.
- Se enviado, o valor é retornado no campo `data.externalOrderId` de todos os endpoints que retornam pedido.

Exemplo de payload com idempotência:
```json
{
  "externalOrderId":   "OMS-20260601-0001",
  "destCnpjCpf":       "12345678000195",
  "destRazaoSocial":   "Cliente Exemplo",
  "destUf":            "SP",
  "itens": [
    { "produtoId": 1, "quantidade": 2, "valorUnitario": 50.00 }
  ]
}
```

---

**9. O endpoint retornou HTTP 422 com campo `errorCode`. Como devo tratar isso?**

Erros de negócio retornam `HTTP 422` com um campo `errorCode` padronizado no corpo da resposta. A OMS deve usar `errorCode` para decidir a ação programática — não o campo `message`, que é legível por humanos e pode mudar entre versões.

Códigos disponíveis:

| `errorCode`              | Situação                                              | HTTP | Ação sugerida para a OMS                          |
|--------------------------|-------------------------------------------------------|------|---------------------------------------------------|
| `PRODUCT_NOT_FOUND`      | `produtoId` não existe no Borurio                     | 422  | Sincronizar catálogo de produtos                  |
| `PRODUCT_INACTIVE`       | Produto existe, mas está inativo (`estado=0`)         | 422  | Reativar produto ou remover do pedido             |
| `INSUFFICIENT_STOCK`     | Quantidade solicitada excede `estoqueDisponivel`      | 422  | Consultar `/api/app/produtos/{id}/estoque` e ajustar |
| `INVALID_ORDER_STATUS`   | Operação não permitida no status atual do pedido      | 422  | Verificar `status` via `/api/app/pedidos/{id}/situacao` |
| `CNPJ_NOT_AUTHORIZED`    | `cnpjEmitente` não tem certificado ativo para este cliente OMS | 403 | Executar Bloco 0B para autorizar o CNPJ |
| `CERT_NOT_FOUND_FOR_CNPJ`| Certificado do `cnpjEmitente` não encontrado na emissão | 422 | Verificar se o CNPJ foi autorizado via Bloco 0B |
| `COMPANY_INACTIVE`       | Empresa existe mas está inativa (`ativo=0`)           | 422  | Contatar o administrador do Borurio |
| `INVALID_API_KEY`        | `X-Api-Key` ausente ou inválida                       | 401  | Verificar a chave recebida no Bloco 0 |
| `CERTIFICATE_EXPIRED`    | Certificado A1 vencido                                | 422  | Renovar o certificado A1 e reautorizar |

Formato de resposta de erro de negócio:
```json
{
  "code":      422,
  "message":   "Estoque insuficiente para \"Produto A\" (disponível: 5.00, solicitado: 10.00)",
  "data":      null,
  "errorCode": "INSUFFICIENT_STOCK"
}
```

**Atenção:** Respostas de sucesso não contêm o campo `errorCode`. Apenas verifique esse campo quando o HTTP status for 4xx.

---

---

**10. O endpoint `/batch` retornou HTTP 207. É normal mesmo que todos os itens tenham sido criados com sucesso?**

Sim. O endpoint `POST /api/app/produtos/batch` retorna **sempre HTTP 207 Multi-Status**, independentemente do resultado. Isso inclui os casos em que todos os itens foram criados, todos atualizados, ou todos rejeitados. Não trate HTTP 207 como erro — verifique o resultado de cada item em `resultados[].status`.

O critério correto de PASS para o smoke test é:
- HTTP 207 recebido ✓
- `criados` e/ou `atualizados` > 0 ✓
- `resultados[n].produtoId` preenchido para itens CRIADO/ATUALIZADO ✓

---

**11. Um item do lote foi rejeitado com `status: "REJEITADO"`. Os outros itens ainda foram processados?**

Sim. O batch processa cada item de forma independente. Um item rejeitado não bloqueia os demais. O campo `rejeitados` na raiz da resposta indica quantos itens foram rejeitados, e cada entrada em `resultados[]` mostra o resultado individual.

Para identificar o motivo da rejeição, use `resultados[n].errorCode`:

| `errorCode`                  | Causa                                                      | Ação sugerida                                 |
|------------------------------|------------------------------------------------------------|-----------------------------------------------|
| `VALIDATION_ERROR`           | NCM inválido, campo obrigatório vazio, preço ≤ 0           | Corrigir o item e reenviar somente ele        |
| `DUPLICATE_CODIGO_IN_BATCH`  | O mesmo `codigo` apareceu mais de uma vez no mesmo payload | Remover duplicata e reenviar                  |

---

**12. O que acontece se o mesmo `codigo` aparecer duas vezes no mesmo payload do batch?**

A primeira ocorrência é processada normalmente (CRIADO ou ATUALIZADO). A segunda ocorrência é imediatamente rejeitada com `errorCode: "DUPLICATE_CODIGO_IN_BATCH"`, sem nenhuma tentativa de processamento.

Exemplo de resposta:
```json
{
  "total": 2, "criados": 1, "atualizados": 0, "rejeitados": 1,
  "resultados": [
    { "codigo": "SKU-001", "status": "CRIADO",    "produtoId": 42 },
    { "codigo": "SKU-001", "status": "REJEITADO", "produtoId": null, "errorCode": "DUPLICATE_CODIGO_IN_BATCH", "message": "Código duplicado no mesmo lote." }
  ]
}
```

---

**13. O batch upsert altera o estoque do produto quando atualiza um produto existente?**

Não. O upsert via batch nunca toca em `estoque`, `estoque_reservado` ou `estado`. Somente os campos de catálogo são atualizados: `descricao`, `ncm`, `cfop`, `unidade`, `preco`, `origem` e `csosn`.

Para gerenciar estoque, use:
```
POST /api/app/produtos/{id}/estoque/entrada    ← requer role ADMIN
GET  /api/app/produtos/{id}/estoque            ← consulta saldo atual
```

---

**14. O que é o header `X-Request-Id` e como usá-lo para diagnóstico?**

Toda resposta da API inclui o header `X-Request-Id` com um UUID que identifica unicamente a requisição nos logs do servidor. Ele pode ser usado para correlacionar logs do OMS com logs do servidor ao investigar um problema.

**Dois modos de uso:**

1. **Gerado automaticamente:** Se a OMS não enviar o header, o servidor gera um UUID e o retorna na resposta. Guarde esse valor ao reportar um problema.

2. **Fornecido pela OMS:** A OMS pode enviar seu próprio ID de correlação:
   ```
   POST /api/app/produtos/batch
   X-Request-Id: oms-batch-20260601-001
   ```
   O mesmo valor será retornado no header de resposta e aparecerá em todos os logs do servidor para aquela requisição.

**Atenção:** O `X-Request-Id` está presente inclusive em respostas HTTP 401 — permitindo correlacionar tentativas de autenticação falhas com os logs do servidor.

Respostas de erro de negócio também incluem `requestId` no corpo JSON:
```json
{
  "code":      422,
  "errorCode": "BATCH_LIMIT_EXCEEDED",
  "requestId": "oms-batch-20260601-001"
}
```

---

**15. O batch retornou HTTP 422 com `errorCode: "BATCH_LIMIT_EXCEEDED"`. O que fazer?**

O limite máximo é de **200 produtos por requisição**. Quando excedido, o servidor retorna HTTP 422 antes de processar qualquer item.

Resposta de erro:
```json
{
  "code":      422,
  "message":   "O lote excede o limite máximo de 200 produtos por requisição.",
  "data":      null,
  "errorCode": "BATCH_LIMIT_EXCEEDED",
  "requestId": "<uuid>"
}
```

Para contornar: dividir o catálogo em lotes de até 200 itens e enviar cada lote em uma requisição separada. Não há exigência de delay entre requisições, mas é recomendável processar os lotes sequencialmente para simplificar o tratamento de erros.

---

**16. Posso autorizar múltiplos CNPJs sob o mesmo cliente OMS?**

Sim. O modelo V028 permite que um único `codigoEmpresaOms` possua múltiplos CNPJs autorizados sob o **mesmo token**. Para adicionar um segundo CNPJ:

1. Repetir o `POST /api/integration/fiscal-authorizations` com o certificado do novo CNPJ e o **mesmo** `codigoEmpresaOms`
2. O token retornado será **idêntico** ao já em uso — não é necessário substituir o header `Authorization`
3. A empresa emitente do novo CNPJ é criada automaticamente no Borurio a partir do Subject X.509

Não há limite de CNPJs por cliente OMS.

---

**17. O token muda quando autorizo um segundo CNPJ?**

Não. Ao adicionar um CNPJ novo a um cliente OMS já existente (cenário D), o Borurio retorna o **mesmo token** (string idêntica). O token identifica o cliente OMS (`codigoEmpresaOms`), não o CNPJ.

O token só muda nas seguintes situações:
- Primeira autorização do cliente OMS (cenário A) — token novo emitido
- Expiração natural (data do `tokenExpiraEm`) — necessário reautorizar com novo certificado

O token **não muda** ao adicionar CNPJs (cenário D) nem ao trocar o certificado de um CNPJ já autorizado (cenário C — JTI mantido, apenas `tokenExpiraEm` atualizado).

---

**18. Como indico qual CNPJ deve emitir a NF-e quando tenho múltiplos CNPJs autorizados?**

Inclua o campo `cnpjEmitente` (14 dígitos) no body do `POST /api/app/pedidos`. O Borurio usa esse valor para selecionar o certificado correto na emissão.

Exemplo:
```json
{
  "cnpjEmitente":    "54393421000159",
  "destCnpjCpf":     "12345678000195",
  "destRazaoSocial": "Cliente Exemplo",
  "itens": [...]
}
```

Se `cnpjEmitente` não for enviado (ou o token não for OMS), o sistema usa o CNPJ da empresa vinculada ao JWT.

---

**19. O que acontece se eu enviar um `cnpjEmitente` que ainda não foi autorizado?**

O pedido é **rejeitado na criação** (`POST /api/app/pedidos`), com `HTTP 403` e `"errorCode": "CNPJ_NOT_AUTHORIZED"`. O pedido não é persistido.

Esta validação ocorre antes de criar o pedido — é um fail-fast que evita criar pedidos que falharão na emissão. Para corrigir: executar o Bloco 0B com o certificado do CNPJ em questão antes de criar o pedido.

---

**20. A empresa emitente precisa ser cadastrada no Borurio antes da autorização?**

Não. A partir do V028, a empresa é **criada automaticamente** pelo Borurio na primeira vez que um CNPJ é autorizado. Os dados são extraídos do Subject X.509 do certificado:

- `razaoSocial` ← campo `CN=NOME DA EMPRESA:CNPJ` do Subject
- `uf` ← campo `ST=SP` (ou equivalente) do Subject; default `SP` se ausente
- `crt` ← `"1"` (default para Simples Nacional)

Se a empresa já existir no cadastro, ela é usada sem alterações. Se existir mas estiver inativa (`ativo=0`), a autorização é recusada com `COMPANY_INACTIVE`.

---

*Documento de suporte para integração OMS — Borurio ERP Fiscal BR*
