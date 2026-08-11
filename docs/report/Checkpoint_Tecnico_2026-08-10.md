# Checkpoint Técnico — 10/08/2026

**Projeto:** Borurio ERP Fiscal BR

**Branch:** `fix/sefaz-xml-structure`

**Último commit existente:** `5640276` — feat(fiscal): implementa Gate 1 do ciclo de numeracao NF-e (local, 1 commit à frente de `origin`, ainda não pushado)

## Status do dia

Dia de maior avanço até agora. Fechei o Gate 1.2 (Fase A + Fase B + P0-1/P0-2/P0-3 + P1 pré-transmissão), revisei o pacote completo e realizei o commit manual (`5640276`). Depois do commit, implementei e testei o Gate 2 (classificação semântica de `cStat`) e, na sequência, o Gate 3 (reconciliação fiscal contra timeout/duplicidade/número ocupado) — os dois com código real e testes reais, não só desenho. Encerrei o dia com uma auditoria read-only do próximo item (retorno de série/numeroNFe para a OMS), sem implementar. Nenhum push, nenhum deploy, nenhuma chamada real à SEFAZ. Ver `Relatorio_Tecnico_Diario_2026-08-10.md` para o detalhamento completo de cada marco.

## Decisão vigente

**Gate 1 = APROVADO DEFINITIVAMENTE E COMMITADO LOCALMENTE (`5640276`)** — não pushado.
**Gate 2 = IMPLEMENTADO E TESTADO** — não commitado.
**Gate 3 = IMPLEMENTADO E TESTADO** (inclusive contra MySQL real) — não commitado.

- **P0-1** (fiscal-numbering corrompendo o gate ativo) — **APROVADO**. 3/3 MySQL real.
- **P0-2** (isolamento multiempresa, tenant-null fail-closed) — **APROVADO**. `JwtFilter.autenticarUsuario()` nega (403 `TENANT_REQUIRED`) usuário sem empresa vinculada e sem `ROLE_ADMIN`, determinado exclusivamente pelas authorities reais carregadas por `UserDetailsService`. ADMIN sem tenant preservado. OMS inalterado. 4/4 cenários reais contra MySQL efêmero (`P02TenantNullFailClosedRealMySqlIT`) — login real via `/auth/login`, `JwtFilter`/`SecurityConfig` reais.
- **P0-3** (inversão de ordem de lock, deadlock real) — **APROVADO**. 9/9 MySQL real.
- **P1 pré-transmissão** (encontrado na revisão linha por linha do Gate 1, não pela banca automatizada) — **APROVADO após correção**. `falhaOcorreuAntesDaTransmissao()` reconhecia só `XmlSchemaValidationException` como falha local segura, classificando falha de assinatura digital e colisão de chave em `marcarTransmitido()` incorretamente como resultado incerto (`PENDENTE_CONFIRMACAO`, gate preso). Corrigido com fronteira por fase de execução (`SefazTransmissaoIncertaException`, lançada exclusivamente ao redor da chamada real de transmissão em `NfeOrquestradorService`) em vez de lista de tipos de exceção. Novo `errorCode LOCAL_PROCESSING_FAILURE` (422, `retryable=false`), aprovado pelo Bruno.

## Fase A — EstoqueServiceTest

Drift de contrato de exceção confirmado por `git log`: `EstoqueServiceImpl` migrou de `IllegalStateException` para `BusinessException` em 01/06/2026, teste nunca foi atualizado, invisível desde então pelo `skipTests` hardcoded (corrigido em 07/08). Opção A confirmada (contrato atual é o correto) — dois testes corrigidos, `EstoqueService` javadoc atualizado. `borurio-app`: 20/20.

## Fase B — tenant-null fail-closed (P0-2)

Auditoria completa da cadeia JWT → `EmpresaContextHolder` → controllers antes de editar. Vulnerabilidade confirmada por código (não hipotética): `empresaId == null` era tratado como acesso administrativo global sem checar `ROLE_ADMIN`, em `PedidoController`, `ClienteController`, `ProdutoController`, `NfeLogController`. Corrigido de forma central no `JwtFilter` — não espalhado por controller. Prova real: container MySQL 8.4 efêmero criado exclusivamente para a banca (nunca `borurio-mysql-hom`/`borurio-mysql-dev`, confirmado por `docker ps` antes de qualquer operação destrutiva), destruído ao final.

## Revisão final de `PedidoEmissaoService` — achado do P1

