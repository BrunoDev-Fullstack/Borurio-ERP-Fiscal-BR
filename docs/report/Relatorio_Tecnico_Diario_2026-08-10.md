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

## 9. Marco 1 — Gate 1: fechado e commitado localmente

Depois da revisão final registrada nas seções 1 a 8, revisei o pacote completo e realizei o commit manual:

```
5640276 — feat(fiscal): implementa Gate 1 do ciclo de numeracao NF-e
```

Esse commit consolida: `nfe_emissao` (ciclo operacional do nNF), gate persistente em `nfe_sequencia` (`emissao_ativa_id`), proteção de série/nNF contra corrida e reaproveitamento indevido, isolamento multiempresa, tenant-null fail-closed (P0-2), fronteira de classificação pré-transmissão (P1), `errorCode LOCAL_PROCESSING_FAILURE`, migrations `V033`/`V034`, e os testes adversariais e de MySQL efêmero descritos acima.

**Gate 1 = CONCLUÍDO / COMMITADO LOCALMENTE.** `HEAD` atual é `5640276`, 1 commit à frente de `origin/fix/sefaz-xml-structure` (que permanece em `badf795`) — **não houve push nesta sessão**, só o commit local.

---

## 10. Marco 2 — Gate 2: classificação semântica do retorno SEFAZ

Depois do commit do Gate 1, implementei o Gate 2: substituí a classificação binária anterior (`cStat == 100` autorizado, `cStat >= 200` rejeitado) por uma matriz explícita, sem nenhuma regra por faixa, em `PedidoEmissaoService.resolverEstadoEmissao()`.

**Matriz implementada:**
```
100 -> AUTORIZADO
150 -> AUTORIZADO

225 -> AGUARDANDO_CORRECAO
302 -> AGUARDANDO_CORRECAO
303 -> AGUARDANDO_CORRECAO

103 -> PENDENTE_CONFIRMACAO
104 (literal, sem infProt) -> PENDENTE_CONFIRMACAO
105 -> PENDENTE_CONFIRMACAO
106 -> PENDENTE_CONFIRMACAO
110 -> PENDENTE_CONFIRMACAO
204 -> PENDENTE_CONFIRMACAO
205 -> PENDENTE_CONFIRMACAO
218 -> PENDENTE_CONFIRMACAO
301 -> PENDENTE_CONFIRMACAO
539 -> PENDENTE_CONFIRMACAO
```

**Pontos técnicos registrados:**
- O caso normal de 104 (com `infProt` presente) já é resolvido pelo parser (`NfeSefazRetornoParser`) antes de chegar à classificação — o 104 literal tratado aqui é só o caso anômalo, sem `infProt`.
- A resposta síncrona (`indSinc=1`, único modo usado pelo Borurio) foi considerada na definição da matriz — não há fluxo assíncrono a cobrir neste Gate.
- `DanfePdfGenerator` corrigido para exibir "PROTOCOLO DE AUTORIZAÇÃO DE USO" também para `cStat=150`, não só `100` — o protocolo de autorização passou a ser exibido corretamente para os dois.
- A denegação antiga (processo revogado pelo Ajuste SINIEF 43/23 para NF-e modelo 55) não foi adotada como fluxo normal vigente — por isso 110/301 permanecem em `PENDENTE_CONFIRMACAO`, fail-safe, nunca convertidos automaticamente em autorização ou rejeição reutilizável.
- 204 e 539 permanecem deliberadamente conservadores (`PENDENTE_CONFIRMACAO`) — a resolução deles depende de reconciliação, tratada no Gate 3.

**Resultados reais executados nesta etapa (Gate 2, antes do início do Gate 3):**
```
borurio-web    = 319/319
borurio-app    = 20/20
borurio-fiscal = 76/76 (1 skip intencional, pré-existente, não relacionado)
```

**Gate 2 = IMPLEMENTADO E TESTADO.** Confirmado por `git status --short` no encerramento desta sessão: **ainda não commitado** — arquivos de produção e teste do Gate 2 aparecem como modificados (`M`) no working tree, junto com o Gate 3 (ver seção 14).

---

## 11. Marco 3 — Pesquisa regulatória que fundamentou o Gate 2

Antes de fechar a matriz do Gate 2, revisei a base regulatória vigente para justificar cada classificação, evitando adivinhar comportamento de `cStat`:

