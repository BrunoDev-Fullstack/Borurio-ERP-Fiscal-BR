# Relatório Técnico Diário — 01/06/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- HOM: UP — Flyway `v026` — MySQL UP — porta 8081
- Acesso externo: `https://hom-api.borurio.com` (Cloudflare Tunnel)
- DEV: UP (porta 8080, não tocado nesta sessão)

---

## 1. Resumo executivo

Sessão com três entregas principais, todas validadas em HOM:

1. **Fase 2 — Observabilidade e resiliência:** Implementação de `RequestIdFilter` com rastreamento por `X-Request-Id` em todas as respostas (incluindo HTTP 401). Melhoria de logging MDC com `requestId` nos padrões `logback-dev`, `logback-hom` e `logback-prd`. Testes: 87/87. Commit: `e9dac4e`.

2. **P0 + Batch de Produtos — Endpoint de upsert em lote:** Correção da chave única de produto (V026 migration: `UNIQUE KEY (empresa_id, codigo)` substituindo `UNIQUE KEY (codigo)` global). Implementação do endpoint `POST /api/app/produtos/batch` com resposta HTTP 207 Multi-Status, suporte a CRIADO/ATUALIZADO/REJEITADO por item e 9 novos testes unitários. Testes: 96/96. Commit: `0abd08b`.

3. **Deploy e smoke test em HOM:** V026 aplicada pelo Flyway com sucesso. Smoke test de 10 itens concluído — todos PASS.

4. **Documentação:** Contratos EN e PT-BR atualizados para v1.4, checklist para v1.5, FAQ para v1.2 e relatório técnico consolidado.

---

## 2. Estado Git

```
Branch: fix/sefaz-xml-structure
Commits locais (não publicados): bfaca56, e9dac4e, 0abd08b
Último commit commitado por Bruno: 0abd08b — feat(produto): adiciona batch upsert para integracao oms
Working tree: documentação atualizada — commit pendente (a ser realizado manualmente pelo responsável técnico)
```

### Commits desta sessão

| Commit    | Descrição                                                   | Autor  |
|-----------|-------------------------------------------------------------|--------|
| `e9dac4e` | feat(observability): adiciona requestId e melhora resiliencia de pedidos | Bruno |
| `0abd08b` | feat(produto): adiciona batch upsert para integracao oms    | Bruno  |

### Arquivos alterados na sessão (por commit)

**Commit `e9dac4e` — Fase 2 (Observabilidade):**

| Arquivo | Motivo |
|---|---|
| `borurio-web/src/main/java/.../filter/RequestIdFilter.java` | Novo — `OncePerRequestFilter` com `@Order(HIGHEST_PRECEDENCE)`; gera ou reutiliza `X-Request-Id`; popula MDC com `requestId` |
| `borurio-web/src/main/java/.../exception/GlobalExceptionHandler.java` | Melhorado — handler `IllegalArgumentException` (HTTP 400); `requestId` no envelope de erro lido do MDC |
| `borurio-web/src/main/java/.../service/PedidoOperacaoService.java` | Melhorado — `BusinessException.invalidOrderStatus()` com `errorCode` em `cancelar()` e `emitirCce()` |
| `borurio-app/src/main/java/.../service/impl/PedidoServiceImpl.java` | Melhorado — resiliência na camada de serviço |
| `borurio-web/src/main/resources/logback-dev.xml` | Atualizado — padrão MDC: `[%X{requestId:-no-rid}]` |
| `borurio-web/src/main/resources/logback-hom.xml` | Atualizado — padrão MDC: `[%X{requestId:-no-rid}]` |
| `borurio-web/src/main/resources/logback-prd.xml` | Atualizado — padrão MDC: `[%X{requestId:-no-rid}]` |

**Commit `0abd08b` — P0 + Batch de Produtos:**

