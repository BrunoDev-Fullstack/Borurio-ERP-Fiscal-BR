# Relatório Técnico Diário — 01/06/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- HOM: UP — Flyway `v024` — MySQL UP — porta 8081
- Acesso externo: Cloudflare Quick Tunnel temporário (`trycloudflare.com`) — URL regenerada no início da sessão
- DEV: UP (porta 8080, não tocado nesta sessão)

---

## 1. Resumo executivo

Sessão com dois eixos principais:

1. **Integração CC/Xiao Li — Quatro dúvidas técnicas:** Respondidas com precisão antes de qualquer implementação. Temas: campos presentes no DANFE, idempotência no `POST /pedidos`, estabilidade da URL HOM, batch de emissões e distinção de `errorCode` por cenário de erro 422. Confirmação de segurança do ambiente local CC (`tpAmb`, `.env`, certificado gitignoreados).

2. **Fase 1 — `externalOrderId` + `errorCode`:** Implementação completa de idempotência por `externalOrderId` (MySQL UNIQUE KEY por empresa) e de `errorCode` padronizado para erros de negócio (`PRODUCT_NOT_FOUND`, `PRODUCT_INACTIVE`, `INSUFFICIENT_STOCK`, `INVALID_ORDER_STATUS`). Cobertura de testes: 87/87 passando (borurio-web: 20/20, borurio-fiscal: 33/33, outros módulos: 34/34). Documentação atualizada: contratos PT-BR e EN, checklist onboarding e FAQ smoke test. Relatório técnico criado.

Commit pendente: será realizado manualmente pelo responsável técnico ao finalizar a revisão.

---

## 2. Estado Git

```
Branch: fix/sefaz-xml-structure
Último commit: 9becd5c — fix(produto): torna cfop opcional com default 5102
Working tree: arquivos alterados não commitados (Fase 1 completa)
```

Arquivos alterados nesta sessão:

| Arquivo | Motivo |
|---|---|
| `borurio-web/src/main/resources/sql/migration/V025__pedido_add_external_order_id.sql` | Fase 1 — nova coluna e UNIQUE KEY `(empresa_id, external_order_id)` |
| `borurio-app/.../exception/BusinessException.java` | Fase 1 — nova exceção com `errorCode` e `httpStatus`; factory methods |
| `borurio-app/.../entity/Pedido.java` | Fase 1 — campo `externalOrderId` |
| `borurio-app/.../mapper/PedidoMapper.java` | Fase 1 — SELECT, INSERT e query por `externalOrderId` |
| `borurio-app/.../service/impl/PedidoServiceImpl.java` | Fase 1 — idempotência no `criar()` e `BusinessException` em `resolverProduto()` |
| `borurio-app/.../service/impl/EstoqueServiceImpl.java` | Fase 1 — `BusinessException` substituindo `IllegalStateException` em `reservarItens()` |
| `borurio-web/.../service/PedidoEmissaoService.java` | Fase 1 — `BusinessException.invalidOrderStatus()` no estado RASCUNHO |
| `borurio-web/.../service/PedidoOperacaoService.java` | Fase 1 — `BusinessException.invalidOrderStatus()` em `cancelar()` e `emitirCce()` |
| `borurio-web/.../exception/GlobalExceptionHandler.java` | Fase 1 — handler `BusinessException` retornando `errorCode` no envelope |
| `borurio-web/.../dto/PedidoResponse.java` | Fase 1 — campo `externalOrderId` |
| `borurio-web/.../controller/PedidoControllerTest.java` | Fase 1 — 5 novos testes (total: 20); todos passando |
| `docs/manual/INTEGRATION_CONTRACT_EN.md` | v1.3 → v1.4 — `externalOrderId`, idempotência, seção 8.2a `errorCode` |
| `docs/manual/INTEGRATION_CONTRACT_PT-BR.md` | v1.3 → v1.4 — mesmas mudanças em português |
| `docs/manual/CHECKLIST_OMS_ONBOARDING.md` | v1.3 → v1.4 — `externalOrderId` no Bloco 4, testes de idempotência e `errorCode` |
| `docs/manual/FAQ_SMOKE_TEST_OMS.md` | v1.0 → v1.1 — FAQ 8 (`externalOrderId`) e FAQ 9 (`errorCode`) |
| `docs/report/Relatorio_Tecnico_2026-06-01.md` | Relatório técnico desta sessão |

