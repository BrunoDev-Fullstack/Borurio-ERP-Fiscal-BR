# Checkpoint Interno Semanal — 14/08/2026

> Documento só nosso — detalhado o suficiente para retomar segunda-feira (17/08) sem depender da memória da conversa. Não é o relatório oficial (ver `Relatorio_Tecnico_Diario_2026-08-14.md`) nem o plano de prontidão para o CC (ver `Plano_Pre_CC_2026-08-14.md`).

## 1. Estado do repositório agora

- Branch: `fix/sefaz-xml-structure`
- HEAD: `c30185f`
- Fase 0 roteamento NORMAL por UF: concluída/commitada
- Push: não realizado
- HOM nova rodada: não realizada

(HEAD antes da implementação da Fase 0: `29bf049` — docs de fechamento Estoque+PUT Empresa, ver tabela da seção 2.)

## 2. O que fechou definitivamente esta semana (commitado)

| Commit | Data | O quê |
|---|---|---|
| `5640276` | 10/08 | Gate 1 — ciclo `nfe_emissao`, gate de série ativa, ordem canônica de lock, `LOCAL_PROCESSING_FAILURE` |
| `620005e` | 11/08 | Gate 2 — matriz semântica `cStat`; Gate 3 — reconciliação ativa |
| `d8590fd` | 11/08 | Gate 5 — retorno `serie`/`numeroNFe`/estado fiscal ao contrato OMS |
| `a746fdf` | 11/08 | docs (consolidação técnica de 11/08) |
| `59b92d5` | 12/08 | Gate de Cancelamento (110111) — idempotência, concorrência, estorno exactly-once |
| `8b6b19b` | 13/08 | fix isolado — extração de `infEvento` em `procEventoNFe` |
| `4791572` | 13/08 | Gate CC-e (110110) — sequência própria, idempotência, reconciliação de conteúdo |
| `e2167e8` | 13/08 | docs (fechamento CC-e) |
| `9e845ba` | 14/08 | Gate Estoque — prova formal `controleEstoqueAtivo=false` |
| `849da14` | 14/08 | PUT Empresa (Rota B) — DTO parcial + **lost update comprovado e corrigido** |
| `29bf049` | 14/08 | docs (fechamento Estoque+PUT Empresa, `CHECKLIST_ERP_DELIVERY.md` v1.11) |
| `c30185f` | 14/08 | Fase 0 — roteamento do autorizador NORMAL pela UF real da Empresa (`SefazRotaResolver`/`SefazRotasProperties`/`SefazRotaNaoConfiguradaException`), pós 3 rodadas de banca |

Nenhum destes foi deployado em HOM ainda — release `4a39a88` (22/07) continua sendo o último ativo em HOM.

## 3. Gate Contingência SVC — decisões de desenho (nada implementado)

Registro técnico completo das quatro rodadas de banca de hoje, para não perder o raciocínio.

### 3.1 Achados da auditoria fria

- **Roteamento multi-UF quebrado independente de SVC:** `NfeGeracaoService` já resolve `Empresa.uf` corretamente, mas `NfeOrquestradorService.processar()` descartava essa UF e recalculava de `EmitenteProperties` (config global). **Isso foi corrigido hoje via Fase 0** (ver seção 4 e o relatório diário).
- **`nfe_evento` não tem FK física** exigindo NF-e autorizada — a suposição "NF-e precisa estar autorizada" é convenção de `PedidoOperacaoService` (`Pedido.status=="AUTORIZADO"`), não constraint de schema. Ainda assim, tabela dedicada para EPEC continua recomendada — não pela constraint, mas porque `resolucao_origem`/`estado` de `nfe_evento` não modelam a dimensão de obrigação-com-prazo que EPEC exige.
- **Retry de transporte (`SefazRetryConfig`) só distingue por tipo de exceção** (`IOException` não-SSL) — não por fase (connect vs. read). `SocketTimeoutException` é idêntica para timeout de conexão e de leitura na API `HttpsURLConnection`; DNS/connect-refused/TLS handshake SÃO distinguíveis por tipo, mas o código hoje trata tudo uniformemente como incerto (mais conservador que o necessário, nunca menos seguro).

### 3.2 Correções normativas P0 (segunda rodada)

