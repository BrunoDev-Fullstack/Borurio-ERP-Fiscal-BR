# Checklist de Onboarding — OMS Logística × Borurio ERP Fiscal BR

| Atributo               | Valor                                    |
|------------------------|------------------------------------------|
| Versão                 | 1.0                                      |
| Data                   | 2026-05-12                               |
| Ambiente de referência | HOM — `http://localhost:8081`            |
| Documento de suporte   | `docs/manual/INTEGRATION_CONTRACT_EN.md` |
| Status                 | Pronto para execução                     |

---

## Como usar este checklist

Execute os itens em ordem. Cada bloco depende do anterior. Não avance para o próximo bloco se houver item não concluído marcado como **[BLOQUEANTE]**.

---

## Bloco 0 — Pré-requisitos

- [ ] **[BLOQUEANTE]** Receber e-mail e senha de usuário com role `OPERADOR` criado pelo ADMIN
- [ ] **[BLOQUEANTE]** Confirmar base URL do ambiente HOM: `http://localhost:8081`
- [ ] **[BLOQUEANTE]** Confirmar que o acesso remoto ao ambiente HOM foi configurado pelo responsável pelo ambiente HOM — `http://localhost:8081` é válido apenas na máquina local onde o Docker está em execução; o time de integração deve solicitar VPN, túnel SSH controlado ou URL externa antes de iniciar qualquer teste
- [ ] Ter cliente HTTP configurado (Postman ou equivalente)
- [ ] Importar `docs/postman/borurio-erp-collection.json` (9 pastas, 46 requests)
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
| `cfop`      | string  | obrigatório, exatamente 4 dígitos |
| `unidade`   | string  | obrigatório, não vazio            |
| `preco`     | decimal | obrigatório, mínimo 0.01          |
| `origem`    | integer | obrigatório                       |

- [ ] Confirmar `data.id` retornado na resposta — guardar o `id` do produto
- [ ] Confirmar `data.estado` = `1` (produto ativo)
- [ ] Confirmar `data.csosn` preenchido (default `"400"` se não enviado)

**Campos fiscais opcionais — se não enviados, recebem defaults:**

| Campo     | Default   |
|-----------|-----------|
| `csosn`   | `"400"`   |
| `estoque` | nulo      |

**Atenção:** Produto com `estado=0` (inativo) é rejeitado na criação de pedido (`HTTP 400`).

---

## Bloco 4 — Criação de pedido

- [ ] **[BLOQUEANTE]** `POST /api/app/pedidos` com campos obrigatórios:

| Campo                   | Tipo    | Restrição                  |
|-------------------------|---------|----------------------------|
| `destCnpjCpf`           | string  | obrigatório, não vazio     |
| `destRazaoSocial`       | string  | obrigatório, não vazio     |
| `itens`                 | array   | obrigatório, mínimo 1 item |
| `itens[].produtoId`     | long    | obrigatório                |
| `itens[].quantidade`    | decimal | obrigatório, maior que 0   |
| `itens[].valorUnitario` | decimal | obrigatório, maior que 0   |

- [ ] Confirmar `data.status` = `"RASCUNHO"` na resposta
- [ ] Confirmar `data.id` retornado — guardar o `id` do pedido
- [ ] Confirmar que os itens têm snapshot fiscal preenchido: `ncm`, `cfop`, `unidade`, `csosn`, `origem`

**Nota:** `naturezaOperacao` é definido automaticamente como `"VENDA DE MERCADORIA"` se não enviado. `serieNfe` padrão é `"1"`.

---

## Bloco 5 — Emissão NF-e

- [ ] **[BLOQUEANTE]** `POST /api/app/pedidos/{id}/emitir` (sem body)
- [ ] Confirmar que `data.chaveNfe` tem exatamente **44 dígitos** — este é o indicador que o lote foi aceito pela SEFAZ
- [ ] Guardar `data.soapRetorno` para diagnóstico se necessário
- [ ] Confirmar que não ocorreu `HTTP 500` (status `ERRO`)

**Comportamento esperado em HOM/SP:**

| Situação                     | O que observar                                                                             |
|------------------------------|--------------------------------------------------------------------------------------------|
| Lote aceito pela SEFAZ       | `chaveNfe` com 44 dígitos                                                                  |
| `cStat=225` no `soapRetorno` | **Normal em HOM/SP** — limitação do processador `SP_NFE_PL_008i2`. Não é falha do sistema. |
| `cStat=100` no `soapRetorno` | AUTORIZADO — ocorre em PRD com certificado real                                            |
| `HTTP 422`                   | Pedido não está em `RASCUNHO`                                                              |
| `HTTP 500`                   | Exceção durante transmissão — pedido vai para `ERRO`                                       |

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

## Bloco 8 — Verificações de segurança

- [ ] Confirmar que request sem token retorna `HTTP 401` com `{"code": 401, "message": "Autenticação necessária", "success": false}`
- [ ] Confirmar que usuário `OPERADOR` acessando `/api/app/usuarios` retorna `HTTP 403`
- [ ] Confirmar que `empresa_id` NÃO é enviado no body de nenhuma requisição — é extraído automaticamente do JWT

---

## Bloco 9 — Bloqueadores para integração PRD

Estes itens não são responsabilidade do time chinês, mas bloqueiam o go-live em PRD:

| Bloqueador                                         | Responsável       | Status   |
|----------------------------------------------------|-------------------|----------|
| Certificados A1 de produção (`tpAmb=1`, CNPJ real) | Operações / Bruno | Pendente |
| `CERT_ENCRYPTION_KEY` configurada em PRD           | Operações / Bruno | Pendente |
| URL de PRD definida e acessível                    | Operações         | Pendente |

---

## Referências

| Documento                        | Caminho                                       |
|----------------------------------|-----------------------------------------------|
| Contrato de integração (EN)      | `docs/manual/INTEGRATION_CONTRACT_EN.md`      |
| Manual técnico motor fiscal (EN) | `docs/manual/MTF-001_motor-fiscal-nfe_EN.md`  |
| Postman collection               | `docs/postman/borurio-erp-collection.json`    |
| Swagger UI (DEV)                 | `http://localhost:8080/swagger-ui/index.html` |