Revisão linha por linha (não a banca automatizada, que já estava verde) encontrou dois testes adversariais rotulados `"BUG P1"` no próprio nome — testes que passavam *provando* o comportamento incorreto em vez de o comportamento correto. Corrigidos para provar o comportamento correto. Sem risco de duplicar/pular nNF em nenhum momento — o problema era disponibilidade/semântica (gate preso indevidamente), não integridade de numeração.

## Gate 2 — matriz de classificação de cStat

100/150→AUTORIZADO; 225/302/303→AGUARDANDO_CORRECAO; 103/104(sem infProt)/105/106/110/204/205/218/301/539→PENDENTE_CONFIRMACAO. Sem faixa (`cStat>=200`). Fundamentado em pesquisa regulatória (Ajuste SINIEF 43/23 elimina denegação para modelo 55; NT 2024.001 exclui especificamente a regra do 301). DANFE corrigido para tratar 100/150 como equivalentes.

## Gate 3 — reconciliação fiscal

Novo estado terminal `NUMERO_OCUPADO` (número definitivamente ocupado por identidade fiscal alheia — 205/206/218/539-confirmado — mas o Pedido nunca vira AUTORIZADO; mapeado para `Pedido.status="ERRO"`, sem novo vocabulário público no contrato OMS agora). Local-first via `nfe_documento`, Consulta Situação estruturada (parser dedicado, nunca reaproveitando o de emissão), claim atômico de backoff, e finalização exactly-once de nfe_emissao+nfe_sequencia+Pedido+estoque numa única transação — essa última correção fechou uma janela de crash que também existia no fluxo síncrono do Gate 2, corrigida nos dois.

## Resultados finais (executados nesta sessão, não reaproveitados)

```
borurio-fiscal = 84/84 (76/76 no fechamento do Gate 2 + 8 novos do parser de consulta; 1 skip pré-existente)
borurio-app    = 20/20
borurio-web    = 351/351 (319/319 no fechamento do Gate 2 + 32 novos do Gate 3)

P0-1 MySQL real = 3/3
P0-2 MySQL real = 4/4
P0-3 MySQL real = 9/9 (regressão pós-Gate 3, confirma que a mudança de assinatura não quebrou nada)
Gate3ReconciliacaoRealMySqlIT (MySQL real, porta 3499, container efêmero criado/destruído na sessão) = 3/3

git diff --check = limpo
```

## Documentação

`MTF-001_motor-fiscal-nfe.md` (v3.2), `INTEGRATION_CONTRACT_PT-BR.md` (v1.12), `CHECKLIST_ERP_DELIVERY.md` (v1.8) e `CHECKLIST_OMS_ONBOARDING.md` (v1.15) atualizados para refletir o Gate 1 fechado — commitados junto com o código em `5640276`. `FAQ_SMOKE_TEST_OMS.md` (v1.8) recebeu a linha de `LOCAL_PROCESSING_FAILURE`. Versões EN não atualizadas (pendência pré-existente, PT-BR é canônica). Documentação de Gate 2/Gate 3 ainda não atualizada nos manuais oficiais — fica para depois do commit desses dois pacotes.

## Pendências registradas como NÃO concluídas (5 itens do CC)

1. Numeração + retorno de `serie`/`numeroNFe` no `/emitir`/`/situacao` — auditoria read-only concluída nesta sessão (ver relatório diário, seção 13); implementação ainda pendente.
2. Correção final do cancelamento.
3. CC-e final.
4. Configuração de estoque (`controleEstoqueAtivo=false`) para a empresa específica do CC.
5. Teste de contingência fiscal formal.

Nenhum dos cinco foi implementado nesta sessão.

## Próxima retomada obrigatória (11/08/2026)

1. Revisar o Gate 3 (código + testes desta sessão).
2. Decidir o commit manual do Gate 2 e do Gate 3 — separados entre si e do commit do Gate 1.
3. Implementar o retorno de `serie`/`numeroNFe`/`chaveNFe`/`cStat`/`xMotivo`/`nProt` para a OMS (auditoria já pronta).
4. Cancelamento → CC-e → estoque CC → contingência → regressão completa → HOM → só então contatar o CC.

## Freeze

Nenhuma chamada à SEFAZ. Nenhum deploy HOM/PRD. Nenhum `git add`, `commit` ou `push` além do commit manual local já registrado (`5640276`, sem push). Nenhum container `borurio-mysql-hom`/`borurio-mysql-dev` tocado.

---

*Encerramento do dia 10/08/2026 — Gate 1 commitado localmente; Gate 2 e Gate 3 implementados, testados e aprovados, ambos aguardando commit manual.*