- **Posição de `tpEmis` na chave: 35ª posição** (não a 24ª de uma leitura inicial errada). `cUF(2)+AAMM(4)+CNPJ(14)+mod(2)+serie(3)+nNF(9)` = 34 caracteres antes de `tpEmis`; `cNF(8)+cDV(1)` depois. Confirmado no código (`NfeGeracaoService.java`, `chave43 = cUF+aaaMM+cnpj+"55"+serie+nNF+tpEmis+cNF`).
- **Timeout de reconciliação ≠ prova de não-autorização.** `NfeReconciliacaoService.registrarEscalonamentoSeNecessario` só loga um warning ao estourar `limiteTentativas`/`idadeMaximaMinutos` — nunca decide nada. `cStat=217` isolado (Consulta Situação) também não é prova suficiente — pode ser ambiente errado ou atraso de sincronização entre bases.
- **tpEmis por modalidade (tabela MOC, confirmada):** 1=Normal, 4=EPEC, 6=SVC-AN, 7=SVC-RS.
- **Tabela UF→SVC corrigida (Ato COTEPE/ICMS 39/12, alteração 110/22):**
  - SVC-AN: AC, AL, AP, CE, ES, MG, PA, PB, PI, RJ, RS, RN, RO, RR, SC, SE, **SP**, TO, DF
  - SVC-RS: AM, BA, GO, MA, MT, MS, PE, PR
- **Status SVC:** só `cStat=107` (SVC em operação) permite autorizar; `113`=desativação em andamento; `114`=desabilitada pela SEFAZ de origem. Nunca inferir disponibilidade de timeout do ambiente normal.
- **dhCont/xJust:** obrigatórios quando `tpEmis≠1`; `xJust` 15–256 caracteres (mesma regra mínima já usada no cancelamento, `PedidoOperacaoService`); `dhCont` com timezone. Confirmação contra o XSD real do projeto (`nfe_v4.00_consolidado.xsd`) ainda pendente — próximo passo antes de codificar geração de XML SVC.

### 3.3 Ciclo substituto — modelo final

**Erro corrigido em rodada intermediária:** se `ultimoNumero=99` e a NORMAL ocupa `nNF=100` (ainda não-terminal), o próximo número livre é `99+1=100` — não `101`. `ultimoNumero` semanticamente significa "último número que chegou a um resultado terminal que libera o gate", não "último número tentado".

**Modelo aprovado — MOC determina novo número quando a NORMAL foi transmitida e ficou sem retorno:**

```
antes:            ultimoNumero=99, NORMAL(nNF=100)=PENDENTE_CONFIRMACAO, gate→NORMAL
consolidação:      consolidarNumeroParaContingencia(cnpj,serie,100)  [operação NOVA, dedicada — nunca reaproveitar consumirNumero]
                   → ultimoNumero: 99→100 (NORMAL.estado permanece PENDENTE_CONFIRMACAO, intocado)
reserva SVC:       candidato = ultimoNumero(100)+1 = 101
                   INSERT SVC(nNF=101, tpEmis=6/7, emissao_origem_id=NORMAL.id, estado=RESERVADO)
                   gate → SVC
autorização SVC:   ultimoNumero: 100→101 (via consumirNumero padrão, SVC é quem detém o gate agora)
saneamento NORMAL: NUNCA passa por resolverCicloComEfeitos/aplicarNovoEstado de novo — cairia no guard
                   de consistência de gate (mismatch emissaoAtivaId) e lançaria IllegalStateException.
                   Precisa de caminho dedicado (NfeContingenciaSaneamentoService) que só grava evidência
                   fiscal na própria linha NORMAL — nunca toca nfe_sequencia/Pedido/estoque.
```

`consolidarNumeroParaContingencia` **exige a mesma checagem estrita `numero==ultimoNumero+1`** que `consumirNumero` já tem — não é um mecanismo de salto genérico, é uma operação nomeada e auditável para exatamente este caso (número tornou-se definitivamente não-reutilizável por decisão de contingência, sem inventar cStat nem mexer em Pedido/estoque).

