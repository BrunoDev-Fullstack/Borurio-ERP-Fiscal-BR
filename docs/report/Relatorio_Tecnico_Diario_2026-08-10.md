# RELATÓRIO TÉCNICO DIÁRIO — 10/08/2026

**Projeto:** Borurio ERP Fiscal BR

**Branch:** `fix/sefaz-xml-structure`

**Responsável técnico:** Bruno Ribeiro

---

## 1. Objetivo do trabalho de hoje

Fechar o Gate 1.2 (hardening de segurança e confiabilidade de testes) aberto em 07/08/2026, e só então declarar `GATE 1 = APROVADO DEFINITIVAMENTE`. Duas fases pendentes da retomada: Fase A (`EstoqueServiceTest`) e Fase B (tenant-null fail-closed). Depois de banca verde, uma revisão técnica linha por linha do arquivo mais crítico do pacote (`PedidoEmissaoService`) encontrou um P1 real que a banca automatizada não capturou, corrigido antes de qualquer commit. Ao final, auditoria e atualização controlada da documentação oficial, para que código e contrato não fiquem dessincronizados no momento do commit manual.

**Importante:** o contrato do OMS não foi alterado hoje, exceto pela adição de um novo `errorCode` (`LOCAL_PROCESSING_FAILURE`) ao vocabulário já existente de erros estruturados — nenhum endpoint, nenhum campo de payload, nenhuma URL foi modificada.

---

## 2. Fase A — `EstoqueServiceTest`

**Auditoria read-only primeiro.** Histórico via `git log` confirmou drift de contrato genuíno, não regressão: `EstoqueServiceImpl` migrou de `IllegalStateException` para `BusinessException` no commit `bfaca56` (01/06/2026), como parte de um esforço maior de padronizar `errorCode` em toda a API. O teste (`e850dde`, 18/05/2026, anterior à migração) nunca foi atualizado. Ficou invisível por `skipTests` hardcoded (corrigido em 07/08/2026) desde antes até da própria migração.

**Contrato atual confirmado como correto:** `INSUFFICIENT_STOCK`/`PRODUCT_NOT_FOUND` já documentados no contrato oficial de integração (`INTEGRATION_CONTRACT_PT-BR.md`, `MTF-001`) antes mesmo desta sessão — o CC/OMS já conhece esses `errorCode`. Os dois testes foram corrigidos para o contrato real; `EstoqueServiceImpl` permaneceu intocado.

**Resultado:** `borurio-app` 20/20, `EstoqueServiceTest` 11/11, `PedidoServiceImplTest` 8/8.

---

## 3. Fase B — tenant-null fail-closed (P0-2)

### 3.1 Auditoria antes de editar

Cadeia completa lida antes de qualquer edição: `PedidoService`, `PedidoServiceImpl`, `PedidoMapper`, `EmpresaContextHolder`, `JwtFilter`, `JwtService`/`JwtUtil`, fluxo de login, `DbUser`, `SecurityConfig`, roles reais, endpoints `/api/app/pedidos/**`, endpoints administrativos, autenticação OMS, testes existentes de isolamento tenant.

**Achados-chave, todos comprovados por código (não hipotéticos):**
- `ROLE_ADMIN` é determinada por `UserDetailsServiceImpl.loadUserByUsername` a cada requisição, consultando `db_user.role` fresco — nunca cacheada no JWT.
- Usuário `admin` seed tem `empresa_id = NULL` legitimamente (criado em `V008`, antes de `empresa_id` existir; `V019` promoveu seu `role` para `ADMIN`).
- `AuthService.authenticate` embute `DbUser.empresaId` no claim `eid` — se `null`, o claim simplesmente não entra no token.
- `SecurityConfig` só exige `ROLE_ADMIN` para uma lista curta de rotas; `/api/app/pedidos/**`, `/api/app/clientes/**`, a maior parte de `/api/app/produtos/**` e `/api/fiscal/nfe/logs` caem em `.anyRequest().authenticated()` — qualquer role passa.
- `PedidoController`, `ClienteController`, `ProdutoController`, `NfeLogController` repetiam o mesmo padrão: `empresaId != null ? scoped : global`, sem checar `ROLE_ADMIN` no branch `null`.
- Token OMS já exige `eid` obrigatório desde sua concepção (`JwtFilter.autenticarOms` rejeita sem ele) — confirmado inalterável e não tocado.
- `EmpresaContextHolder` sempre limpo em `finally`, mesmo com exceção — sem vazamento de ThreadLocal entre requisições. Nenhum uso assíncrono do contexto encontrado.