| Arquivo | Motivo |
|---|---|
| `borurio-web/src/main/resources/sql/migration/V026__produto_unique_key_empresa_codigo.sql` | Migração P0 — `DROP INDEX uq_produto_codigo` + `ADD UNIQUE KEY uq_produto_codigo_empresa (empresa_id, codigo)` |
| `borurio-app/src/main/java/.../entity/ProdutoBatchItemResultado.java` | Nova entidade — enum Status (CRIADO/ATUALIZADO/REJEITADO) + factory methods |
| `borurio-web/src/main/java/.../dto/ProdutoBatchRequest.java` | Novo DTO — sem `@Valid` nos itens (validação por item no service) |
| `borurio-web/src/main/java/.../dto/ProdutoBatchResponse.java` | Novo DTO — `total`, `criados`, `atualizados`, `rejeitados`, `resultados[]`; factory `from()` |
| `borurio-app/src/main/java/.../exception/BusinessException.java` | Modificado — adicionado factory method `batchLimitExceeded()` (HTTP 422) |
| `borurio-app/src/main/java/.../mapper/ProdutoMapper.java` | Modificado — adicionado `atualizarBatch()` (não atualiza: `codigo`, `estoque`, `estoque_reservado`, `estado`) |
| `borurio-app/src/main/java/.../service/ProdutoService.java` | Modificado — adicionado `batchUpsert()` na interface |
| `borurio-app/src/main/java/.../service/impl/ProdutoServiceImpl.java` | Modificado — implementação `batchUpsert()` com `LinkedHashSet` para detecção de duplicatas |
| `borurio-web/src/main/java/.../controller/app/ProdutoController.java` | Modificado — `POST /api/app/produtos/batch` → HTTP 207 Multi-Status |
| `borurio-web/src/test/java/.../controller/ProdutoBatchControllerTest.java` | Novo — 9 testes unitários; todos passando |

---

## 3. Análise da mensagem do CC/Xiao Li

CC confirmou que a semântica de upsert era a esperada (produto novo → inserir; existente → atualizar) e encerrou a sessão. Nenhuma pendência aberta pelo time chinês. Próxima comunicação: informar que `POST /api/app/produtos/batch` está disponível em HOM após documentação finalizada.

---

## 4. Fase 2 — Observabilidade e resiliência

### 4.1 RequestIdFilter

**Mecanismo:** `OncePerRequestFilter` com `@Order(Ordered.HIGHEST_PRECEDENCE)` — executa antes do filtro Spring Security (order -100). Lê `X-Request-Id` do header da requisição; se ausente ou em branco, gera `UUID.randomUUID()`. Popula `MDC.put("requestId", value)` e escreve `response.setHeader("X-Request-Id", value)`. Remove do MDC no bloco `finally` (garantia mesmo em exceção).

**Resultado:** `X-Request-Id` presente em **todas** as respostas, incluindo HTTP 401 — permitindo à OMS correlacionar tentativas de autenticação com logs do servidor.

**Confirmado em HOM:** Header presente na resposta HTTP 401 de endpoint sem token.

### 4.2 Logging MDC

Padrão de log atualizado nos três perfis:
```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{requestId:-no-rid}] %-5level %logger{36} - %msg%n
```

O marcador `no-rid` aparece apenas em logs gerados fora do ciclo HTTP (ex: startup). Logs de requisições sempre terão o UUID.

### 4.3 GlobalExceptionHandler

- `IllegalArgumentException` → HTTP 400 (handler `handleBadRequest`) — novo
- `BusinessException` → httpStatus da exceção + `errorCode` no envelope
- `MethodArgumentNotValidException` → HTTP 422 com mapa de erros em `data`
- Todos os handlers lêem `MDC.get("requestId")` e incluem `requestId` no corpo do erro de negócio

---

## 5. P0 — Correção da chave única de produto

### 5.1 Problema

`V009` criou `UNIQUE KEY uq_produto_codigo (codigo)` globalmente — sem partição por empresa. `V017` adicionou `empresa_id` à tabela mas não corrigiu a chave. O resultado: duas empresas distintas não poderiam ter o mesmo código de produto, violando o modelo multiempresa.

### 5.2 Solução

`V026__produto_unique_key_empresa_codigo.sql`:
```sql
ALTER TABLE produto DROP INDEX uq_produto_codigo;
ALTER TABLE produto ADD UNIQUE KEY uq_produto_codigo_empresa (empresa_id, codigo);
```

Verificado antes da aplicação: 4 produtos em HOM, todos `empresa_id=1`, sem duplicatas de `codigo` — sem risco de conflito.

### 5.3 Aplicação em HOM

Flyway aplicou V026 com sucesso: `Successfully applied 1 migration to schema 'borurio_fiscal_hom', now at version v026`.

---

## 6. Batch de Produtos — Implementação técnica

### 6.1 Endpoint

```
POST /api/app/produtos/batch
Authorization: Bearer {token}
Content-Type: application/json
```

Sempre retorna HTTP 207 Multi-Status. Nunca HTTP 200 por item individual.