**Dois caminhos de entrada em SVC:**
- **Caminho A** — NORMAL ainda `RESERVADO` (nunca transmitida, `chave_nfe` ainda `NULL`): reaproveita o mesmo nNF, só troca `tp_emis`/`autorizador_destino` na mesma linha. Sem `emissao_origem_id`, sem segunda linha.
- **Caminho B** — NORMAL `TRANSMITIDO`/`PENDENTE_CONFIRMACAO` (chave já gravada): exige o ciclo substituto completo acima.

### 3.4 Exactly-once dos efeitos operacionais

Call sites de produção mapeados (não suposição, `grep` real):
- `resolverCicloComEfeitos`: 2 chamadores — `PedidoEmissaoService.emitir()` e `NfeReconciliacaoService.reconciliar()`, ambos centralizados em `NfeEmissaoService`.
- `baixaDefinitivaItens`/`desfazerReservaItens`: 1 chamador cada, dentro do mesmo `resolverCicloComEfeitos`.
- `pedidoMapper.atualizarStatus`: **achado real** — `NfeEventoService.finalizar` (cancelamento) é uma **segunda autoridade genuína**, estruturalmente separada do árbitro `emissao_ativa_id`, que também escreve `Pedido.status`/estoque incondicionalmente.

**Decisão final:** não criar coluna nova de ownership (`emissao_operacional_id`). Garantia vem de **isolamento de dependências por construção**: `NfeContingenciaSaneamentoService` (cancelamento técnico da NORMAL substituída) nunca deve receber `PedidoMapper`/`EstoqueService`/`NfeEventoService` no construtor — impossibilidade estrutural, não convenção. `Pedido.chaveNfe`/`Pedido.status` só são escritos pelo árbitro real (SVC) e por cancelamento real (que sempre opera sobre a chave que `Pedido.chaveNfe` aponta, sempre a da SVC pós-substituição) — nunca colidem.

Corrida NORMAL-autoriza-primeiro vs. SVC-abre-primeiro: **já serializada pela ordem de lock existente** (`Empresa→nfe_sequencia→nfe_emissao`, ambos os caminhos precisam do mesmo par de locks na mesma ordem). O que faltava não era lock novo — era o caminho de saneamento nunca tentar `aplicarNovoEstado` de novo sobre uma linha já substituída.

### 3.5 Saneamento fiscal — mapeamento por evidência concreta

Nunca "não recebida/processada" (redação antiga). Ajuste SINIEF 07/05 consolidado (43/23):

```
NORMAL transmitida antes da contingência, pendente de retorno
  → Consulta Situação da PRÓPRIA chave (nunca Status Serviço — esse só prova indisponibilidade do serviço, nada sobre a chave específica)
  → cStat=100/150 com protocolo → AUTORIZADA → cancelamento técnico (autorizador NORMAL da própria NF-e, nunca a SVC — SVC não oferece cancelamento de NF-e que não autorizou ela mesma)
  → cStat=225/302/303 (rejeição real, sem ambiguidade de identidade) → NÃO AUTORIZADA → inutilização (sempre no ambiente NORMAL — SVC não oferece inutilização)
  → cStat=217 isolado, 205/206/218/539 sem confirmação, 110 (denegação revogada desde 43/23) → PENDENTE_SANEAMENTO, nunca decide sozinho
```

Nenhuma inutilização automática desenhada ainda — só o mapeamento de quando seria seguro.

### 3.6 Schema conceitual (ainda sem migration)

```
nfe_emissao ADD:
  tp_emis                VARCHAR(1)   NOT NULL DEFAULT '1'
  autorizador_destino    VARCHAR(20)  NOT NULL DEFAULT 'SEFAZ_UF'
  emissao_origem_id      BIGINT NULL
  dh_cont                (tipo a decidir após checar como o projeto persiste OffsetDateTime hoje)
  x_just_contingencia     VARCHAR(256) NULL

UNIQUE KEY uk_nfe_emissao_origem (emissao_origem_id)   -- NULL não conflita entre si no MySQL/InnoDB
```

Três eixos deliberadamente separados: `tpEmis` (campo fiscal literal) ≠ `autorizadorDestino` (endpoint usado agora — muda dentro do MESMO `tpEmis` no ciclo do EPEC, prova de que não podem ser o mesmo campo) ≠ `estadoFiscalDoCiclo` (`nfe_emissao.estado`, já existente, inalterado).