### 3.2 Decisão de desenho

Ponto central único no `JwtFilter.autenticarUsuario()`, em vez de checagem espalhada por controller — mesmo padrão já usado no bloco OMS do mesmo arquivo. `ROLE_ADMIN` determinada exclusivamente pelas authorities reais carregadas por `UserDetailsService`, nunca por `empresaId == null`, e-mail, claim do cliente ou header.

```
tenant presente                → autentica normalmente; EmpresaContextHolder recebe o eid.
tenant ausente + ROLE_ADMIN     → autentica normalmente; EmpresaContextHolder permanece null.
tenant ausente + NÃO ADMIN      → HTTP 403, errorCode: TENANT_REQUIRED, retryable=false;
                                   FilterChain não continua — controller/service/mapper nunca alcançados.
```

### 3.3 Implementação

Único arquivo de produção modificado: `JwtFilter.java`. `autenticarUsuario` passou a retornar `boolean` (mesmo padrão de `autenticarOms`); `doFilterInternal` interrompe a cadeia se `false`.

### 3.4 Prova adversarial — nível HTTP e nível MySQL real

Além dos testes unitários do filtro (`JwtFilterTest`, cenários A–H), duas provas de nível mais alto:

1. **HTTP real, sem MySQL** (`PedidoControllerTest`, `@WebMvcTest` + `@Import(SecurityConfig.class)`, `JwtFilter` não mockado): OPERADOR sem tenant → 403 antes do controller, `verifyNoInteractions(pedidoService)`; ADMIN sem tenant → 200, acesso global preservado.
2. **MySQL real, container efêmero** (`P02TenantNullFailClosedRealMySqlIT`): login real via `POST /auth/login` (senha real, BCrypt), 4 cenários — OPERADOR sem tenant bloqueado com contagens antes/depois idênticas em `pedido`/`produto.estoque`/`nfe_emissao`/`nfe_sequencia`/`nfe_log`; ADMIN sem tenant preservado; OPERADOR com tenant escopado.

**Descoberta e correção de curso durante a preparação da prova MySQL real:** o container inicialmente indicado como "descartável" na porta 3308 foi identificado, via `docker ps`, como `borurio-mysql-hom` — o ambiente real de homologação usado nos testes com o CC, não uma instância de teste. A sessão foi interrompida antes de qualquer operação destrutiva. Um container `mysql:8.4` efêmero e isolado (`docker run`, porta livre `3399`, schema próprio) foi criado exclusivamente para a prova, com validação read-only (`SELECT VERSION()`, `SHOW DATABASES`, confirmação de identidade via `docker inspect`) antes de qualquer seed/DELETE, e destruído ao final. `borurio-mysql-hom`/`borurio-mysql-dev` nunca foram tocados.

**Resultado:** `JwtFilterTest` 14/14; `PedidoControllerTest` 34/34; `P02TenantNullFailClosedRealMySqlIT` 4/4 MySQL real; `borurio-web` completo 306/306.

**Ponto adjacente identificado, não corrigido nesta etapa:** `UsuarioController`/`UsuarioService` permitem que um ADMIN crie um `OPERADOR` com `empresa_id = NULL` (o endpoint já é `ROLE_ADMIN`-only — não é a vulnerabilidade em si, mas a origem de contas nesse estado). Registrado como hardening operacional posterior (P1). Verificação obrigatória antes de qualquer deploy: `SELECT id, email, role FROM db_user WHERE empresa_id IS NULL` — não executada contra HOM/PRD nesta sessão.

---

## 4. Banca final — P0-1/P0-2/P0-3

Revalidação completa após Fase A + Fase B: `borurio-app` 20/20, `borurio-fiscal` 73/73 (1 skip intencional), `borurio-web` 306/306, P0-1 3/3 MySQL real, P0-2 4/4 MySQL real, P0-3 9/9 MySQL real, `git diff --check` limpo. **Classificação inicial: `GATE 1 = APROVADO DEFINITIVAMENTE`** — revista na seção 5.

---

## 5. Revisão final de `PedidoEmissaoService` — achado do P1 pré-transmissão

A aprovação da seção 4 foi **retirada temporariamente** para `APROVADO COM PENDÊNCIAS` a partir de uma revisão técnica linha por linha do diff completo de `PedidoEmissaoService.java` — não da banca automatizada, que já estava inteiramente verde. A revisão comparou HEAD (`badf795`) contra o working tree, método por método, incluindo os serviços novos chamados pelo fluxo (`NfeEmissaoService`, `NfeGeracaoService`, `NfeOrquestradorService`) e os testes que comprovam cada mudança.

