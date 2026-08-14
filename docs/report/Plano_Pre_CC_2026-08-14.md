# Plano Pré-CC — 14/08/2026

> Leitura cruzada de `CHECKLIST_ERP_DELIVERY.md` (v1.11, commitado em `29bf049`) contra os commits/checkpoints desta semana e o working tree atual. Objetivo: separar o que já está concluído do que só foi desenhado, e isolar o que efetivamente bloqueia a próxima chamada ao CC do que é apenas desejável.

## 1. Classificação — checklist atual vs. realidade de hoje

| Item do checklist | Estado no checklist (v1.11) | Estado real hoje |
|---|---|---|
| Backlog CC #1 — numeração/retorno OMS | CONCLUÍDO, commitado | Confirmado — commits `5640276`/`620005e`/`d8590fd` |
| Backlog CC #2 — cancelamento | CONCLUÍDO, commitado | Confirmado — `59b92d5` |
| Backlog CC #3 — CC-e | CONCLUÍDO, commitado | Confirmado — `4791572`/`8b6b19b` |
| Backlog CC #4 — estoque por empresa | CONCLUÍDO, commitado | Confirmado — `9e845ba` |
| Backlog CC #5 — contingência fiscal formal | PENDENTE | **PENDENTE, agora com desenho fechado** (4 rodadas de banca hoje) — zero código escrito. Não é obsoleto, é o mesmo item, só menos vago |
| Seção 9.2 — "definir e implementar estratégia de contingência... SVC-AN/SVC-RS/EPEC" | Crítico, P1 pré-produção | Modalidade decidida (SVC primeiro, EPEC depois) e escopo técnico (`tpEmis`/`dhCont`/`xJust`/autorizador) confirmado como correto no desenho de hoje — **nenhuma linha desse item do checklist ficou obsoleta**, ela só deixou de ser genérica |
| Seção 6 — última execução de testes registrada | 631/631 (14/08, banca Estoque+PUT) | Ainda a mais recente registrada oficialmente — a regressão final da Fase 0 (626/626, pós-commit `c30185f`, 2 execuções idênticas) **não está no checklist ainda**, por decisão explícita de só editar o checklist quando pedido |
| PUT `/empresas/{id}` hardening | CONCLUÍDO, commitado | Confirmado — `849da14` |

**Nenhum item do checklist está obsoleto/superado por commit desta semana.** O único item que mudou de "vago" para "desenhado, não implementado" é o item 9.2 de contingência — e essa mudança **não está registrada no checklist ainda**, por decisão de esperar o SHA.

## 2. Fase 0 — COMMITADA (atualizado ao final do dia)

- **Fase 0 — roteamento fiscal por UF**: commitada em `c30185f` (`fix(fiscal): roteia autorizador normal pela uf da empresa`), 19 arquivos, 942 inserções/46 deleções. Passou por 2 rodadas de `/code-review` (6 achados corrigidos) + 1 banca manual arquivo-por-arquivo do Bruno, que bloqueou 2 pontos que as revisões automatizadas não pegaram (fallback SP residual em `NfeReconciliacaoService` para Empresa real com UF inválida; erro de configuração permanente virando HTTP 409/retryable=true). Regressão final: 626/626 em 2 execuções consecutivas idênticas. Sem push.

## 3. Pendente local (código, sem depender de infraestrutura externa)

- Fase 1 SVC completa (persistência/ciclo, `consolidarNumeroParaContingencia`, guard de saneamento, `NfeContingenciaSaneamentoService`) — desenhada, zero código.
- Fase 2 SVC (geração XML/chave com `tpEmis`/`dhCont`/`xJust` reais) — depende de confirmar `dhCont`/`xJust` contra o XSD real do projeto antes de codificar.
- Fase 3 SVC (transporte real) — depende das Fases 1 e 2.

## 4. Pendente HOM

- **Nenhum gate desta semana foi deployado em HOM** — release `4a39a88` (22/07) continua ativo. Isso inclui Gate 1/2/3/5, Cancelamento, CC-e, Estoque, PUT Empresa — todos só localmente commitados.
- Fase 0 e SVC nem chegaram a ponto de cogitar HOM ainda.
- Migrations pendentes de aplicar em HOM desde a semana passada: nenhuma nova migration foi criada esta semana (Gate Estoque e PUT Empresa não exigiram schema novo); a Fase 0 também não tem migration. A próxima migration real só nasce na Fase 1 SVC.

## 5. Pendente SEFAZ (revalidação real, não simulada)

- Cancelamento, CC-e, inutilização e consulta de situação — implementados e testados internamente (unitário + concorrência real MySQL), mas **nunca revalidados contra a SEFAZ real em contexto multi-CNPJ** desde que foram reescritos como gates dedicados esta semana (item já registrado no checklist, seção 1.3/3, inalterado).
- Nenhuma chamada real à SEFAZ foi feita em nenhuma das bancas desta semana — todas as provas de concorrência/rollback usaram MySQL 8.4 efêmero com SEFAZ mockada.

