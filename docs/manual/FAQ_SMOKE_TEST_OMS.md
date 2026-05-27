# FAQ — Smoke Test OMS em HOM

| Atributo               | Valor                              |
|------------------------|------------------------------------|
| Versão                 | 1.0                                |
| Data                   | 2026-05-27                         |
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

*Documento de suporte para integração OMS — Borurio ERP Fiscal BR*
