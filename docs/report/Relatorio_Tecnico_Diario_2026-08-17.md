# Relatório Técnico Diário — 17/08/2026

| Atributo | Valor |
|---|---|
| Data | 2026-08-17 (segunda-feira) |
| Branch | `fix/sefaz-xml-structure` |
| HEAD no início da sessão | `54013fc` (fechamento documental de 14/08; Fase 0 já concluída em `c30185f`) |
| Commit funcional do dia | `0520b15` — `feat(fiscal): implementa ciclo persistente de contingencia SVC` |
| HEAD ao final do dia | `0520b15` |
| Working tree ao final do dia | Sem alterações pendentes em código/config/testes; permanecem apenas documentos untracked em `docs/report`, incluindo os históricos e os dois documentos de 17/08. Sem push, sem nova rodada HOM, sem SEFAZ |

---

## 1. Base da sessão

Retomada conforme a palavra-chave registrada no fechamento de 14/08 (`RETOMAR BORURIO — SVC FASE 1 (PERSISTENCIA/CICLO, SEM TRANSPORTE) — POS-C30185F`). Sessão iniciada em `54013fc`, com a Fase 0 (roteamento do autorizador NORMAL pela UF real da empresa) já concluída e commitada em `c30185f`. Escopo do dia: SVC Fase 1 — persistência e ciclo de substituição, explicitamente sem transporte externo, sem XML SVC, sem endpoint OMS, sem HOM.

O desenho já vinha fechado do checkpoint de 14/08 (`Checkpoint_Interno_Semanal_2026-08-14.md`, seção 3) — o dia começou direto na implementação, com o plano passando por quatro rodadas de banca antes de qualquer linha de produção ser escrita: v1 (gate ativo usado indevidamente como prova de substituição, rejeitada), v2 (corrigido, mas com quatro problemas de arquitetura: `dh_cont` por config global, ausência de CAS no gate, `consolidarNumeroParaContingencia` sem proteção transacional, uso de `DENEGADO` na lógica nova), v3 (corrigidos os quatro, mais quatro ajustes finais: origem do número da filha, formato de `dh_cont`, ordem do branch de late-NORMAL, acoplamento `tpEmis`↔`autorizadorDestino`), v4 (ajuste final de idempotência — comparação de tupla fiscal completa, não só o estado). Só a v4 foi aprovada para implementação.

---

## 2. Commit funcional final

```
0520b15 feat(fiscal): implementa ciclo persistente de contingencia SVC
         17 arquivos, 2229 insertions(+), 12 deletions(-)
```

Commit manual, conferido antes de aplicar: 17 arquivos staged contra 17 esperados, `Compare-Object` sem diferença, `git diff --cached --check` limpo. Nenhum relatório histórico (`docs/report/*.md` untracked de sessões anteriores) entrou no commit — confirmado pelo `git status --short` pós-commit, que ficou só com os `??` de sempre.

---

## 3. Escopo efetivamente entregue na Fase 1