## 6. BLOQUEADORES REAIS ANTES DO CC

Só itens que realmente impedem chamar o CC de novo:

1. **Contingência fiscal formal concluída no escopo decidido** (SVC-AN/SVC-RS primeiro) — hoje é 0% código, só desenho. Sem isso, o item 5 do backlog do CC continua aberto.
2. ~~Fase 0 commitada~~ — **FECHADO em 14/08** (`c30185f`). Corrigiu, por si só, um gap real de roteamento multi-UF que existia independente da SVC.
3. **Regressão completa verde** pós-Fase 1/2/3 SVC.
4. **Deploy HOM da janela completa** (Gate 1 até contingência) — o CC testaria contra `4a39a88`, que não tem nenhum dos gates desta semana.
5. **Migrations da Fase 1 SVC aplicadas e confirmadas em HOM** (`flyway_schema_history` sem falhas).
6. **Health HOM verde** pós-deploy.
7. **Smoke test interno dos endpoints OMS** contra o novo release, antes de reabrir para o CC.

## 7. ITENS DESEJÁVEIS, MAS NÃO BLOQUEANTES

- `INTEGRATION_CONTRACT_PT-BR.md` (seção 8.2a, tabela de `errorCode`) ainda não lista `RECONCILIACAO_ERRO_CONFIGURACAO` (novo, 500, retryable=false, introduzido na banca da Fase 0 desta semana) — adição de linha à tabela de referência, nenhum endpoint/payload existente muda de formato. Não bloqueia HOM/CC, mas deve entrar antes de qualquer documentação ser considerada "final".
- Atualização de `MTF-001_motor-fiscal-nfe_EN.md`/`INTEGRATION_CONTRACT_EN.md` (EN em catch-up, PT-BR já é a versão canônica e está atual).
- Atualização da Postman collection.
- Diagramas arquiteturais.
- CI/CD automatizado.
- Resolução da pendência cadastral de IE da empresa-âncora do CC junto à SEFAZ (externa ao código, já registrada, empresa alternativa já em uso para os testes).
- Rotação de credenciais/segredos de PRD (seção 9.2 do checklist) — **bloqueador de PRD, não de uma nova rodada de testes com o CC em HOM**. Registrado aqui explicitamente para não ser confundido com bloqueador de CC.

## 8. GATE DE PRONTIDÃO PARA CC

Nada marcado sem evidência:

- [x] Fase 0 commitada — `c30185f`, 14/08
- [ ] SVC/contingência fechada conforme escopo decidido (SVC-AN/SVC-RS)
- [ ] Regressão completa verde (pós Fase 0 + SVC)
- [ ] Documentação final atualizada (`CHECKLIST_ERP_DELIVERY.md`/`CHECKLIST_OMS_ONBOARDING.md` com SHAs reais)
- [ ] Deploy HOM concluído
- [ ] Migrations HOM verdes
- [ ] Health HOM verde
- [ ] Smoke OMS verde
- [ ] Certificado válido confirmado *(nota: A1 já confirmado válido até 02/07/2027 em comunicação anterior com o CC — não é um item em aberto, só não reverificado nesta sessão)*
- [ ] Nenhum secret exposto *(nota: pendências específicas já catalogadas na seção 9.2 do checklist — rotação de `SECURITY_JWT_SECRET`, remoção de segredo hardcoded de `application-dev.yml` — são bloqueadores de PRD já conhecidos, não um achado novo desta semana; confirmar que nada da sessão desta semana expôs segredo novo)*
- [ ] Evidências/logs prontos *(relatórios técnicos diários e checkpoints da semana já existem — reunir numa mensagem única ao CC é o que falta)*
- [ ] Mensagem ao CC preparada

## 9. Observação sobre o resumo da semana

A leitura acima confirma o resumo feito ao final da semana: o motor fechou praticamente todo o contrato fiscal que o CC havia levantado (numeração, retorno, cancelamento, CC-e, estoque, PUT empresa, roteamento por UF — todos commitados), e a virada de hoje para contingência foi deliberadamente cautelosa (desenho antes de código, com um erro aritmético real corrigido em banca e um bypass real de efeitos operacionais encontrado e endereçado por isolamento estrutural). A Fase 0 corrigiu um problema pré-existente e independente da SVC, e foi a única mudança de código desta semana que passou por banca manual arquivo-por-arquivo além das revisões automatizadas — 2 bloqueadores reais só apareceram nessa banca manual. Nada disso, porém, está pronto para uma nova rodada com o CC — não presumir que toda a dívida técnica listada acima bloqueia essa rodada: só os 7 itens da seção 6 bloqueiam de fato; o resto (seção 7) é desejável, não impeditivo.
