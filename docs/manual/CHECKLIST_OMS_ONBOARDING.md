# Checklist de Onboarding — OMS Logística × Borurio ERP Fiscal BR

| Atributo               | Valor                                    |
|------------------------|------------------------------------------|
| Versão                 | 1.4                                      |
| Data                   | 2026-06-01                               |
| Ambiente de referência | HOM — `https://hom-api.borurio.com`      |
| Documento de suporte   | `docs/manual/INTEGRATION_CONTRACT_EN.md` |
| Status                 | Pronto para execução                     |

---

## Como usar este checklist

Execute os itens em ordem. Cada bloco depende do anterior. Não avance para o próximo bloco se houver item não concluído marcado como **[BLOQUEANTE]**.

---

## Bloco Local — Execução local (usar enquanto HOM externo estiver pendente)

> Use este bloco se o Cloudflare Tunnel (`https://hom-api.borurio.com`) ainda não estiver ativo.
> Quando o HOM externo for liberado, pule este bloco e siga direto para o Bloco 0.

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

- [ ] A partir daqui, substituir `https://hom-api.borurio.com` por `http://localhost:8080` em todos os blocos seguintes
- [ ] **Atenção:** ambiente DEV usa `tpAmb=2` (homologação SEFAZ) — nunca emite NF-e real

---

## Bloco 0 — Pré-requisitos

- [ ] **[BLOQUEANTE]** Receber e-mail e senha de usuário com role `OPERADOR` criado pelo ADMIN
- [ ] **[BLOQUEANTE]** Confirmar base URL do ambiente HOM: `https://hom-api.borurio.com`
- [ ] **[BLOQUEANTE]** Confirmar que o Cloudflare Tunnel está ativo no servidor HOM — URL externa definida: `https://hom-api.borurio.com`. Verificar com `GET https://hom-api.borurio.com/api/test/ping` antes de iniciar os testes. Comandos de setup em `docs/manual/ROTEIRO_ENTREGA_TIME_CHINES.md` Bloco 1.
- [ ] Ter cliente HTTP configurado (Postman ou equivalente)
- [ ] Importar `docs/postman/borurio-erp-collection.json` (10 pastas, 49 requests)
- [ ] Ler `docs/manual/INTEGRATION_CONTRACT_EN.md` completo antes de executar qualquer chamada

---

## Bloco 1 — Autenticação

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

## Bloco 4 — Criação de pedido

- [ ] **[BLOQUEANTE]** `POST /api/app/pedidos` com campos obrigatórios:

| Campo                   | Tipo    | Restrição                                            |
|-------------------------|---------|------------------------------------------------------|
| `destCnpjCpf`           | string  | obrigatório, não vazio                               |
| `destRazaoSocial`       | string  | obrigatório, não vazio                               |
| `externalOrderId`       | string  | opcional — ID externo OMS para idempotência (máx 100 chars) |
| `itens`                 | array   | obrigatório, mínimo 1 item                           |
| `itens[].produtoId`     | long    | obrigatório                                          |
| `itens[].quantidade`    | decimal | obrigatório, maior que 0                             |
| `itens[].valorUnitario` | decimal | obrigatório, maior que 0                             |

- [ ] Confirmar `data.status` = `"RASCUNHO"` na resposta
- [ ] Confirmar `data.id` retornado — guardar o `id` do pedido
- [ ] Confirmar que os itens têm snapshot fiscal preenchido: `ncm`, `cfop`, `unidade`, `csosn`, `origem`

**Nota:** `naturezaOperacao` é definido automaticamente como `"VENDA DE MERCADORIA"` se não enviado. `serieNfe` padrão é `"1"`.

**Idempotência via `externalOrderId`:**

Quando `externalOrderId` é enviado, um segundo POST com o mesmo valor retorna o pedido já criado — sem criar duplicata.

- [ ] Criar pedido com `"externalOrderId": "OMS-TEST-IDEM-001"` → guardar `data.id` retornado (ex: `42`)
- [ ] Repetir exatamente o mesmo POST com `"externalOrderId": "OMS-TEST-IDEM-001"` → confirmar `data.id` igual a `42`
- [ ] Confirmar que nenhum segundo pedido foi criado no sistema para esse `externalOrderId`

**Nota:** Pedidos enviados sem `externalOrderId` nunca são deduplicados — cada POST cria um pedido distinto.

**Cenários negativos — `errorCode`:**

Erros de negócio retornam `HTTP 422` com campo `errorCode` identificável pela OMS. Não dependa do campo `message` para lógica de integração — ele pode mudar entre versões.