- Confirmei a eliminação do processo de denegação para NF-e modelo 55 a partir da legislação posterior ao MOC 7.0 (Ajuste SINIEF 43/23) — isso afeta diretamente por que 110/301 não podem ser tratados como fluxo normal vigente.
- 302/303 foram tratados como rejeições correntes (mesmo `cStat`, efeito de rejeição corrigível, não mais denegação).
- 301 foi mantido conservador por corresponder a uma regra histórica excluída, não a uma rejeição corrigível equivalente a 302/303.
- 204 e 539 não foram tratados como rejeição corrigível comum — exigem reconciliação, não decisão automática no momento da emissão.
- Considerei a exigência de resposta síncrona para lote unitário (relevante porque o Borurio já opera com `indSinc=1`).
- Identifiquei que o pacote local de Reforma Tributária (RTC, NT 2025.002 v1.00) está desatualizado em relação à versão vigente — **não tratei isso como item deste Gate**; fica registrado como pendência para uma etapa específica futura, sem qualquer alteração de código relacionada a IBS/CBS/IS nesta sessão.
- Registrei que, quando a contingência formal for implementada, deve usar a documentação regulatória vigente no momento da implementação, não a base já identificada como desatualizada.

Nenhuma dessas decisões regulatórias foi convertida em implementação além do que está descrito nas seções 10 e 12 — pesquisa e implementação seguiram o mesmo escopo.

---

## 12. Marco 4 — Gate 3: reconciliação fiscal — implementado e testado

Depois do Gate 2, conduzi duas rodadas de auditoria (a primeira concentrada no desenho geral da reconciliação, a segunda focada especificamente em atomicidade e concorrência) antes de implementar. As duas auditorias resultaram em decisões e depois em código real, testado — não ficaram só em desenho.

**Decisões e comportamento implementados:**
- Reconciliação sempre pela chave NF-e já congelada no ciclo — nunca retransmite cegamente.
- Estratégia local-first: `nfe_documento` resolve sem tocar a SEFAZ quando há evidência suficiente (chave igual à congelada, `cStat` definitivo, protocolo coerente quando exigido); caso contrário, consulta real.
- Consulta Situação SEFAZ (`consSitNFe`) interpretada por um parser estruturado dedicado (`NfeConsultaSituacaoParser`/`NfeConsultaSituacaoRetorno`), nunca reaproveitando o parser de emissão — os vocabulários de `cStat` são diferentes.
- Distinção explícita entre falha de transporte (rede), falha de parse (resposta ilegível) e resposta fiscal válida — uma resposta com `cStat` conhecido nunca é tratada como falha de transporte.
- `cStat=217` tratado como resposta válida ("NF-e não consta na base"), nunca como timeout.
- `cStat=635` tratado como processamento ainda pendente, sem retransmissão.
- `cStat=205/206/218` (e `539` com chave de acesso divergente confirmada) tratados como número definitivamente ocupado quando comprovados pela consulta ou por evidência local.
- `cStat=204` com protocolo válido (mesma chave, `cStat` individual 100/150, `nProt` presente) permite reconciliar como autorizado; sem protocolo, cai para consulta.
- `cStat=539` exige reconciliação pela chave congelada, sem gerar chave ou nNF novo em nenhuma hipótese.
- Novo estado interno terminal: `NfeEmissao.Estados.NUMERO_OCUPADO` — consome o número (nunca reutilizado), libera o gate da série, e o Pedido correspondente recebe `status = "ERRO"` (decisão que tomei conscientemente para não criar vocabulário público novo no contrato da OMS agora; a precisão do motivo fica em `nfe_emissao.cstat`/`xmotivo`).
- Backoff persistente (`ultima_consulta_em`/`tentativas_consulta` em `nfe_emissao`, migration `V035`), configurável por propriedades (`sefaz.reconciliacao.*`), nunca hardcoded.
- Claim atômico de consulta (`NfeEmissaoMapper.tentarAdquirirJanelaConsulta`) — UPDATE condicional único, mesmo padrão já usado no claim de emissão (P0.1); nenhuma chamada SEFAZ ocorre dentro de transação de banco.
- Finalização exactly-once de nfe_emissao + nfe_sequencia + Pedido + estoque numa única transação (`NfeEmissaoService.resolverCicloComEfeitos`) — fecha uma janela de crash que identifiquei existir também no fluxo síncrono normal do Gate 2, não só na reconciliação, e por isso apliquei a mesma correção aos dois caminhos.

**Arquivos novos:** `NfeReconciliacaoService.java`, `NfeConsultaSituacaoParser.java`, `NfeConsultaSituacaoRetorno.java`, `NfeConsultaSituacaoService.java`, `SefazReconciliacaoProperties.java`, `V035__nfe_emissao_add_reconciliacao.sql`, mais três classes de teste (`NfeConsultaSituacaoParserTest`, `NfeReconciliacaoServiceTest`, `Gate3ReconciliacaoRealMySqlIT` com sua classe de propriedades).