- **Migration `V038__nfe_emissao_add_contingencia.sql`** — `tp_emis`, `autorizador_destino`, `emissao_origem_id`, `dh_cont`, `x_just_contingencia`, `UNIQUE KEY uk_nfe_emissao_origem (emissao_origem_id)`. Aplicada com sucesso sobre as 37 migrations anteriores em MySQL 8.4 efêmero, sem quebra de schema.
- **`tp_emis`/`autorizador_destino`** (`NfeEmissao.TpEmis`/`AutorizadorDestino`) — domínio `NORMAL`/`SVC_AN`/`SVC_RS` nesta fase (EPEC fora de escopo). `autorizadorDestino` é sempre derivado internamente de `tpEmis` pelo chamador (`NfeContingenciaService`), nunca recebido separado — elimina estruturalmente a combinação inválida.
- **`emissao_origem_id`** — relação durável da filha (SVC) para a NORMAL substituída, protegida por `UNIQUE KEY` no banco.
- **`dh_cont`** — `VARCHAR(30)`, string ISO-8601 com offset formatada uma única vez no momento real de abertura da contingência e persistida literalmente (mesmo padrão já usado por `nfe_evento_idempotencia.dh_reg_evento_resultado`, V037). O cálculo do offset correto por UF/empresa fica fora desta fase — quem chamar `abrirContingencia` (Fase 2/3) decide isso; a Fase 1 só garante que o valor recebido nunca se perde nem é reconstruído por config global.
- **`x_just_contingencia`** — `trim()` antes de validar/persistir, 15–256 caracteres no valor normalizado.
- **`consolidarNumeroParaContingencia`** — operação nova e nomeada em `NfeSequenciaService`, nunca reaproveita `consumirNumero`; mesma checagem estrita `numero == ultimoNumero + 1`; retorna o `ultimoNumero` resultante, que é a autoridade formal de onde o número da filha é derivado (nunca de `NORMAL.numeroNfe` direto).
- **CAS NORMAL→SVC** (`substituirGateParaContingencia`) — `UPDATE` condicional (`WHERE emissao_ativa_id = :normalEsperada`), nunca um `ocuparGate` genérico.
- **`Propagation.MANDATORY`** — `consolidarNumeroParaContingencia`/`substituirGateParaContingencia` nunca podem ser a raiz da própria transação; provado com contexto Spring real (`IllegalTransactionStateException` fora de transação externa).
- **Transação Path B** (`NfeContingenciaService.abrirContingencia`, `borurio-fiscal`) — única fronteira `@Transactional(REPEATABLE_READ)`: pré-leitura não bloqueante → trava `nfe_sequencia` → trava NORMAL → valida → consolida → insere filha → CAS do gate. Construtor só recebe `NfeSequenciaService`/`NfeEmissaoMapper` — impossível receber `PedidoMapper`/`EstoqueService` (não existem em `borurio-fiscal`, que não depende de `borurio-app`). Caminho A (NORMAL ainda `RESERVADO`) explicitamente **não implementado** nesta fase — rejeitado com mensagem clara, sem bloquear estruturalmente a Fase 2.
- **Late NORMAL evidence-only** — `NfeEmissaoService.aplicarNovoEstado` grava evidência fiscal (estado/cStat/xMotivo/nProt) na própria linha NORMAL quando ela já foi substituída, sem nunca tocar `nfe_sequencia`/Pedido/Estoque.
- **Exactly-once** — `resolverCicloComEfeitos` só chama `pedidoMapper`/`estoqueService` quando o resultado interno é `APLICADO_NORMAL`; os três outros resultados (`NAO_APLICADO_JA_TERMINAL`, `APLICADO_EVIDENCIA_SUBSTITUIDA`, `NAO_APLICADO_EVIDENCIA_JA_REGISTRADA`) nunca disparam efeito operacional.

---

## 4. Invariante central

**`emissao_origem_id` é a verdade durável da substituição de uma NORMAL. `emissao_ativa_id` NÃO é.**

A relação vive na linha filha (SVC), aponta pra `NORMAL.id`, nunca muda depois de gravada, e sobrevive mesmo depois que o ciclo ativo (a própria filha) também termina e o gate da série volta a `NULL`. Qualquer decisão sobre "esta NORMAL foi substituída" tem que consultar `emissao_origem_id` — nunca inferir isso do valor atual de `nfe_sequencia.emissao_ativa_id`, que é só o ponteiro do gate corrente, sem memória do que já passou por ele. Essa é a regra que orientou a rejeição do "ponteiro redundante" (`normal.emissao_substituta_id`) levantado durante a investigação da seção 6 — duas representações da mesma relação exigiriam consistência garantida para sempre em migration/rollback/concorrência/saneamento futuro, custo estrutural real pra resolver um problema que, medido, era de latência, não de correção.