- [ ] Tentar criar pedido com `produtoId` inexistente → confirmar `HTTP 422` com `"errorCode": "PRODUCT_NOT_FOUND"`
- [ ] Tentar criar pedido com produto com `estado=0` (inativo) → confirmar `HTTP 422` com `"errorCode": "PRODUCT_INACTIVE"`
- [ ] Tentar criar pedido com `quantidade` maior que o estoque disponível → confirmar `HTTP 422` com `"errorCode": "INSUFFICIENT_STOCK"`

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

- [ ] **[BLOQUEANTE]** `POST /api/app/pedidos/{id}/emitir` (sem body)
- [ ] Confirmar que `data.chaveNfe` tem exatamente **44 dígitos** — este é o indicador que o lote foi aceito pela SEFAZ
- [ ] Guardar `data.soapRetorno` para diagnóstico se necessário
- [ ] Confirmar que não ocorreu `HTTP 500` (status `ERRO`)

**Comportamento de estoque durante a emissão:**

| Evento                        | Estoque                                                         |
|-------------------------------|-----------------------------------------------------------------|
| `POST /emitir` chamado        | Reserva atômica — `estoqueDisponivel -= qtd` para cada item     |
| `HTTP 422` retornado          | Estoque insuficiente — reserva não feita; pedido em `RASCUNHO`  |
| Status → `AUTORIZADO`         | Baixa definitiva — `estoqueTotal -= qtd`, reserva liberada      |
| Status → `REJEITADO` ou `ERRO`| Reserva desfeita — `estoqueDisponivel += qtd` para cada item    |
| Status → `AGUARDANDO`         | Reserva mantida — `estoqueDisponivel` permanece bloqueado       |
| Status → `CANCELADO`          | Estorno — `estoqueTotal += qtd`                                 |

**Comportamento esperado em HOM/SP:**

| Situação                     | O que observar                                                                             |
|------------------------------|--------------------------------------------------------------------------------------------|
| Lote aceito pela SEFAZ       | `chaveNfe` com 44 dígitos                                                                  |
| `cStat=225` no `soapRetorno` | **Normal em HOM/SP** — limitação do processador `SP_NFE_PL_008i2`. Não é falha do sistema. |
| `cStat=100` no `soapRetorno` | AUTORIZADO — será validado em PRD com infraestrutura pronta, usando o A1 real da Jcho Factory Ltda (`tpAmb=1`) |
| `HTTP 422` + `errorCode: "INVALID_ORDER_STATUS"` | Pedido não está em `RASCUNHO` — verificar `status` antes de tentar novamente |
| `HTTP 500`                                        | Exceção durante transmissão — pedido vai para `ERRO`                         |

- [ ] Tentar emitir pedido com `status != "RASCUNHO"` → confirmar `HTTP 422` com `"errorCode": "INVALID_ORDER_STATUS"`

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
| `REJEITADO`  | cStat ≥ 200 — SEFAZ recusou         | Nenhuma — fluxo encerrado |
| `ERRO`       | Exceção durante transmissão         | Investigar logs           |
| `CANCELADO`  | Cancelamento autorizado             | Nenhuma — imutável        |

---

## Bloco 7 — Operações pós-autorização (opcional em HOM)

Estes itens só são executáveis quando `status = "AUTORIZADO"`. Em HOM/SP o status será `AGUARDANDO` (cStat=225), então os testes abaixo são realizáveis somente se o ambiente retornar cStat=100.

- [ ] `POST /api/app/pedidos/{id}/cancelar` com `{"justificativa": "<texto mínimo 15 chars>"}`
- [ ] `POST /api/app/pedidos/{id}/cce` com `{"correcao": "<texto mínimo 15 chars>"}`

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

---

## Bloco 9 — Bloqueadores para integração PRD

Estes itens não são responsabilidade do time chinês, mas bloqueiam o go-live em PRD:

| Bloqueador                                         | Responsável       | Status   |
|----------------------------------------------------|-------------------|----------|
| Configurar A1 real da Jcho Factory Ltda (já entregue) com `tpAmb=1` em PRD | Operações / Bruno | Pendente |
| `CERT_ENCRYPTION_KEY` configurada em PRD           | Operações / Bruno | Pendente |
| URL de PRD definida e acessível                    | Operações         | Pendente |

---

## Referências

| Documento                        | Caminho                                       |
|----------------------------------|-----------------------------------------------|
| Contrato de integração (EN)      | `docs/manual/INTEGRATION_CONTRACT_EN.md`      |
| Manual técnico motor fiscal (EN) | `docs/manual/MTF-001_motor-fiscal-nfe_EN.md`  |
| Postman collection               | `docs/postman/borurio-erp-collection.json`    |
| Swagger UI (HOM)                 | `https://hom-api.borurio.com/swagger-ui/index.html` |