**Arquivos modificados:** `NfeEmissao.java` (estado `NUMERO_OCUPADO` e campos de backoff), `NfeEmissaoMapper.java`, `NfeEmissaoService.java` (`resolverCicloComEfeitos`, `tentarAdquirirJanelaConsulta`), `PedidoEmissaoService.java` (rota de reconciliação para pedidos em `AGUARDANDO`), `NfeTransmitServiceImpl.java` e `SefazTransmissaoIncertaException.java` (fronteira de falha de transporte também na consulta), `BusinessException.java` (`numeroFiscalOcupado`), `application.yml`, e os arquivos de teste do Gate 1/Gate 2 que precisaram de ajuste mecânico de assinatura por causa da nova finalização atômica.

**Resultados reais executados (Gate 3, estado final da sessão):**
```
borurio-fiscal = 84/84  (76/76 do Gate 2 + 8 novos do parser de consulta; 1 skip pré-existente)
borurio-app    = 20/20  (inalterado)
borurio-web    = 351/351 (319/319 do Gate 2 + 24 de NfeReconciliacaoServiceTest + 8 de NfeEmissaoServiceTest)

MySQL real efêmero (container criado e destruído nesta sessão, porta 3499, nunca 3307/3308/dev/hom):
  Gate3ReconciliacaoRealMySqlIT      = 3/3 (claim atômico sob 10 threads reais; exactly-once de
                                             AUTORIZADO e NUMERO_OCUPADO sob 5 threads reais)
  NfeEmissaoLockOrderRealMySqlIT     = 9/9 (regressão do P0-3 — confirma que a mudança de
                                             assinatura de NfeEmissaoService não quebrou a ordem de lock)

git diff --check = limpo
```

**Gate 3 = IMPLEMENTADO E TESTADO**, com evidência de código e testes reais (unitários e MySQL real) — não apenas auditoria/desenho. Confirmado por `git status --short`: **não commitado**.

**Risco residual registrado, não resolvido:** o escalonamento operacional para ciclos pendentes há muito tempo (limite de tentativas/idade máxima) hoje só gera um log de aviso — não há canal de alerta ou painel; a ordem de lock estendida (Empresa→sequência→emissão→Pedido→Estoque) foi provada com Pedido/Estoque mockados, não contra tabelas reais de produto/estoque.

---

## 13. Auditoria read-only — retorno de série/numeroNFe/chaveNFe para a OMS

Depois do Gate 3 verde, conduzi uma auditoria read-only (sem alteração de código) sobre como o Borurio hoje retorna dados fiscais à OMS em `/emitir` e `/situacao`. Principais achados:

- `POST /emitir` (sucesso) devolve hoje só `chaveNfe` e o XML SOAP bruto — sem `cStat`/`xMotivo`/`nProt`/`serie`/`numeroNfe` estruturados.
- `GET /situacao` devolve `cStat`/`xMotivo`/`nProt` (quando existe `NfeDocumento`), mas o campo `numero` presente é o número interno do Pedido, não o nNF fiscal; não há `serie`/`numeroNfe` explícitos em nenhum dos dois endpoints.
- Não existe coluna de `numeroNfe` na tabela `pedido` (só `serieNfe`, que já é mantida sincronizada).
- A garantia "pedido posterior nunca autorizado antes do ciclo anterior estar resolvido" é interna e independente do que é retornado à OMS — não há brecha de correção; a lacuna encontrada é de visibilidade (a OMS não recebe hoje dado estruturado suficiente para diferenciar "número pulado por conflito" de qualquer outro erro, nem para consultar o estado do gate antes de agir).
- Não existe endpoint de leitura para consultar o próximo número antes de emitir; `PUT /api/integration/fiscal-numbering/{cnpj}` (não é `POST`, é `PUT`) só devolve esse dado como efeito colateral de uma chamada de escrita.

**Nenhum código foi alterado nesta auditoria.** Fica registrada como base para a próxima etapa (ver seção 16).

---

## 14. Pendências para a próxima rodada de homologação com o CC