Provado com as três corridas de banca (RACE A/B/C, `NfeContingenciaConcorrenciaRealMySqlIT`) e, adicionalmente, com o cenário em que a própria filha SVC já terminou (`emissao_ativa_id` de volta a `NULL`) antes de uma resposta tardia da NORMAL chegar — a evidência ainda é aplicada corretamente porque a busca nunca depende do gate.

---

## 5. Fast-path — `emissao_ativa_id` como otimização, nunca como verdade

Durante a banca da seção 6, `emissao_ativa_id` passou a ser usado como um atalho transacional: quando uma transição é **terminal** (única situação em que `nfe_sequencia` já está travada por outro motivo — consumo de número/liberação de gate), e o gate ainda aponta exatamente para a emissão sendo resolvida, uma substituição já commitada é impossível — `abrirContingencia` precisa do mesmo lock, na mesma ordem canônica, antes de inserir a filha e mover o gate. Nesse caso específico, a busca por `emissao_origem_id` (que toma o lock que motivou a investigação da seção 6) é pulada.

Em qualquer outro caso — gate apontando pra outro lugar, gate `NULL`, ou transição não-terminal (`AGUARDANDO_CORRECAO` continua sem travar `nfe_sequencia`, de propósito, para não reabrir a serialização que o P0-3 de 07/08 eliminou) — a busca por `emissao_origem_id` roda exatamente como antes do fast-path. `emissao_origem_id` continua sendo a única prova durável; o fast-path não a substitui, só evita uma consulta desnecessária quando o próprio lock já garante a resposta.

---

## 6. Finding de concorrência — gap lock em `uk_nfe_emissao_origem`

A revisão de código sobre o diff da Fase 1 (1ª rodada) apontou um risco: a busca por `emissao_origem_id` corria em **toda** resolução de emissão, não só nas relacionadas a contingência; sob `REPEATABLE_READ`, uma busca `FOR UPDATE` que não encontra linha pode tomar gap lock no índice único correspondente.

**Investigação, não correção imediata.** Antes de qualquer mudança de código, o achado foi reproduzido contra MySQL 8.4 real (Docker efêmero, porta isolada, removido ao final de cada rodada):

- **Determinístico:** uma busca `FOR UPDATE` por `emissao_origem_id` sem linha correspondente tomou `X` lock no *supremum* do índice `uk_nfe_emissao_origem` (índice majoritariamente `NULL` — nenhum valor não-nulo hoje); um `INSERT` concorrente com `emissao_origem_id` diferente, em linha não relacionada, ficou `X,INSERT_INTENTION` **WAITING** até a primeira transação commitar. Confirmado em `performance_schema.data_locks`/`data_lock_waits`, 2 execuções idênticas.
- **Estresse:** 300 operações por execução (15 resoluções não relacionadas × 5 aberturas de contingência concorrentes, 15 rodadas), 2 execuções — **0 `ER_LOCK_DEADLOCK` (1213)** nas duas. Rastreamento manual confirma por que: a transação que faz a busca nunca precisa de nenhum lock que a transação de contingência detém (série/linha diferentes) — na pior hipótese, só espera a outra terminar.

**Classificação: contenção/latência confirmada, deadlock NÃO reproduzido.** Toda abertura de contingência serializava contra qualquer resolução de emissão em voo no sistema inteiro (e vice-versa) pelo tempo de vida das duas transações — sem corrupção, sem exactly-once quebrado, mas com custo real de throughput.

**Fast-path eliminou o lock no fluxo terminal comum** (seção 5). Depois do fix, o mesmo cenário reproduzido mostrou `data_lock_waits` **vazio** e a contingência concorrente completando em 25–50ms em vez de esperar — 2 execuções consecutivas confirmando.

---

## 7. Finding de leitura stale — 2ª rodada de code review

