# FAQ — Smoke Test OMS em HOM

| Atributo               | Valor                              |
|------------------------|-------------------------------------|
| Versão                 | 1.7                                 |
| Data                   | 2026-07-22                          |
| Ambiente de referência | HOM — acesso externo disponibilizado somente durante janela controlada de teste. Nenhuma URL fixa deve ser assumida pelo integrador. |
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

**4. O `POST /emitir` retornou `errorCode: "SEFAZ_REJECTED"` com `cStat=225`. Isso é um erro do OMS?**

Não. `cStat=225` indica falha de validação do XML perante o schema da NF-e. No incidente observado em 14-07-2026, a causa esteve relacionada à estrutura e aos algoritmos declarados na assinatura XMLDSig. Após o alinhamento do XML ao schema utilizado pela SEFAZ, o fluxo passou a alcançar `cStat=100` em HOM.

Se você ainda vir `cStat=225` (ou outro `cStat≥200`) depois dessa correção, trate como um erro real a investigar — verifique `data.xMotivo` para o motivo específico. Causas já conhecidas que podem gerar rejeição:

1. **Cadastro do emitente incompleto** — a empresa emitente estava sem endereço. Bloqueado preventivamente desde a v1.9 com `errorCode: EMITTER_ADDRESS_INCOMPLETE` (não chega a chamar a SEFAZ).
2. **Problema cadastral do emitente na Receita/SEFAZ** (ex.: Inscrição Estadual inapta ou cassada) — pendência externa à empresa, não corrigível pela integração.

Inscrição Estadual inapta, cassada ou outro problema cadastral deve ser diagnosticado pelo `cStat` e `xMotivo` efetivamente retornados pela SEFAZ na tentativa — cada rejeição tem código próprio. Não associe problemas cadastrais ao `cStat=225`.

Como distinguir: se o pedido nem chegou a chamar a SEFAZ, o erro será `errorCode: EMITTER_ADDRESS_INCOMPLETE` (não `SEFAZ_REJECTED`). Se veio `SEFAZ_REJECTED`, inspecione `data.xMotivo` — a mensagem da SEFAZ indica a causa.

O indicador correto de que o fluxo funcionou é `status: "AUTORIZADO"` com `data.cStat = "100"`.

---

**4a. O `POST /emitir` retornou `cStat=209`. O que significa?**

`cStat=209` significa **IE do emitente inválida** — a Inscrição Estadual cadastrada para a empresa emitente não está aceita pela SEFAZ no momento da transmissão. Não é falha de autenticação, XML, assinatura, transmissão nem da arquitetura multiempresa — é uma pendência cadastral fiscal da empresa específica usada naquela emissão.

Se você receber `cStat=209`: confirme qual `cnpjEmitente` foi usado no pedido (se o campo foi omitido, o sistema usa a empresa padrão associada ao token — ver `CHECKLIST_OMS_ONBOARDING.md`, Bloco 4). O mesmo token OMS pode estar autorizado para múltiplas empresas (modelo multi-CNPJ); se uma delas tiver pendência de IE, informe explicitamente o `cnpjEmitente` de outra empresa vinculada ao token com cadastro fiscal aceito pela SEFAZ para continuar testando o fluxo de emissão. A correção do cadastro da empresa com IE pendente é acompanhada separadamente, fora do escopo técnico da integração.

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

**6. O que acontece quando o controle de estoque está desativado para minha empresa?**

A configuração é feita por empresa, do lado do Borurio — o OMS não a envia por pedido. Com o controle desativado, `/emitir` nunca retorna `INSUFFICIENT_STOCK` e o saldo do produto não é reservado, baixado nem estornado em nenhuma etapa (emissão, rejeição ou cancelamento). As demais validações fiscais do pedido continuam normalmente.

---

**7. Preciso enviar o campo `serieNfe` na criação do pedido?**

Não. O campo `serieNfe` não deve ser enviado no body. O Borurio controla a série internamente. A resposta da criação do pedido mostrará `"serieNfe": "1"` como padrão — não é necessário enviar nem armazenar esse valor no OMS.

---

**8. Qual é a URL do ambiente HOM para os testes?**

O acesso externo ao HOM é disponibilizado somente durante janelas controladas de teste e a URL muda a cada janela. Não utilize uma URL salva de sessão anterior.

Antes de iniciar cada janela de testes, avise para que a URL ativa seja informada por canal seguro. Em seguida, confirme que o ambiente está operacional com:

```
GET {url-fornecida}/api/test/ping
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

**9. O que é `externalOrderId` e quando devo usá-lo?**

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

**10. O endpoint retornou HTTP 422 com campo `errorCode`. Como devo tratar isso?**

Erros de negócio retornam `HTTP 422` com um campo `errorCode` padronizado no corpo da resposta. A OMS deve usar `errorCode` para decidir a ação programática — não o campo `message`, que é legível por humanos e pode mudar entre versões.

> **v1.9:** todo erro agora também inclui o campo booleano `retryable`. `true` = pode reenviar sem alterar nada (falha transitória). `false` = precisa corrigir a causa primeiro. Ver Q26.

Códigos disponíveis:

| `errorCode`              | Situação                                              | HTTP | `retryable` | Ação sugerida para a OMS                          |
|--------------------------|---------------------------------------------------------|------|-------------|-----------------------------------------------------|
| `PRODUCT_NOT_FOUND`      | `produtoId` não existe no Borurio                     | 422  | false | Sincronizar catálogo de produtos                  |
| `PRODUCT_INACTIVE`       | Produto existe, mas está inativo (`estado=0`)         | 422  | false | Reativar produto ou remover do pedido             |
| `INSUFFICIENT_STOCK`     | Quantidade solicitada excede `estoqueDisponivel`      | 422  | false | Consultar `/api/app/produtos/{id}/estoque` e ajustar |
| `INVALID_ORDER_STATUS`   | Operação não permitida no status atual do pedido      | 422  | false | Verificar `status` via `/api/app/pedidos/{id}/situacao` |
| `EMISSAO_EM_ANDAMENTO`   | Outra requisição já assumiu a emissão do mesmo pedido | 409  | **true** | Não criar pedido novo; aguardar, consultar situação e repetir somente se aplicável (ver Q11) |
| `IND_FINAL_PADRAO_INVALIDO` | Configuração fiscal inválida (`indFinal`) na empresa emitente | 422 | false | Não corrigir no payload; acionar a operação responsável pelo Borurio |
| `DOCUMENTO_CNPJ_DIVERGENTE` | CNPJ do documento/chave incompatível com a empresa fiscal resolvida | 422 | false | Não repetir automaticamente; revisar pedido, chave, empresa e autorização fiscal (ver Q23) |
| `CNPJ_NOT_AUTHORIZED`    | `cnpjEmitente` não tem certificado ativo para este cliente OMS | 403 | false | Executar Bloco 0B para autorizar o CNPJ |
| `CERT_NOT_FOUND_FOR_CNPJ`| Certificado do `cnpjEmitente` não encontrado na emissão | 422 | false | Verificar se o CNPJ foi autorizado via Bloco 0B |
| `COMPANY_INACTIVE`       | Empresa existe mas está inativa (`ativo=0`)           | 422  | false | Contatar o administrador do Borurio |
| `INVALID_API_KEY`        | `X-Api-Key` ausente ou inválida                       | 401  | false | Verificar a chave recebida no Bloco 0 |
| `CERTIFICATE_EXPIRED`    | Certificado A1 vencido                                | 422  | false | Renovar o certificado A1 e reautorizar |
| `EMITTER_ADDRESS_INCOMPLETE` **(v1.9)** | Cadastro do emitente sem endereço — SEFAZ nem foi chamada | 422 | false | Enviar campos `emit*` em `POST /pedidos` (Q24) |
| `SEFAZ_REJECTED` **(v1.9)**             | SEFAZ processou e rejeitou a NF-e (cStat≥200) | 422 | false | Ver `data.cStat`/`data.xMotivo`; corrigir e reemitir no mesmo pedido (Q26) |
| `SEFAZ_TIMEOUT` **(v1.9)**              | Timeout na chamada à SEFAZ | 503 | **true** | Reenviar `/emitir` sem alterar nada |
| `SEFAZ_UNAVAILABLE` **(v1.9)**          | SEFAZ inacessível | 503 | **true** | Reenviar `/emitir` sem alterar nada |
| `XML_SCHEMA_INVALID` **(v1.9)**         | XML não passou na validação de schema local | 422 | false | Verificar dados do pedido/produto |

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

**11. O que fazer ao receber `errorCode: "EMISSAO_EM_ANDAMENTO"`?**

Significa concorrência na emissão do mesmo pedido: duas chamadas a `/emitir` colidiram, uma prossegue e a outra recebe `HTTP 409`. Não crie um pedido novo. Aguarde um intervalo curto, consulte `GET /api/app/pedidos/{id}/situacao` e repita `/emitir` somente se o estado ainda permitir. `retryable: true` — trate a resposta de forma idempotente.

---

**12. O OMS precisa enviar `indFinal` ou `indIntermed`?**

Não. `indFinal` (indicador de consumidor final) é resolvido internamente pelo cadastro da empresa emitente — não é inferido do CPF/CNPJ do destinatário, e não existe override por pedido nesta versão do contrato. `indIntermed` (indicador de intermediador/marketplace) é gerado internamente pelo Borurio; no escopo atual de venda direta, o valor usado é `"0"` — isso não representa regra universal para toda NF-e. Um cenário de marketplace/plataforma de terceiro é evolução futura e poderá exigir alteração contratual.

---

**13. O endpoint `/batch` retornou HTTP 207. É normal mesmo que todos os itens tenham sido criados com sucesso?**

Sim. O endpoint `POST /api/app/produtos/batch` retorna **sempre HTTP 207 Multi-Status**, independentemente do resultado. Isso inclui os casos em que todos os itens foram criados, todos atualizados, ou todos rejeitados. Não trate HTTP 207 como erro — verifique o resultado de cada item em `resultados[].status`.

O critério correto de PASS para o smoke test é:
- HTTP 207 recebido ✓
- `criados` e/ou `atualizados` > 0 ✓
- `resultados[n].produtoId` preenchido para itens CRIADO/ATUALIZADO ✓

---

**14. Um item do lote foi rejeitado com `status: "REJEITADO"`. Os outros itens ainda foram processados?**

Sim. O batch processa cada item de forma independente. Um item rejeitado não bloqueia os demais. O campo `rejeitados` na raiz da resposta indica quantos itens foram rejeitados, e cada entrada em `resultados[]` mostra o resultado individual.

Para identificar o motivo da rejeição, use `resultados[n].errorCode`:

| `errorCode`                  | Causa                                                      | Ação sugerida                                 |
|------------------------------|--------------------------------------------------------------|-------------------------------------------------|
| `VALIDATION_ERROR`           | NCM inválido, campo obrigatório vazio, preço ≤ 0           | Corrigir o item e reenviar somente ele        |
| `DUPLICATE_CODIGO_IN_BATCH`  | O mesmo `codigo` apareceu mais de uma vez no mesmo payload | Remover duplicata e reenviar                  |

---

**15. O que acontece se o mesmo `codigo` aparecer duas vezes no mesmo payload do batch?**

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

**16. O batch upsert altera o estoque do produto quando atualiza um produto existente?**

Não. O upsert via batch nunca toca em `estoque`, `estoque_reservado` ou `estado`. Somente os campos de catálogo são atualizados: `descricao`, `ncm`, `cfop`, `unidade`, `preco`, `origem` e `csosn`.

Para gerenciar estoque, use:
```
POST /api/app/produtos/{id}/estoque/entrada    ← requer role ADMIN
GET  /api/app/produtos/{id}/estoque            ← consulta saldo atual
```

---

**17. O que é o header `X-Request-Id` e como usá-lo para diagnóstico?**

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

**18. O batch retornou HTTP 422 com `errorCode: "BATCH_LIMIT_EXCEEDED"`. O que fazer?**

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

**19. Posso autorizar múltiplos CNPJs sob o mesmo cliente OMS?**

Sim. O modelo V028 permite que um único `codigoEmpresaOms` possua múltiplos CNPJs autorizados sob o **mesmo token**. Para adicionar um segundo CNPJ:

1. Repetir o `POST /api/integration/fiscal-authorizations` com o certificado do novo CNPJ e o **mesmo** `codigoEmpresaOms`
2. O token retornado será **idêntico** ao já em uso — não é necessário substituir o header `Authorization`
3. A empresa emitente do novo CNPJ é criada automaticamente no Borurio a partir do Subject X.509

Não há limite de CNPJs por cliente OMS.

---

**20. O token muda quando autorizo um segundo CNPJ?**

Não. Ao adicionar um CNPJ novo a um cliente OMS já existente (cenário D), o Borurio retorna o **mesmo token** (string idêntica). O token identifica o cliente OMS (`codigoEmpresaOms`), não o CNPJ.

O token só muda nas seguintes situações:
- Primeira autorização do cliente OMS (cenário A) — token novo emitido
- Expiração natural (data do `tokenExpiraEm`) — necessário reautorizar com novo certificado
- Rotação administrativa pelo Borurio (desde 22-07-2026) — em caso de suspeita de vazamento ou rotina de segurança, o ADMIN do Borurio pode rotacionar a autorização, invalidando o token anterior imediatamente e emitindo um novo. Isso é comunicado ao integrador por canal controlado, fora do fluxo normal da API — não é uma ação que o OMS aciona diretamente

O token **não muda** ao adicionar CNPJs (cenário D) nem ao trocar o certificado de um CNPJ já autorizado (cenário C — JTI mantido, apenas `tokenExpiraEm` atualizado).

**Revogação:** se o Borurio revogar uma autorização (suspeita de comprometimento, encerramento de integração), o token para de funcionar imediatamente em qualquer requisição — não é necessário aguardar a expiração natural (`tokenExpiraEm`). O erro retornado é `AUTHORIZATION_REVOKED`, HTTP 401.

---

**21. Como indico qual CNPJ deve emitir a NF-e quando tenho múltiplos CNPJs autorizados?**

Inclua o campo `cnpjEmitente` (14 dígitos) no body do `POST /api/app/pedidos`. O Borurio usa esse valor para selecionar o certificado correto na emissão.

Exemplo:
```json
{
  "cnpjEmitente":    "{{cnpjEmitente}}",
  "destCnpjCpf":     "12345678000195",
  "destRazaoSocial": "Cliente Exemplo",
  "itens": [...]
}
```

Se `cnpjEmitente` não for enviado (ou o token não for OMS), o sistema usa o CNPJ da empresa vinculada ao JWT.

---

**22. O que acontece se eu enviar um `cnpjEmitente` que ainda não foi autorizado?**

O pedido é **rejeitado na criação** (`POST /api/app/pedidos`), com `HTTP 403` e `"errorCode": "CNPJ_NOT_AUTHORIZED"`. O pedido não é persistido.

Esta validação ocorre antes de criar o pedido — é um fail-fast que evita criar pedidos que falharão na emissão. Para corrigir: executar o Bloco 0B com o certificado do CNPJ em questão antes de criar o pedido.

---

**23. A empresa emitente precisa ser cadastrada no Borurio antes da autorização?**

Não. A partir do V028, a empresa é **criada automaticamente** pelo Borurio na primeira vez que um CNPJ é autorizado. Os dados são extraídos do Subject X.509 do certificado:

- `razaoSocial` ← campo `CN=NOME DA EMPRESA:CNPJ` do Subject
- `uf` ← campo `ST=SP` (ou equivalente) do Subject; default `SP` se ausente
- `crt` ← `"1"` (default para Simples Nacional)

Se a empresa já existir no cadastro, ela é usada sem alterações. Se existir mas estiver inativa (`ativo=0`), a autorização é recusada com `COMPANY_INACTIVE`.

---

**24. Como funcionam cancelamento, CC-e e consulta de situação quando tenho múltiplos CNPJs autorizados?**

Empresa, certificado e UF são resolvidos pelo CNPJ real da operação — não por configuração global. O CNPJ embutido na chave de acesso do documento é validado contra esse contexto antes de prosseguir; não existe fallback silencioso para uma empresa global. Se houver divergência, a operação falha explicitamente com `errorCode: "DOCUMENTO_CNPJ_DIVERGENTE"` (ver Q10). Smoke test real multi-CNPJ desses três endpoints, após esse hardening, ainda deve ser executado em HOM.

---

**25. O certificado A1 usado em HOM será o mesmo usado em produção (PRD)?**

Não. Os certificados A1 disponibilizados e usados atualmente são exclusivos do ambiente de homologação e teste. O ambiente de produção utilizará um certificado A1 diferente, ainda não disponibilizado — será fornecido posteriormente pelo responsável da empresa emitente, por canal seguro, durante a preparação formal de produção. PRD permanece fora do escopo deste smoke test e depende de preparação operacional, de segurança e de validação fiscal próprias. Nunca reutilizar o certificado de HOM em PRD.

---

**26. Um pedido ficou `REJEITADO` ou `ERRO`. Preciso criar um pedido novo pra tentar de novo?**

**Não, desde a v1.9.** `POST /emitir` aceita pedidos em `RASCUNHO`, `REJEITADO` ou `ERRO` — chame o mesmo endpoint, no **mesmo `pedidoId`**, depois de corrigir a causa do erro. Cada nova tentativa gera um número de NF-e (`nNF`) e uma `chaveNfe` novos automaticamente — não há risco de duplicidade na SEFAZ.

Use o campo `retryable` da resposta de erro pra decidir o que fazer antes de tentar de novo — mas a estratégia depende do `errorCode`, não só do booleano:
- `retryable: true` — `SEFAZ_TIMEOUT`/`SEFAZ_UNAVAILABLE`: pode chamar `/emitir` de novo sem alterar dados, observando a política de retentativa da integração.
- `retryable: true` — `EMISSAO_EM_ANDAMENTO`: **não repetir imediatamente.** Aguardar um intervalo curto, consultar `GET /api/app/pedidos/{id}/situacao` e chamar `/emitir` de novo somente se o estado atual do pedido ainda permitir (ver Q11).
- `retryable: false` (`SEFAZ_REJECTED`, `EMITTER_ADDRESS_INCOMPLETE`, `XML_SCHEMA_INVALID`, `IND_FINAL_PADRAO_INVALIDO`, `DOCUMENTO_CNPJ_DIVERGENTE`, etc.) — corrija a causa (ver `data.cStat`/`data.xMotivo` ou o `errorCode`) antes de chamar `/emitir` de novo, senão vai repetir o mesmo erro.

Só `AUTORIZADO` e `CANCELADO` são terminais — `/emitir` nesses casos retorna `HTTP 422` com `errorCode: INVALID_ORDER_STATUS`.

---

**27. Como completo o endereço da empresa emitente se ela foi criada automaticamente sem esse dado?**

Empresas autorizadas via certificado A1 (Bloco 0B) são criadas só com CNPJ, razão social e UF — o certificado não carrega endereço. Se o cadastro estiver incompleto, `/emitir` retorna `EMITTER_ADDRESS_INCOMPLETE` sem sequer chamar a SEFAZ.

Para corrigir, envie os 6 campos abaixo em qualquer `POST /api/app/pedidos` daquele CNPJ:

```json
{
  "cnpjEmitente": "{{cnpjEmitente}}",
  "emitLogradouro": "Rua Exemplo",
  "emitNumero": "100",
  "emitBairro": "Centro",
  "emitCodigoMunicipio": "3550308",
  "emitMunicipio": "São Paulo",
  "emitCep": "01000000",
  "destCnpjCpf": "...",
  "destRazaoSocial": "...",
  "itens": [...]
}
```

O sistema completa **apenas os campos que estiverem faltando** no cadastro — nunca sobrescreve um endereço já preenchido. Não precisa reenviar esses campos em todo pedido depois que o cadastro estiver completo.

---

**28. O que significa o campo `retryable` nas respostas de erro?**

`retryable: true` indica que a causa pode permitir nova tentativa, mas a estratégia depende do `errorCode` — não é um sinal genérico de "repita imediatamente":

- `retryable: true` — falha transitória de rede/indisponibilidade (`SEFAZ_TIMEOUT`, `SEFAZ_UNAVAILABLE`). Reenviar sem alterar dados é seguro.
- `retryable: true` — `EMISSAO_EM_ANDAMENTO`: é obrigatório consultar o estado do pedido antes de repetir (ver Q11). Não é o mesmo caso das falhas de rede.
- `retryable: false` — precisa corrigir alguma coisa primeiro (dado incorreto, cadastro incompleto, configuração fiscal inválida, estado inválido). Reenviar sem corrigir só repete o mesmo erro.

Não infira isso a partir do texto de `message` (varia e é só pra humano ler) — use sempre os campos `retryable` e `errorCode` juntos.

---

**29. `POST /emitir` não retorna mais `HTTP 200` quando a SEFAZ rejeita? Isso muda meu código?**

Sim, desde a v1.9. Antes, uma rejeição da SEFAZ (`cStat≥200`) ainda vinha como `HTTP 200` com o `cStat` embutido dentro de `data.soapRetorno` — era preciso fazer parse do XML pra descobrir. Agora vem como `HTTP 422` com `errorCode: SEFAZ_REJECTED` e `data.cStat`/`data.xMotivo` já estruturados, sem precisar parsear XML.

Se o seu código hoje trata qualquer `HTTP 200` de `/emitir` como sucesso, é necessário ajustar pra também checar o `errorCode` nas respostas 4xx/5xx — ver Q10 e Q26.

---

**30. Preciso enviar `modFrete` (modalidade de frete) no pedido?**

Não. O OMS não envia esse campo — o Borurio define `modFrete` automaticamente no XML da NF-e conforme o fluxo de emissão usado. Para pedidos criados via integração OMS (`POST /api/app/pedidos`), o valor aplicado é `modFrete=2` (`CONTA_TERCEIROS` — frete por conta de terceiros, contratado pelo marketplace, conforme confirmado pelo time chinês). Não há parâmetro no payload pra alterar esse valor nesse fluxo.

---

*Documento de suporte para integração OMS — Borurio ERP Fiscal BR*