---

## 3. Integração CC/Xiao Li — Dúvidas do dia

### 3.1 Dúvida 1 — Campos presentes no DANFE

CC perguntou quais campos do payload do pedido aparecem no PDF do DANFE. Resposta: o DANFE é gerado a partir da NF-e autorizada pela SEFAZ, não diretamente do payload da API. O conteúdo relevante são: dados do emitente (configurados no sistema), dados do destinatário (`destCnpjCpf`, `destRazaoSocial`, `destUf`, campos de endereço), itens (snapshot fiscal com NCM, CFOP, CSOSN, unidade, descrição, quantidade, valor), totais fiscais calculados pelo motor (vBC, vICMS, vNF, etc.) e dados de transporte (`modFrete`, dados da transportadora se enviados).

### 3.2 Dúvida 2 — Idempotência no `POST /pedidos`

CC identificou ausência de proteção contra duplicação de pedidos em caso de retry por timeout de rede. Solução discutida: campo `externalOrderId` opcional no payload — a OMS envia seu ID interno; o Borurio retorna o pedido existente se o mesmo `(empresa_id, external_order_id)` já existir. Implementado na Fase 1 desta sessão.

### 3.3 Dúvida 3 — URL permanente do HOM e batch de emissões

CC perguntou sobre a URL permanente do HOM (atualmente Cloudflare Quick Tunnel temporário por sessão) e sobre suporte a batch de emissões. Respostas: (a) URL permanente é decisão de infraestrutura pendente de Bruno/Bless — não é responsabilidade do time de integração; (b) não existe endpoint de batch — o fluxo correto é `POST /emitir` por pedido, paralelizável no lado do OMS com controle de concorrência.

### 3.4 Dúvida 4 — `errorCode` distinto por cenário 422

CC solicitou distinção programática entre os diferentes cenários que retornam HTTP 422, para que o OMS possa tratar cada caso separadamente sem depender de parse de mensagem em português. Implementado na Fase 1 com o padrão `BusinessException` + campo `errorCode` no envelope de erro.

### 3.5 Confirmação de segurança do ambiente local CC

Verificado que `.env.dev`, `.env.hom` e `certificado-jcho.pfx` estão no `.gitignore` — CC não pode obter credenciais do repositório. Solicitado a CC que confirme: `NFE_TPAMB=2` no `.env` local (nunca emitir em modo `tpAmb=1` fora de PRD), uso do template `docker/env/.env.dev.template` para preencher variáveis.

---

## 4. Fase 1 — Implementação técnica

### 4.1 `externalOrderId` — Idempotência

**Mecanismo:** MySQL UNIQUE KEY `uq_pedido_external_order (empresa_id, external_order_id)`. MySQL 8.x trata múltiplos `NULL` como valores distintos em índice UNIQUE — pedidos sem `externalOrderId` nunca colidem entre si.

**Fluxo no `criar()`:**
1. Se `externalOrderId` não nulo/blank e `empresaId` não nulo → consulta `PedidoMapper.buscarPorExternalOrderIdEEmpresa()`.
2. Se encontrado: carrega itens e retorna pedido existente (sem inserção).
3. Se não encontrado: fluxo normal de criação.

**Migração:** `V025__pedido_add_external_order_id.sql` — `ADD COLUMN external_order_id VARCHAR(100) NULL` + `ADD UNIQUE KEY`. Sintaxe MySQL 8.x pura (sem `IF NOT EXISTS`).

### 4.2 `errorCode` — Distinção de erros de negócio

**Padrão:** `BusinessException extends RuntimeException` com campos `errorCode: String` e `httpStatus: int`. Factory methods estáticos para cada cenário.

**Handler:** `GlobalExceptionHandler.handleBusiness()` retorna `Map<String, Object>` (não `Result<T>`) para garantir que o campo `errorCode` apareça apenas nas respostas de erro — respostas de sucesso nunca recebem `errorCode`.