Depois do fast-path, uma nova rodada de revisão de código apontou um segundo achado, este **CONFIRMED** por rastreamento de código (sem precisar de reprodução empírica — semântica de MVCC do InnoDB bem documentada, aplicada precisamente ao trecho): a comparação de tupla fiscal da idempotência (late-NORMAL) fazia uma segunda leitura não-bloqueante (`buscarPorId`) depois que a mesma transação `REPEATABLE_READ` já havia fixado seu snapshot consistente na primeira leitura não-bloqueante (`preRead`, mais acima no método). Uma segunda leitura não-bloqueante reaproveita esse mesmo snapshot — podia devolver dado anterior ao commit de quem gravou a evidência primeiro, mesmo já sabendo (via `affected == 0`) que existia evidência mais nova.

**Cenário de falha:** duas respostas tardias verdadeiramente concorrentes para a mesma NORMAL — a que perde a corrida do lock, ao reler para comparar a tupla, podia comparar contra dado velho e disparar um WARN de divergência falso (ou, em tese, deixar de detectar uma divergência real). Os testes existentes só exercitavam essa sequência com commits separados, nunca concorrência de verdade — por isso não pegaram.

**Correção:** eliminada a releitura. A variável `emissao` (já obtida via `FOR UPDATE` mais acima no mesmo método, e mantida sob lock contínuo até o ponto da comparação) já é exatamente o dado fresco que a releitura tentava buscar — leitura com lock nunca usa snapshot, sempre vê o último dado commitado. Reaproveitada diretamente na comparação de tupla; nenhuma nova consulta consistente foi introduzida no lugar. Uma viagem a menos ao banco, bug eliminado por construção.

---

## 8. Resultados de teste (2 execuções consecutivas idênticas em cada suíte)

```
Regressão completa (mvn -pl borurio-app,borurio-fiscal,borurio-web -am clean test):
  borurio-core    1/1
  borurio-app    20/20
  borurio-fiscal 157/157 (1 skip pré-existente)
  borurio-web   473/473
  TOTAL         651/651, 0 falhas/erros — idêntico nas duas execuções

NfeContingenciaConcorrenciaRealMySqlIT (MySQL 8.4 efêmero) = 9/9 × 2
  RACE A, RACE B, RACE C, RACE B repetida, round-trip dh_cont (offset -05:00),
  CAS falhando de propósito, deadlock/lock-order, double-substitute concorrente,
  migration safety

NfeContingenciaGapLockReproducaoRealMySqlIT (investigativo, MySQL 8.4 efêmero) = 4/4 × 2
  determinístico (gap lock confirmado), depois-do-fast-path (bloqueio eliminado),
  residual conhecido (não-terminal inalterado), stress (0 deadlocks/300 operações)

NfeSequenciaServicePropagationMandatoryTest (contexto Spring real) = 2/2

git diff --check = limpo (todas as rodadas)
```

Container MySQL efêmero (porta 3309, nunca 3307/3308/dev/hom) criado e destruído três vezes ao longo do dia — nunca reaproveitado entre investigações, sempre removido ao final.

---

## 9. Explicitamente fora do escopo do dia

Caminho A (mesma linha `RESERVADO`) — Fase 2. `TpEmis.EPEC` e fluxo EPEC inteiro. Geração de XML/chave SVC. Cálculo do offset correto por UF/empresa no momento real de abertura de contingência. Cancelamento técnico/inutilização automática sobre uma NORMAL substituída — a fundação (`emissao_origem_id` + leitura por origem) já existe, nenhum serviço dedicado foi criado. Qualquer chamada real à SEFAZ/SVC. Endpoint OMS. Deploy HOM. Push. Início da Fase 2.

---

## 10. Estado ao final do dia

**SVC Fase 1 — persistência/ciclo: FECHADA, implementada e commitada em `0520b15`.**

HEAD `0520b15`, 17 arquivos, 2229 inserções / 12 deleções. Sem alterações pendentes em código/config/testes; permanecem apenas documentos untracked em `docs/report`, incluindo os históricos e os dois documentos de 17/08. Sem push, sem deploy HOM, sem chamada à SEFAZ, Fase 2 não iniciada.