### 5.1 Achado

`falhaOcorreuAntesDaTransmissao()` reconhecia como falha local segura apenas `XmlSchemaValidationException`. Dois cenários reais, ambos ocorridos **antes** de qualquer I/O SEFAZ, eram classificados incorretamente:

1. Falha de assinatura digital — `AssinaturaXmlService` lança `IllegalArgumentException`/exceção genérica, nunca `XmlSchemaValidationException`.
2. `DuplicateKeyException` em `marcarTransmitido()` (colisão em `uk_nfe_emissao_chave`) — ocorre em `NfeGeracaoService`, antes até de `NfeOrquestradorService.processar()` ser chamado.

Ambos caíam no fail-safe de rede (`PENDENTE_CONFIRMACAO`), travando o gate da série indefinidamente (Gate 3 de reconciliação não existe), e vazavam como exceção crua, sem `errorCode`/`retryable` estruturado.

**Autodocumentado pela própria banca adversarial anterior:** dois testes em `PedidoEmissaoServiceAdversarialTest` já traziam `"BUG P1"` no `@DisplayName` e passavam *provando* o comportamento incorreto — um padrão de "landmine test" que registra o bug real sem corrigi-lo.

**Não era risco de duplicar/pular nNF** — o número permanecia protegido (gate ocupado, não liberado, não consumido). O problema era disponibilidade/semântica: um certificado ou senha inválidos travariam toda a série até intervenção manual.

### 5.2 Correção — fronteira por fase, não por tipo de exceção

Explicitamente rejeitada a solução frágil de simplesmente ampliar a lista de tipos reconhecidos (`IllegalArgumentException`, `RuntimeException`, `Exception`). `NfeOrquestradorService.processar()` passou a envolver **exclusivamente** a chamada real de transmissão (`NfeTransmitService.transmitirXml`) numa nova exceção, `SefazTransmissaoIncertaException` — o único ponto de todo o pipeline capaz de produzi-la. `falhaOcorreuAntesDaTransmissao()` foi invertida: local é o comportamento padrão, só deixa de ser se essa marca estiver comprovadamente na cadeia de causas.

Novo `errorCode LOCAL_PROCESSING_FAILURE` (422, `retryable=false`) — proposto, reportado e **aprovado pelo Bruno** antes de ser considerado definitivo, por representar alteração de contrato público.

**Colisão de chave — auditoria de causa antes de decidir o comportamento:** `chave_nfe` embute `cnpj+serie+nNF+aaMM+cNF(aleatório 8 dígitos)`; `numero_nfe` já é único antes de `marcarTransmitido` (`UPDATE` por PK, não `INSERT`). Uma colisão exigiria coincidência do `cNF` aleatório ou bug real de geração. Tratada como local (nunca toca rede), mas `retryable=false` — nunca assumida como retry simples, força investigação.

### 5.3 Testes

Os dois testes `"BUG P1"` foram reescritos para provar o comportamento correto (zero SEFAZ, `LOCAL_PROCESSING_FAILURE`, número não consumido, `reverterParaReservadoPorFalhaLocal` chamado). Testes de timeout/`ConnectException`/erro de rede não classificado foram ajustados para simular `SefazTransmissaoIncertaException` envolvendo a exceção real — o que a produção passou a lançar de fato — preservando exatamente o comportamento conservador anterior.

**Resultado:** `borurio-web` 306/306 (era 305 antes desta correção — +1 teste novo de cenário de rede não classificado), `borurio-fiscal` 73/73, `borurio-app` 20/20.

**Classificação final: `P1 PRE-TRANSMISSÃO = APROVADO`. `PEDIDOEMISSAOSERVICE = APROVADO PARA GATE 1`. `GATE 1 = APROVADO DEFINITIVAMENTE`** (reconfirmado).

---

## 6. Resultados finais consolidados (executados nesta sessão, não reaproveitados)

```
borurio-web    = 306/306
borurio-fiscal = 73/73 (1 skip intencional, pré-existente, não relacionado)
borurio-app    = 20/20

P0-1 MySQL real (NfeSequenciaGateRealMySqlIT)         = 3/3
P0-2 MySQL real (P02TenantNullFailClosedRealMySqlIT)  = 4/4
P0-3 MySQL real (NfeEmissaoLockOrderRealMySqlIT)      = 9/9

git diff --check = limpo
git diff | grep -Ei "password|secret|token|apikey|private.key|pfx" = só falsos positivos
  (nomes de método/variável do fluxo JWT; dois placeholders de teste explicitamente fictícios)
```