1. **Série e número da NF-e** — a OMS sincroniza por `/api/integration/fiscal-numbering`; o Borurio mantém a sequência; a série permanece estável; o nNF incrementa; não pode haver salto enquanto o número anterior estiver sem destino (isso já é garantido internamente pelo Gate 1/3). Falta fechar o retorno de `serie`/`numeroNFe` para a OMS (auditoria read-only concluída na seção 13; implementação ainda pendente).
2. **Cancelamento** — precisa interpretar `cStat`/`xMotivo` real da SEFAZ; só sucesso real pode marcar `CANCELADO`; rejeição precisa ser estruturada; timeout precisa reconciliar; repetição precisa ser idempotente. Não tocado nesta sessão.
3. **CC-e** — o endpoint já existe; falta fechar a interpretação do resultado real da SEFAZ e a idempotência. Não tocado nesta sessão.
4. **Estoque** — manter o controle configurável por empresa; o cenário do CC precisa conseguir emitir com `controleEstoqueAtivo=false`; não remover o controle global do produto/sistema. Não tocado nesta sessão (mecanismo já existe desde antes, decisão de configuração pendente).
5. **Contingência** — retry curto/503 não equivale a contingência fiscal formal; falta fechar a estratégia/teste vigente de EPEC/SVC quando aplicável, usando documentação regulatória vigente no momento da implementação (ver seção 11). Não tocado nesta sessão.
6. **Certificado A1** — o CC confirmou que o certificado renovado da empresa usada nos testes tem validade informada até 02/07/2027. Nenhuma senha, arquivo ou conteúdo de certificado foi registrado em nenhum documento.
7. **Manifestação do destinatário** — endpoint mantido; fora do fluxo principal atual da OMS; não é bloqueador para a próxima rodada de homologação.

---

## 15. O que não foi feito

- Não houve deploy em HOM nesta sessão.
- Não houve deploy em PRD nesta sessão.
- Não houve nenhuma chamada real à SEFAZ (produção ou homologação) nesta sessão.
- Não houve alteração de certificado.
- Cancelamento, CC-e e contingência formal **não foram concluídos**.
- O retorno de `serie`/`numeroNFe` para a OMS **não foi implementado** — só auditado (seção 13).
- Não enviei ao CC nenhuma mensagem afirmando que os cinco itens pendentes estão prontos.
- Gate 2 e Gate 3 estão implementados e testados, mas só podem ser chamados de commitados quando houver evidência real de `git log` — hoje só o Gate 1 (`5640276`) tem essa evidência, e mesmo esse commit **não foi enviado ao remoto** (`origin` continua em `badf795`).

---

## 16. Estado Git no encerramento real da sessão

```
$ git branch --show-current
fix/sefaz-xml-structure

$ git log -3 --oneline
5640276 feat(fiscal): implementa Gate 1 do ciclo de numeracao NF-e
badf795 chore(hom): amplia rate limit de emissao do OMS
8652c43 docs(fiscal): detalha contingencia formal pre-producao

$ git rev-list --count origin/fix/sefaz-xml-structure..HEAD
1   (commit 5640276 local, ainda não pushado)

$ git status --short
16 arquivos modificados (M) — Gate 2 + Gate 3, código de produção e testes
10 arquivos novos (??) — Gate 3 (services/DTOs/parser/migration/testes novos)
19 documentos históricos (??) em docs/report/ — fora de escopo, não alterados
(este relatório e o checkpoint de 10/08/2026 sendo atualizados agora)

$ git diff --stat
16 files changed, 818 insertions(+), 86 deletions(-)

$ git diff --check
(vazio — limpo)
```

Nenhum segredo, senha, API key, certificado ou conteúdo de `.env` em nenhum arquivo alterado ou criado nesta sessão.

---

## 17. Próxima ação obrigatória — retomada em 11/08/2026

Gate 3 está implementado e verde. A sequência de retomada é:

1. Revisar o Gate 3 (código + testes desta sessão) antes de qualquer novo desenvolvimento.
2. Decidir o commit manual do Gate 2 e do Gate 3 — separado do commit do Gate 1, sem misturar semanticamente os três pacotes.
3. Auditar (já concluído nesta sessão, seção 13) e então implementar o retorno de `serie`/`numeroNFe`/`chaveNFe`/`cStat`/`xMotivo`/`nProt` para a OMS em `/emitir` e `/situacao`.
4. Cancelamento.
5. CC-e.
6. Configuração de estoque da empresa do CC.
7. Contingência formal.
8. Regressão completa + deploy HOM.
9. Só então contatar o CC — mensagem única informando que os ajustes terminaram e propondo a rodada de integração.

---

**Documento consolidado — cobre o dia completo de 10/08/2026 (Gate 1 commitado localmente, Gate 2 e Gate 3 implementados e testados, ambos ainda não commitados). Complementar ao checkpoint de 10/08/2026.**
