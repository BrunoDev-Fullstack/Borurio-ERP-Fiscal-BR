# Checkpoint Técnico — 10/08/2026

**Projeto:** Borurio ERP Fiscal BR

**Branch:** `fix/sefaz-xml-structure`

**Último commit existente:** `badf795` — chore(hom): amplia rate limit de emissao do OMS

## Status do dia

Gate 1.2 concluído — Fase A (EstoqueServiceTest) e Fase B (tenant-null fail-closed) fechadas, banca final dos três P0 executada e aprovada, e um P1 de classificação de falha pré-transmissão encontrado na revisão linha por linha de `PedidoEmissaoService` e corrigido. **Gate 1 = APROVADO DEFINITIVAMENTE.** Nenhum commit, push ou deploy realizado — código revisado, testado e staged manualmente pelo Bruno, aguardando fechamento da documentação antes do commit.

## Decisão vigente

**Gate 1 = APROVADO DEFINITIVAMENTE.**
**Gate 2 = BLOQUEADO** (classificação semântica de cStat — não iniciar até o commit manual do Gate 1).

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

## Resultados finais (executados nesta sessão, não reaproveitados)

```
borurio-web    = 306/306
borurio-fiscal = 73/73 (1 skip intencional, não relacionado)
borurio-app    = 20/20

P0-1 MySQL real = 3/3
P0-2 MySQL real = 4/4
P0-3 MySQL real = 9/9

git diff --check = limpo
```

## Documentação

`MTF-001_motor-fiscal-nfe.md` (v3.2), `INTEGRATION_CONTRACT_PT-BR.md` (v1.12), `CHECKLIST_ERP_DELIVERY.md` (v1.8) e `CHECKLIST_OMS_ONBOARDING.md` (v1.15) atualizados nesta sessão para refletir o Gate 1 fechado. `FAQ_SMOKE_TEST_OMS.md` (v1.8) recebeu a linha de `LOCAL_PROCESSING_FAILURE`. Versões EN (`MTF-001_motor-fiscal-nfe_EN.md`, `INTEGRATION_CONTRACT_EN.md`) **não atualizadas** nesta revisão — PT-BR é a versão canônica, catch-up EN fica como item técnico separado, já é um padrão pré-existente no projeto. Nenhum documento histórico (`docs/report/` de julho/agosto anteriores) foi alterado retroativamente.

## Pendências registradas como NÃO concluídas (5 itens do CC)

1. Numeração + retorno de `serie`/`numeroNFe` no `/emitir`/`/situacao` — depende de Gate 2, Gate 3 e Gate 5.
2. Correção final do cancelamento.
3. CC-e final.
4. Configuração de estoque (`controleEstoqueAtivo=false`) para a empresa específica do CC.
5. Teste de contingência fiscal formal.

Nenhum dos cinco foi tocado nesta sessão.

## Próxima retomada obrigatória

1. Revisão final do pacote completo (código + documentação) pelo Bruno.
2. Commit manual pelo Bruno — Claude nunca executa `git add`/`commit`/`push`.
3. Só depois: iniciar Gate 2 (classificação semântica de `cStat`) — começando por pesquisa da documentação oficial NF-e antes de qualquer código.

## Freeze

Nenhuma chamada à SEFAZ. Nenhum deploy HOM/PRD. Nenhum `git add`, `commit` ou `push`. Nenhum container `borurio-mysql-hom`/`borurio-mysql-dev` tocado.

---

*Encerramento do dia 10/08/2026 — Gate 1 aprovado definitivamente, aguardando commit manual pós-revisão final.*
