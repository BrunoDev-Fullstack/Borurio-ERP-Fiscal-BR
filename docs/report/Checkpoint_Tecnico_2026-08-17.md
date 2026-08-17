# Checkpoint Técnico — 17/08/2026

**Projeto:** Borurio ERP Fiscal BR

**Branch:** `fix/sefaz-xml-structure`

**Último commit existente:** `0520b15` — feat(fiscal): implementa ciclo persistente de contingencia SVC (commit local, ainda sem push)

## Status do dia

Retomei a sessão de segunda-feira exatamente na palavra-chave combinada em 14/08 (`RETOMAR BORURIO — SVC FASE 1 (PERSISTENCIA/CICLO, SEM TRANSPORTE) — POS-C30185F`). O plano de implementação passou por quatro rodadas de banca antes da primeira linha de produção (v1 a v4 — ver `Relatorio_Tecnico_Diario_2026-08-17.md` seção 1 para o detalhamento de cada rejeição/ajuste). Implementei a v4 aprovada: migration `V038`, ciclo de substituição Path B completo (`NfeContingenciaService`), CAS explícito do gate, `Propagation.MANDATORY`, branch late-NORMAL evidence-only em `NfeEmissaoService`. Depois da implementação, duas rodadas de `/code-review` encontraram achados reais — um de concorrência (gap lock, investigado com MySQL real antes de qualquer correção) e um de leitura stale (corrigido direto, por ser inequívoco). Commit manual: `0520b15`.

## Decisão vigente

**SVC Fase 1 (persistência/ciclo) = FECHADA E COMMITADA LOCALMENTE (`0520b15`)** — não pushada.

- **Invariante central, sem exceção:** `emissao_origem_id` é a verdade durável de que uma NORMAL foi substituída. `emissao_ativa_id` (o gate corrente de `nfe_sequencia`) NÃO é — é só um ponteiro do ciclo ativo, sem memória do que já passou por ele.
- **Fast-path (não é fonte de verdade):** quando a transição é terminal e o gate ainda aponta pra própria emissão, sob o mesmo lock de `nfe_sequencia` já necessário pra consumir número/liberar gate, uma substituição commitada é impossível — pula a busca por `emissao_origem_id`. Em qualquer outro caso (gate alhures, `NULL`, ou transição não-terminal), a busca roda normalmente.
- **Gap lock em `uk_nfe_emissao_origem`:** reproduzido contra MySQL 8.4 real — `X` lock no *supremum* do índice, `INSERT` concorrente `WAITING`. Deadlock **não** reproduzido em 600 operações de estresse (2×300). Classificado como contenção/latência, não corrupção. Fast-path eliminou o lock no fluxo terminal comum — confirmado com `data_lock_waits` vazio depois do fix.
- **Leitura stale (2ª code review):** uma releitura não-bloqueante reaproveitava snapshot desatualizado sob `REPEATABLE_READ`, arriscando WARN de divergência falso numa corrida real. Corrigido reaproveitando a linha já lida com lock, sem releitura nova.
- **Caminho A não implementado** nesta fase (rejeitado explicitamente, sem bloqueio estrutural pra Fase 2). EPEC fora de escopo.

## Commit do dia

```
0520b15 — feat(fiscal): implementa ciclo persistente de contingencia SVC
          17 arquivos, 2229 insertions(+) / 12 deletions(-)
```

## Resultados finais (executados nesta sessão, 2× consecutivas idênticas)

```
borurio-core    = 1/1
borurio-app    = 20/20
borurio-fiscal = 157/157 (1 skip pré-existente)
borurio-web   = 473/473
TOTAL         = 651/651, 0 falhas/erros

NfeContingenciaConcorrenciaRealMySqlIT              = 9/9  (MySQL 8.4 efêmero, RACE A/B/C + variações)
NfeContingenciaGapLockReproducaoRealMySqlIT         = 4/4  (investigativo: determinístico + fast-path + residual + stress)
NfeSequenciaServicePropagationMandatoryTest         = 2/2  (contexto Spring real)

git diff --check = limpo
```

## Documentação

`Relatorio_Tecnico_Diario_2026-08-17.md` (detalhamento completo: as 4 rodadas de banca do plano, escopo entregue item a item, os dois findings de code review com investigação/correção, resultados de teste). Ambos ainda untracked — commit documental separado fica para depois desta banca.

## Item do backlog do CC — status real

Contingência fiscal formal (item 5 do backlog levantado pelo CC, único restante desde 14/08): **persistência/ciclo implementado, testado e commitado localmente** (`0520b15`). **Não disponibilizado em HOM. Sem XML/chave SVC, sem transporte real, sem endpoint OMS. Não validado pelo CC.**

## Pendências registradas como NÃO concluídas

1. Fase 2 SVC — geração de XML/chave (`tpEmis`/`dhCont`/`xJust` reais no documento fiscal).
2. Fase 3 SVC — transporte real à SEFAZ/SVC.
3. Caminho A (troca de `tpEmis` na mesma linha `RESERVADO`) — adiado pra Fase 2.
4. EPEC — não iniciado.
5. Cancelamento técnico/inutilização automática sobre NORMAL substituída — fundação existe (`emissao_origem_id`), nenhuma ação implementada.
6. Deploy HOM da janela completa (Gate 1 até contingência) — release `4a39a88` (22/07) continua sendo o último ativo.
7. Atualização de `CHECKLIST_ERP_DELIVERY.md`/`CHECKLIST_OMS_ONBOARDING.md`/`INTEGRATION_CONTRACT_*` com o SHA e o escopo da Fase 1 — deliberadamente não tocada ainda.

Nenhum dos sete foi iniciado nesta sessão.

## Próxima retomada — ordem esperada

1. Banca desta documentação (`Relatorio_Tecnico_Diario_2026-08-17.md` + este checkpoint).
2. Commit documental separado (manual).
3. Só então, formalmente, SVC Fase 2 — XML/chave, ainda sem push e sem HOM.

## Freeze

Nenhuma chamada à SEFAZ. Nenhum deploy HOM/PRD. Nenhum `git push`. Nenhum container `borurio-mysql-hom`/`borurio-mysql-dev` tocado — só o efêmero de porta 3309, criado e destruído três vezes, nunca reaproveitado entre investigações. Fase 2 explicitamente NÃO iniciada.

---

*Encerramento do dia 17/08/2026 — SVC Fase 1 (persistência/ciclo) fechada e commitada (`0520b15`), gap lock investigado e mitigado com fast-path, leitura stale corrigida, 651/651 + 9/9 + 4/4 + 2/2 em execuções duplicadas idênticas. Fase 2 fica para depois da banca desta documentação e do commit documental separado.*