## 4. Fase 0 — implementação, banca e commit (`c30185f`)

Ver relatório diário para a lista completa de arquivos. Resumo funcional: `Empresa.uf` chega até `NfeTransmitServiceImpl` sem ser recalculada por config global; `SefazRotaResolver` falha fechado para UF sem rota; SP inalterado (mesmos endpoints, mesma chave, mesmo certificado, mesmo contrato). Regressão limpa pós-1ª-correção: **612/612** (`mvn clean test`).

Revisão de código (`/code-review`) rodou em segundo plano e voltou com **3 achados confirmados**, todos gaps de classificação de erro/validação (nenhum roteia para UF errada) — **CORRIGIDOS E REVALIDADOS ANTES DO COMMIT**:

1. `SefazRotaResolver` lançava `IllegalStateException` (UF sem rota) de dentro do bloco que `NfeOrquestradorService.processar()` envolvia num `catch(Exception e) → SefazTransmissaoIncertaException` genérico — o erro de config virava "transmissão incerta"/retryable em vez de erro definitivo. **Corrigido:** novo tipo dedicado `SefazRotaNaoConfiguradaException` (extends `IllegalStateException`) + catch específico em `NfeOrquestradorService.processar()` que repropaga sem embrulhar.
2. `SefazRotaResolver.resolver()` validava só a presença da UF no mapa, não dos 4 sub-campos da rota — UF parcialmente configurada (falta um endpoint) só quebrava depois, com NPE cru. **Corrigido:** validação dos 4 campos dentro do próprio `resolver()`, mesma exceção dedicada, mensagem identifica o campo faltando.
3. `NfeGeracaoService` só caía no fallback de UF para `Empresa.uf == null`, não para vazio/em branco; `validarEnderecoEmitente` não cobria UF. **Corrigido:** UF incluído na mesma validação de endereço já existente, mais fallback blank-safe como defesa em profundidade.

**2ª revisão de código** (diff completo da Fase 0, já com os 3 achados acima corrigidos) encontrou mais 3 pontos, todos verificados manualmente contra o código antes de aceitar — **também corrigidos e revalidados**:

4. `NfeGeracaoService.gerar(request, null, ...)` (endpoint legado `NfeEnvioController.gerarNfe`) tinha perdido o fallback `"SP"` que o outro endpoint legado ainda tem, criando divergência entre os dois caminhos administrativos para `EmitenteProperties.uf` em branco. Corrigido: fallback restaurado.
5. `SefazRotaNaoConfiguradaException` caía no handler genérico de `IllegalStateException` (422, mensagem interna de config vazada ao OMS). Corrigido: handler dedicado, 500, mensagem genérica ao cliente.
6. `NfeReconciliacaoService` (Gate 3) não capturava `SefazRotaNaoConfiguradaException` — propagava descoberta e quebrava o contrato documentado do método; mesmo gap `isBlank` da 1ª rodada não replicado aqui. Corrigido: catch dedicado + blank-check.

Regressão pós-2ª-correção: `borurio-app` **20/20**, `borurio-fiscal` **137/137** (1 skip preexistente), `borurio-web` **459/459** — **total 616/616, 0 falhas/erros**.

**Banca final (3ª rodada)** — arquivo por arquivo — aprovou a arquitetura (SefazRotasProperties/SefazRotaResolver/SefazRotaNaoConfiguradaException/NfeTransmitServiceImpl/NfeOrquestradorService/GlobalExceptionHandler), mas bloqueou o commit em dois pontos de `NfeReconciliacaoService` que contradiziam o próprio objetivo da Fase 0:

7. Empresa real com UF inválida ainda caía em SP por omissão — corrigido para `EMITTER_ADDRESS_INCOMPLETE` (422, não retryable), nunca tenta outra UF.
8. Erro de configuração (rota ausente) virava 409/retryable=true — corrigido com `Decisao.pendenteComErroConfiguracao()` + `BusinessException.reconciliacaoErroConfiguracao()` (500, não retryable); estado fiscal da emissão permanece intocado em ambos os casos.
9. Canonicalização única da UF (`SefazRotaResolver.canonicalizarUf`, `Locale.ROOT`) — mesmo valor usado em cUF/chave, endereço do emitente no XML e transporte; antes, `montarEmit` relia UF crua por conta própria.
10. Fallback `"SP"` isolado ao caminho administrativo legado (`empresa == null`) — Empresa real nunca herda `EmitenteProperties`.