---

## 7. Estado Git no encerramento

```
$ git status --short
(código do Gate 1 staged manualmente pelo Bruno — 24 arquivos entre M e A)
(documentação desta revisão ainda não staged — ver seção 8)
(19 documentos históricos em docs/report/ permanecem ?? — fora do commit do Gate 1)

$ git diff --cached --check
(vazio — limpo)

$ git log --oneline -3
badf795 chore(hom): amplia rate limit de emissao do OMS
8652c43 docs(fiscal): detalha contingencia formal pre-producao
d7dc480 docs: consolidar homologação OMS e motor fiscal NF-e
```

`badf795` continua sendo o último commit real. Nenhum `git add` de documentação, nenhum `commit`, nenhum `push` executado nesta sessão.

---

## 8. Documentação — auditoria e atualização controlada

Realizada **depois** do fechamento técnico do Gate 1 e **antes** do commit, para que código e contrato não fiquem dessincronizados.

**Documentos oficiais localizados e lidos:** `INTEGRATION_CONTRACT_PT-BR.md`, `INTEGRATION_CONTRACT_EN.md`, `MTF-001_motor-fiscal-nfe.md`, `MTF-001_motor-fiscal-nfe_EN.md`, `CHECKLIST_ERP_DELIVERY.md`, `CHECKLIST_OMS_ONBOARDING.md`, `FAQ_SMOKE_TEST_OMS.md`, `ROTEIRO_ENTREGA_TIME_CHINES.md`.

**Atualizados:**
- `MTF-001_motor-fiscal-nfe.md` → v3.2. Nova entrada de histórico de versões; seções 1.1/1.2 (fechado/pendente); migrations V033/V034 (seção 3.1); seção 4.4 (ordem canônica de lock) e a nota de classificação de falha logo após o fluxo de emissão; nova seção 5.9 (ciclo operacional do nNF); nova seção 7.5 (fronteira local/transmissão, P1); seção 9.3 corrigida (fallback só para ADMIN, não mais para qualquer `empresaId == null`); nova seção 9.7 (tenant-null fail-closed); ponteiro na seção 11.2a.
- `INTEGRATION_CONTRACT_PT-BR.md` → v1.12. Novo `errorCode LOCAL_PROCESSING_FAILURE` nas seções 6.4 e 8.2a. Nenhum endpoint/campo alterado; seção 6.10 (retorno serie/numeroNFe) permanece exatamente como proposta não confirmada pelo CC — não antecipada.
- `CHECKLIST_ERP_DELIVERY.md` → v1.8. Seção 0 com o estado real de hoje (Gate 1 fechado, código não commitado) preservando a consolidação de 22-07-2026 abaixo, sem reescrevê-la. Seção 1.2 (numeração/concorrência) e seção 6 (testes) atualizadas com os itens e números de hoje. Seção 8 (documentação) com as versões corrigidas.
- `CHECKLIST_OMS_ONBOARDING.md` → v1.15. Nova linha de `LOCAL_PROCESSING_FAILURE` no Bloco 5.
- `FAQ_SMOKE_TEST_OMS.md` → v1.8. Mesma linha adicionada à tabela de `errorCode` (Q10).

**Confirmações:**
- Nenhum documento declara Gate 2, Gate 3 ou o retorno de `serie`/`numeroNFe` (Gate 5) como concluído — todos explicitamente listados como pendentes, posteriores ao commit do Gate 1.
- Os cinco itens pedidos pelo CC (numeração+retorno, cancelamento, CC-e, estoque do cenário dele, contingência) estão registrados como PENDENTES em `CHECKLIST_ERP_DELIVERY.md` seção 0 — nenhum marcado como concluído.
- Nenhum segredo (senha, API key, JWT, certificado, PFX) foi incluído em nenhum documento novo ou editado.
- Documentos históricos de `docs/report/` (julho/agosto anteriores) **não foram alterados** — registram o estado do dia correspondente, preservados como estão.

**Não atualizados nesta revisão (registrado como pendência, não como omissão silenciosa):**
- `MTF-001_motor-fiscal-nfe_EN.md` e `INTEGRATION_CONTRACT_EN.md` — PT-BR é a versão canônica; catch-up EN já era um item técnico separado antes desta sessão (backfill da v3.0 ainda pendente desde 22-07-2026) e continua assim.
- `ROTEIRO_ENTREGA_TIME_CHINES.md` — revisado, sem menção a numeração/concorrência/Gate 1; nenhuma alteração necessária identificada.