### 6.2 Validação sem `@Valid`

A escolha deliberada de não usar `@Valid` nos itens de `ProdutoBatchRequest` garante sucesso parcial: um item com `ncm` inválido é rejeitado sem bloquear os demais. A validação acontece por item no `ProdutoServiceImpl.batchUpsert()`, com `try/catch` que mapeia `IllegalArgumentException` → `VALIDATION_ERROR` e `Exception` → `INTERNAL_ERROR`.

### 6.3 Detecção de duplicatas no payload

`LinkedHashSet<String> processados` rastreia códigos já vistos. Primeira ocorrência: `processados.add(codigo)` retorna `true` → processamento normal. Segunda+ ocorrência: retorna `false` → `REJEITADO / DUPLICATE_CODIGO_IN_BATCH` imediatamente, sem consulta ao banco.

### 6.4 Campos preservados no upsert

`atualizarBatch()` só toca campos de catálogo: `descricao`, `ncm`, `cfop`, `unidade`, `preco`, `origem`, `csosn`. Preserva: `codigo`, `estoque`, `estoque_reservado`, `estado`.

### 6.5 Defaults aplicados (service layer)

| Campo   | Default  | Condição                        |
|---------|----------|---------------------------------|
| `cfop`  | `"5102"` | `null` ou em branco             |
| `csosn` | `"400"`  | `null` ou em branco             |
| `origem`| `0`      | `null`                          |
| `estoque`| `0`     | `null` (apenas no INSERT)       |

### 6.6 ErrorCodes adicionados

| `errorCode`                  | HTTP   | Trigger                                                             |
|------------------------------|--------|---------------------------------------------------------------------|
| `VALIDATION_ERROR`           | 207    | Item com dado inválido no batch                                     |
| `BATCH_LIMIT_EXCEEDED`       | 422    | Mais de 200 itens na requisição                                     |
| `DUPLICATE_CODIGO_IN_BATCH`  | 207    | Mesmo `codigo` aparece mais de uma vez no payload                   |

### 6.7 Cobertura de testes

| Módulo | Testes | Status |
|---|---|---|
| `borurio-web` (`ProdutoBatchControllerTest`) | 9 (novos) | Todos passando |
| `borurio-web` (total incluindo anteriores) | 29/29 | Todos passando |
| `borurio-fiscal` | 33/33 | Todos passando |
| Demais módulos | 34/34 | Todos passando |
| **Total** | **96/96** | **Todos passando** |

Testes adicionados:
- `batchUpsert_listaVazia_returns400`
- `batchUpsert_limiteExcedido_returns422`
- `batchUpsert_produtoNovo_returns207Criado`
- `batchUpsert_produtoExistente_returns207Atualizado`
- `batchUpsert_produtoInvalido_returns207Rejeitado`
- `batchUpsert_loteMisto_returns207ComContagens`
- `batchUpsert_codigoDuplicadoNoBatch_returns207ComRejeicao`
- `batchUpsert_semToken_returns401`
- `batchUpsert_update_preservaEstoqueEEstado`

---

## 7. Deploy HOM

### 7.1 Sequência executada

1. `mvn clean package -DskipTests` (build do JAR)
2. `docker compose ... up -d --no-deps --build borurio-web-hom` (apenas o container web, sem recriar dependências)
3. Healthcheck até `healthy` via `docker inspect`
4. Verificação Flyway: `v026` aplicada com sucesso

### 7.2 Resultado

```
Flyway: Successfully applied 1 migration to schema 'borurio_fiscal_hom', now at version v026
Container: borurio-web-hom → status healthy
Environment: hom — porta 8081 — Cloudflare Tunnel ativo
```

---

## 8. Smoke Test HOM — 01/06/2026

### 8.1 Sequência de validação executada