**Decisão de módulo:** `BusinessException` reside em `borurio-app.exception` — acessível por `borurio-app` (serviços) e `borurio-web` (handler e services da camada web) sem criar dependência circular.

| `errorCode`            | Lançado por                                     | Cenário                                |
|------------------------|-------------------------------------------------|----------------------------------------|
| `PRODUCT_NOT_FOUND`    | `PedidoServiceImpl.resolverProduto()`           | `produtoId` não existe no banco        |
| `PRODUCT_INACTIVE`     | `PedidoServiceImpl.resolverProduto()`           | Produto com `estado=0`                 |
| `INSUFFICIENT_STOCK`   | `EstoqueServiceImpl.reservarItens()`            | `qtd > estoqueDisponivel`              |
| `INVALID_ORDER_STATUS` | `PedidoEmissaoService`, `PedidoOperacaoService` | Operação não permitida no status atual |

### 4.3 Cobertura de testes

| Módulo | Testes | Status |
|---|---|---|
| `borurio-web` (`PedidoControllerTest`) | 20/20 (eram 15) | Todos passando |
| `borurio-fiscal` | 33/33 | Todos passando |
| Demais módulos | 34/34 | Todos passando |
| **Total** | **87/87** | **Todos passando** |

Novos testes adicionados:
- `criar_comExternalOrderId_retornaExternalOrderIdNaResposta`
- `criar_estoqueInsuficiente_returns422ComErrorCode`
- `criar_produtoInativo_returns422ComErrorCode`
- `criar_produtoNaoEncontrado_returns422ComErrorCode`
- `emitir_pedidoNaoRascunho_returns422ComErrorCode`

---

## 5. Documentação atualizada

| Documento | Versão antes | Versão após | Mudanças |
|---|---|---|---|
| `INTEGRATION_CONTRACT_EN.md` | 1.3 | 1.4 | `externalOrderId` na tabela de campos, notas de idempotência, seção 8.2a `errorCode` |
| `INTEGRATION_CONTRACT_PT-BR.md` | 1.3 | 1.4 | Idem, em português |
| `CHECKLIST_OMS_ONBOARDING.md` | 1.3 | 1.4 | `externalOrderId` no Bloco 4, testes de idempotência, cenários negativos `errorCode` |
| `FAQ_SMOKE_TEST_OMS.md` | 1.0 | 1.1 | FAQ 8 (`externalOrderId`) e FAQ 9 (`errorCode`) |

---

## 6. Segurança e cuidados respeitados

| Regra | Status |
|---|---|
| Token JWT nunca exibido | ✓ |
| Senha do certificado não exposta | ✓ |
| PRD não alterado | ✓ |
| Nenhuma chamada SEFAZ real executada | ✓ |
| Commit pendente — a ser realizado manualmente pelo responsável técnico | ✓ |
| Push não executado | ✓ |
| `.gitignore` verificado — credenciais CC fora do repositório | ✓ |

---

## 7. Ponto de retomada para a próxima sessão

| Campo | Valor |
|---|---|
| Branch | `fix/sefaz-xml-structure` |
| Último commit (antes desta sessão) | `9becd5c` — fix(produto): torna cfop opcional com default 5102 |
| Working tree | Fase 1 completa — commit pendente (manualmente pelo responsável técnico) |
| HOM | UP — JAR com v024 — `env=hom` confirmado |
| Cloudflare tunnel | URL temporária — nova a cada sessão |
| CC/Xiao Li | Dúvidas respondidas. Aguardando commit + comunicação do responsável técnico para iniciar testes de integração. |

### Próximas ações por prioridade

| Prioridade | Ação |
|---|---|
| P1 | Responsável técnico faz commit e push da Fase 1 e informa CC que `externalOrderId` e `errorCode` estão disponíveis |
| P1 | CC executa smoke test com `externalOrderId` e valida `errorCode` nos cenários negativos |
| P2 | Fase 2: análise de robustez da camada de exceções — `GlobalExceptionHandler`, padrões de `try/catch`, logging com MDC/`requestId` |
| Backlog | M2: `cfop` por item no pedido (apenas para fase 2 — interestadual) |
| Backlog | M4: invalidação de cache de certificado no `EmpresaController` |

---

*Relatório gerado em 01/06/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
