# Checkpoint Técnico — 11/08/2026

**Projeto:** Borurio ERP Fiscal BR

**Branch:** `fix/sefaz-xml-structure`

**Último commit existente:** `d8590fd` — feat(fiscal): retorna resultado fiscal da NF-e para o OMS (local, à frente de `origin`, ainda não pushado)

## Status do dia

Retomei pós-reboot com a última prova pendente do Gate 3: `Gate3ReconciliacaoRealMySqlIT` contra MySQL 8.4 efêmero (porta 3499, criado e destruído nesta sessão, nunca 3307/3308/dev/hom) — 3/3 verde. Com a banca completa, fiz o commit manual do Gate 2 + Gate 3 (`620005e`). Na sequência, implementei o item mais pedido pelo CC: retorno estruturado de série, número da NF-e e resultado fiscal em `/emitir` e `/situacao`, com `nfe_emissao` como fonte principal do ciclo fiscal. Revisei o contrato em duas rodadas antes do commit — a segunda encontrou e corrigiu um risco real de segurança (`/situacao` chamando a SEFAZ ao vivo em todo `GET`, contornando o claim/backoff do Gate 3) e um cenário de dado real não coberto (`Pedido.chaveNfe` nulo com chave já congelada em `nfe_emissao`). Um microajuste final removeu o último `cStat` sintético do contrato. Commit manual do Gate de retorno fiscal OMS: `d8590fd`. Nenhum push, nenhum deploy, nenhuma chamada real à SEFAZ. Ver `Relatorio_Tecnico_Diario_2026-08-11.md` para o detalhamento completo.

## Decisão vigente

**Gate 1 = APROVADO DEFINITIVAMENTE E COMMITADO LOCALMENTE (`5640276`)** — não pushado.
**Gate 2 + Gate 3 = COMMITADOS LOCALMENTE (`620005e`)** — não pushado.
**Gate de retorno fiscal OMS = COMMITADO LOCALMENTE (`d8590fd`)** — não pushado.

- **Gate 3 — prova real de concorrência (MySQL efêmero):** `Gate3ReconciliacaoRealMySqlIT` 3/3 — claim atômico da janela de reconciliação sob 10 chamadas concorrentes (exatamente uma vence); exactly-once de `resolverCicloComEfeitos` sob 5 chamadas concorrentes em `AUTORIZADO` e em `NUMERO_OCUPADO` (número queimado nunca reaproveitado).
- **Retorno fiscal OMS:** `POST /emitir` e `GET /situacao` devolvem `serie`, `numeroNFe`, `estadoFiscal`, `cStat`, `xMotivo`, `nProt`, além de `chaveNfe`/`soapRetorno` (inalterados). Fonte única `nfe_emissao`; `nfe_documento` só como fallback legado para pedidos pré-Gate-1 e para `dhRecbto`.
- **`/situacao` deixou de chamar a SEFAZ ao vivo.** Achado de maior risco da revisão contratual: o endpoint de polling da OMS contornava o claim/backoff que o Gate 3 tinha acabado de construir, com risco real de `cStat 656` sob consultas repetidas. Corrigido — leitura pura do estado persistido; reconciliação ativa só pelo mecanismo protegido (`POST /emitir`).
- **`consultaSefaz` preservado, deprecated.** Mantido no payload por retrocompatibilidade (contrato anterior o documentava como sempre presente), sempre `null` a partir de hoje.
- **Mistura de fonte de chave eliminada.** `nfe_emissao.chaveNfe` tem precedência sempre que existe ciclo, usada de forma consistente para resposta, validação de CNPJ e busca em `nfe_documento` dentro do mesmo ramo.
- **Cenário `Pedido.chaveNfe=null` com chave congelada em `nfe_emissao` tratado e testado** — provado por código (falha de rede na primeira tentativa de emissão), não presumido.
- **Nenhum `cStat` sintético no contrato.** `-1` removido de `numeroFiscalOcupado` e de `sefazRejected`; sempre código real da SEFAZ ou `null`.
- **Nomenclatura pública consolidada:** `numeroNFe`.

## Commits do dia

```
620005e — feat(fiscal): implementa semantica SEFAZ e reconciliacao da NF-e
           28 arquivos, 2544 insertions / 136 deletions

d8590fd — feat(fiscal): retorna resultado fiscal da NF-e para o OMS
           12 arquivos, 654 insertions / 115 deletions
```

## Resultados finais (executados nesta sessão, não reaproveitados)

```
Gate3ReconciliacaoRealMySqlIT (MySQL real efêmero) = 3/3

borurio-app    = 20/20
borurio-fiscal = 87/88 (1 skip pré-existente)
borurio-web    = 381/381

NfeReconciliacaoServiceTest (teste focado, reactor -am) = 24/24

git diff --check = limpo
```

## Documentação

`INTEGRATION_CONTRACT_PT-BR.md` e `INTEGRATION_CONTRACT_EN.md` atualizados (seções 6.4/6.5): novos campos e tipos, bloco `NUMERO_FISCAL_OCUPADO`, `consultaSefaz` deprecated, tabela de origem por campo, nota sobre chave congelada. Ambos commitados em `d8590fd`.

## Item do CC — status real

Retorno de série/número/resultado fiscal (`serie`, `numeroNFe`, `chaveNfe`, `estadoFiscal`, `cStat`, `xMotivo`, `nProt`): **implementado, testado e documentado localmente** (`d8590fd`). **Não disponibilizado em HOM. Não validado pelo CC.**

## Pendências registradas como NÃO concluídas

1. Cancelamento — auditoria e correção não iniciadas.
2. CC-e — integração/testes não iniciados.
3. Configuração de estoque (`controleEstoqueAtivo=false`) para o cenário do CC — não ajustada.
4. Contingência fiscal formal — não definida/testada.

Nenhum dos quatro foi tocado nesta sessão.

## Riscos conhecidos de cancelamento — só para investigação amanhã

- Interpretação real de `cStat`/`xMotivo` do evento de cancelamento.
- Não marcar `CANCELADO` quando a SEFAZ rejeitar.
- Rollback de estoque exatamente uma vez.
- Idempotência de cancelamento repetido.
- Timeout/resultado incerto exigindo reconciliação equivalente ao Gate 3.
- Isolamento multiempresa/tenant no novo desenho.

Nenhum implementado ou corrigido hoje.

## Próxima retomada obrigatória (12/08/2026)

1. Auditoria e correção do cancelamento.
2. CC-e.
3. Ajuste/validação da regra de estoque do cenário do CC.
4. Definição e testes de contingência.
5. Regressão final.
6. Documentação.
7. Preparação de HOM.
8. Smoke interno.
9. Só então nova rodada conjunta com o CC.

## Freeze

Nenhuma chamada à SEFAZ. Nenhum deploy HOM/PRD. Nenhum `git push`. Nenhum container `borurio-mysql-hom`/`borurio-mysql-dev` tocado. Cancelamento explicitamente NÃO iniciado hoje — começa amanhã.

---

*Encerramento do dia 11/08/2026 — Gate 3 fechado com prova real de MySQL, Gate 2+3 commitados (`620005e`), retorno fiscal OMS implementado, revisado em duas rodadas e commitado (`d8590fd`). Cancelamento fica para 12/08/2026.*