| # | Requisição | Resultado | Status |
|---|---|---|---|
| 1 | `GET /api/test/ping` | HTTP 200 · `status="UP"` · `environment="hom"` | PASS |
| 2 | `POST /auth/login` | HTTP 200 · `token` presente | PASS |
| 3 | `POST /api/app/produtos` (produto individual) | HTTP 200 · `data.id` retornado | PASS |
| 4 | `GET /api/app/produtos?page=0&size=5` | HTTP 200 · `totalElements ≥ 1` | PASS |
| 5 | `POST /api/app/pedidos` | HTTP 200 · `data.status="RASCUNHO"` | PASS |
| 6 | `POST /api/app/pedidos/{id}/emitir` | HTTP 200 · `data.soapRetorno` não vazio · `chaveNfe` 44 dígitos | PASS |
| 7 | `GET /api/app/pedidos/{id}/situacao` | HTTP 200 · `data.chaveNfe` preenchida | PASS |
| 8 | `GET /api/app/pedidos/{id}` | HTTP 200 · `data.itens` com snapshot fiscal | PASS |
| 9 | `POST /api/app/produtos/batch` (produto novo) | HTTP 207 · `criados=1` · `status="CRIADO"` | PASS |
| 10 | `POST /api/app/produtos/batch` (mesmo produto) | HTTP 207 · `atualizados=1` · `status="ATUALIZADO"` | PASS |

### 8.2 Validações de segurança

| Verificação | Resultado | Status |
|---|---|---|
| Request sem token → HTTP 401 com `X-Request-Id` no header | Confirmado | PASS |
| `X-Request-Id` presente em todas as respostas | Confirmado | PASS |
| Produto existente: batch não altera `estoque` nem `estado` | Confirmado | PASS |
| Lista vazia → HTTP 400 | Confirmado | PASS |

### 8.3 Comportamento HOM-SP

`cStat=225` no `soapRetorno` do `/emitir` — comportamento normal do schema `SP_NFE_PL_008i2`. Pedido em `AGUARDANDO`. Não ocorrerá em PRD.

---

## 9. Documentação atualizada

| Documento | Versão antes | Versão após | Mudanças |
|---|---|---|---|
| `INTEGRATION_CONTRACT_EN.md` | 1.3 | 1.4 | Seção 6.2d (batch), 3 novos `errorCode` em 8.2a, seção 8.5 (X-Request-Id), itens 9–11 no smoke test, observações 9–10 |
| `INTEGRATION_CONTRACT_PT-BR.md` | 1.3 | 1.4 | Idem em português |
| `CHECKLIST_OMS_ONBOARDING.md` | 1.4 | 1.5 | Bloco 3B (batch upsert), checks X-Request-Id no Bloco 8 |
| `FAQ_SMOKE_TEST_OMS.md` | 1.1 | 1.2 | FAQs 10–15 (batch HTTP 207, rejeição parcial, duplicatas, estoque, X-Request-Id, limite 200) |
| `Relatorio_Tecnico_2026-06-01.md` | Apenas Fase 1 | Completo | Incorpora Fase 2, P0, batch, deploy HOM, smoke test |

---

## 10. Segurança e cuidados respeitados

| Regra | Status |
|---|---|
| Token JWT nunca exibido | ✓ |
| Senha do certificado não exposta | ✓ |
| PRD não alterado | ✓ |
| Nenhuma chamada SEFAZ real executada | ✓ |
| Commits realizados manualmente pelo responsável técnico | ✓ |
| Push não executado | ✓ |
| Documentação atualizada — commit pendente | ✓ |

---

## 11. Ponto de retomada para a próxima sessão

| Campo | Valor |
|---|---|
| Branch | `fix/sefaz-xml-structure` |
| Último commit de código | `0abd08b` — feat(produto): adiciona batch upsert para integracao oms |
| Working tree | Documentação atualizada — commit pendente (manualmente pelo responsável técnico) |
| HOM | UP — Flyway v026 — `env=hom` confirmado |
| Cloudflare Tunnel | `https://hom-api.borurio.com` ativo |
| CC/Xiao Li | Aguardando comunicação: batch disponível em HOM, URL e credenciais |

### Próximas ações por prioridade

| Prioridade | Ação | Responsável |
|---|---|---|
| P1 | Commit da documentação e informar CC que `POST /batch` está disponível em HOM | Bruno |
| P1 | CC executa smoke test do batch e valida CRIADO/ATUALIZADO/REJEITADO | CC/Xiao Li |
| P2 | Avaliar CORS: adicionar `X-Request-Id` a `allowedHeaders` se OMS tiver componente browser | Bruno |
| Backlog | M2: `cfop` por item no pedido (para operações interestaduais) | Bruno |
| Backlog | M5: Rate limiting em `POST /api/app/pedidos/{id}/emitir` | Bruno |
| Backlog | M6: CI/CD GitHub Actions | Bruno |
| Backlog | Remover `NfeAuthorizeService` (mock legado, sem uso) | Bruno |

---

*Relatório gerado em 01/06/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