**Documentos que devem entrar no commit do Gate 1:**
```
docs/manual/MTF-001_motor-fiscal-nfe.md
docs/manual/INTEGRATION_CONTRACT_PT-BR.md
docs/manual/CHECKLIST_ERP_DELIVERY.md
docs/manual/CHECKLIST_OMS_ONBOARDING.md
docs/manual/FAQ_SMOKE_TEST_OMS.md
docs/report/Checkpoint_Tecnico_2026-08-10.md
docs/report/Relatorio_Tecnico_Diario_2026-08-10.md
```

**Relatórios/checkpoints históricos que devem continuar FORA do commit do Gate 1** (decisão do Bruno, não alterados e não recomendados para o mesmo commit — são registro de dias anteriores, não documentação oficial vigente):
```
docs/report/Analise_Estado_Atual_ERP_Fiscal_2026-07-14.md
docs/report/Avaliacao_Prontidao_Entrega_CC_OMS_2026-07-21.md
docs/report/CHECKPOINT_ENCERRAMENTO_2026-07-22.md
docs/report/Checkpoint_Tecnico_2026-07-15.md
docs/report/Checkpoint_Tecnico_2026-07-16.md
docs/report/Checkpoint_Tecnico_2026-07-20.md
docs/report/Checkpoint_Tecnico_2026-08-06.md
docs/report/Checkpoint_Tecnico_2026-08-07.md
docs/report/DOSSIE_TECNICO_GATE_7H_2026-07-22.md
docs/report/Desenho_Tecnico_Sincronizacao_Serie_Numeracao_2026-07-20.md
docs/report/Mapeamento_Requisitos_Implicitos_Logistica_2026-07-21.md
docs/report/Prompt_Continuidade_2026-07-21.md
docs/report/Relatorio_Tecnico_2026-07-14.md
docs/report/Relatorio_Tecnico_2026-07-15.md
docs/report/Relatorio_Tecnico_2026-07-16.md
docs/report/Relatorio_Tecnico_2026-07-20.md
docs/report/Relatorio_Tecnico_2026-07-22.md
docs/report/Relatorio_Tecnico_Diario_2026-08-07.md
docs/report/Runbook_Deploy_HOM_2026-07-20.md
```

---

## 9. Alterações não realizadas

- Gate 2 (classificação semântica de `cStat`) — **não iniciado**.
- Gate 3 (reconciliação de timeout/crash) — **não implementado**.
- Gate 5 (retorno de `serie`/`numeroNFe` ao contrato OMS) — **não implementado**.
- Cancelamento — correção final **não iniciada**.
- CC-e — interpretação final do retorno SEFAZ **não fechada**.
- Configuração de estoque do cenário do CC — **não aplicada**.
- Contingência fiscal formal — **não implementada**.
- Nenhum deploy em HOM. Nenhum deploy em PRD. Nenhuma chamada real à SEFAZ.
- Nenhum `git add` de documentação. Nenhum `commit`. Nenhum `push`.
- Nenhuma resposta enviada ao CC nesta sessão.

---

## 10. Situação dos Gates

| Item | Status |
|---|---|
| P0-1 (fiscal-numbering × gate) | APROVADO |
| P0-2 (isolamento multiempresa, tenant-null fail-closed) | APROVADO |
| P0-3 (lock ordering / deadlock) | APROVADO |
| P1 (classificação de falha pré-transmissão) | APROVADO |
| **Gate 1** | **APROVADO DEFINITIVAMENTE** — código pronto, staged, documentação sincronizada; **commit manual pendente** |
| Gate 2 (classificação cStat) | BLOQUEADO |
| Gate 3 (reconciliação) | BLOQUEADO |
| Gate 5 (retorno serie/numeroNFe) | BLOQUEADO |
| Cancelamento / CC-e / estoque CC / contingência | PENDENTES — itens do CC, não iniciados |
| HOM | BLOQUEADO |
| PRD | BLOQUEADO |

---

## 11. Próxima sequência técnica

1. Revisão final do pacote completo (código já staged + documentação desta revisão) pelo Bruno.
2. Commit manual pelo Bruno — Claude nunca executa `git add`/`commit`/`push`.
3. Push, a critério do Bruno, separado do commit.
4. Só então: Gate 2 — começando por pesquisa da documentação oficial NF-e vigente antes de qualquer código de classificação de `cStat`.
5. Nenhuma resposta ao CC até haver algo efetivamente testável para oferecer a ele.

---

**Documento complementar ao checkpoint de 10/08/2026 — não o substitui.**