Regressão pós-3ª-correção — duas execuções consecutivas de `mvn clean test` para descartar instabilidade sem prova: **ambas EXIT=0, contagem idêntica** — `borurio-app` 20/20, `borurio-fiscal` 138/138 (1 skip), `borurio-web` 466/466 — **total 626/626 nas duas execuções**. A execução falha anterior não pôde ser atribuída a uma causa específica (log truncado, sem o topo do stack trace) — não foi descartada como "flaky" sem investigação; o protocolo de dupla execução limpa foi aplicado por não haver prova da causa. `git diff --check` limpo.

**Fase 0 — CONCLUÍDA E COMMITADA em `c30185f`** (`fix(fiscal): roteia autorizador normal pela uf da empresa`) — 19 arquivos (5 novos + 14 modificados), 942 inserções / 46 deleções, exatamente o conjunto revisado nas 3 rodadas de banca acima. `git status --short` pós-commit: zero Java/YAML/teste pendente, só os `docs/report/*.md` de hoje continuam untracked (commit documental separado, ainda não feito). Sem push. Sem nova rodada HOM. Sem SEFAZ. Sem CC acionado.

## 5. Estado git (referência)

```
$ git log --oneline -4
c30185f fix(fiscal): roteia autorizador normal pela uf da empresa
29bf049 docs(fiscal): registra fechamento de estoque e atualizacao de empresa
849da14 feat(app): implementa atualizacao parcial segura de empresa
9e845ba test(fiscal): valida emissao com controle de estoque desativado
```

HEAD atual: `c30185f`. `CHECKLIST_ERP_DELIVERY.md`/`CHECKLIST_OMS_ONBOARDING.md` **ainda não atualizados com o SHA real** — decisão de deixar para quando o Bruno pedir explicitamente, não incluído nesta rodada de fechamento documental.

## 6. CHECKPOINT DE SEGUNDA-FEIRA (17/08/2026)

**Estado exato ao encerrar a semana:**

| Item | Estado |
|---|---|
| HEAD | `c30185f` |
| Gate Estoque | FECHADO |
| PUT Empresa | FECHADO |
| CC-e | FECHADO |
| Cancelamento | FECHADO |
| Numeração/reconciliação | FECHADO |
| Roteamento NORMAL por UF (Fase 0) | FECHADO |
| SVC (Fase 1 em diante) | NÃO IMPLEMENTADA |
| EPEC | NÃO IMPLEMENTADO |
| HOM (nova rodada) | NÃO REALIZADA |
| CC (nova rodada) | NÃO ACIONADO |
| Push | NÃO REALIZADO |

**Próximo gate:** SVC Fase 1 — persistência/ciclo, **sem transporte externo**.

**Ordem de retomada esperada:**

1. Revisar `Plano_Pre_CC_2026-08-14.md` (versão final, pós-commit da Fase 0).
2. Confirmar o escopo mínimo de contingência exigido antes de reabrir para o CC.
3. Iniciar Fase 1 SVC.
4. Modelar a persistência (migration da seção 3.6 deste documento).
5. Testar local/MySQL (mesmo padrão de IT de concorrência já usado esta semana).
6. Só depois: XML/chave (Fase 2) e transporte (Fase 3).
7. HOM continua bloqueado até os gates da SVC fecharem verdes.

**Palavra-chave de retomada:**

```
RETOMAR BORURIO — SVC FASE 1 (PERSISTENCIA/CICLO, SEM TRANSPORTE) — POS-C30185F — 17-08-2026
```

Mais precisa que uma palavra-chave genérica de fase porque fixa três coisas ao mesmo tempo: o escopo exato da Fase 1 (persistência/ciclo, explicitamente sem transporte — evita reabrir a discussão de escopo já fechada em banca), o commit-âncora (`c30185f`, não "a Fase 0" em abstrato — qualquer commit novo entre hoje e segunda invalida a referência de forma óbvia) e a data. Forma mais curta equivalente, se preferir: `RETOMAR SVC FASE 1 POS-C30185F`.
