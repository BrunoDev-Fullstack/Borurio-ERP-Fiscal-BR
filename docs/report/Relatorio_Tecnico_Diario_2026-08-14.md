# Relatório Técnico Diário — 14/08/2026

| Atributo | Valor |
|---|---|
| Data | 2026-08-14 (sexta-feira) |
| Branch | `fix/sefaz-xml-structure` |
| Commits do dia | `9e845ba`, `849da14`, `29bf049`, `c30185f` |
| HEAD ao final do dia | `c30185f` |
| Working tree ao final do dia | Limpo (código/config/testes) — só `docs/report/*.md` de hoje untracked, commit documental separado ainda pendente. Sem push, sem nova rodada HOM |

---

## 1. Resumo da semana (10 a 14/08/2026)

| Dia | Commits | Gate fechado |
|---|---|---|
| 10/08 | `5640276` | Gate 1 — ciclo operacional do nNF, ordem canônica de lock, isolamento tenant-null |
| 11/08 | `620005e`, `d8590fd`, `a746fdf` | Gate 2/3 — matriz `cStat` + reconciliação; Gate 5 — retorno fiscal ao OMS |
| 12/08 | `59b92d5` | Gate de Cancelamento (evento 110111) — idempotência, concorrência, estorno exactly-once |
| 13/08 | `8b6b19b`, `4791572`, `e2167e8` | Gate CC-e (evento 110110) — sequência própria, idempotência, reconciliação de conteúdo |
| 14/08 | `9e845ba`, `849da14`, `29bf049`, `c30185f` | Gate Estoque (ajuste por empresa) + hardening `PUT /empresas/{id}` (lost update corrigido) + Fase 0 (roteamento NORMAL por UF, 3 rodadas de banca) |

Todos os gates de 10 a 14/08 fecham o backlog funcional de 5 itens que o CC havia levantado (numeração/retorno, cancelamento, CC-e, estoque por empresa) — resta apenas o item 5, contingência fiscal formal, em desenho nesta data.

---

## 2. Fechamento Gate Estoque + PUT Empresa (commits `9e845ba`, `849da14`, `29bf049`)

Banca arquitetural do início do dia fechou dois pontos pendentes de 13/08:

- **Gate Estoque:** confirmado que `controleEstoqueAtivo=false` já era suportado integralmente pela lógica de produção vigente, sem alteração em `PedidoEmissaoService`/`NfeEmissaoService`/`NfeEventoService`. Três lacunas de prova fechadas com testes novos, incluindo IT dedicado contra MySQL 8.4 real.
- **PUT `/api/app/empresas/{id}`:** achado de banca — defeito de contrato (payload documentado retornava 500) corrigido com DTO de atualização parcial dedicado. Durante a correção, a banca identificou e comprovou um **lost update real**: leitura sem lock seguida de `UPDATE` de linha inteira permitia que duas requisições concorrentes em campos diferentes da mesma empresa perdessem uma alteração. Comprovado contra MySQL 8.4 real (IT dedicado, defeito confirmado) e corrigido com lock pessimista (`SELECT ... FOR UPDATE`) dentro de uma única fronteira transacional (`TransactionTemplate`).

Resultado da banca: regressão completa **631/631, 0 falhas/erros** (na execução da própria banca, container MySQL 8.4 efêmero removido ao final). Consolidação documental (`CHECKLIST_ERP_DELIVERY.md` v1.11) feita no mesmo commit `29bf049`.

---

## 3. Gate Contingência Fiscal (SVC) — auditoria e desenho, sem código

Depois do fechamento de estoque/PUT, o dia seguiu para o próximo item do backlog: contingência fiscal formal. Decisão explícita de **não pular direto para código** — quatro rodadas de banca fecharam o desenho antes de qualquer implementação:

1. **Auditoria fria inicial:** mapeamento do fluxo atual de indisponibilidade (timeout/conexão/cStat), do gap de roteamento multi-UF (achado central: `NfeOrquestradorService` perdia a UF real da Empresa e recalculava de configuração global) e da incompatibilidade estrutural de `nfe_evento` com o evento prévio do EPEC.
2. **Correções P0 pós-revisão normativa:** posição real de `tpEmis` na chave (35ª posição, não a 24ª como uma primeira leitura sugeriu), distinção entre "timeout de reconciliação" e "prova de não-autorização" (nunca a mesma coisa), boundary real de rede em `NfeTransmitServiceImpl` (o que é e o que não é distinguível por tipo de exceção Java), tabela SVC-AN/SVC-RS por UF corrigida contra o Ato COTEPE/ICMS 39/12 (alteração 110/22).
3. **Desenho do ciclo substituto:** correção de um erro de aritmética do próprio desenho (99+1=100, não 101) e adoção do modelo correto — MOC determina usar **novo número** (não reaproveitar) quando a NORMAL foi transmitida e ficou sem retorno; ciclo substituto SVC como emissão própria, ligada à NORMAL por `emissao_origem_id`.
4. **Banca final de invariantes:** `consolidarNumeroParaContingencia` como operação explícita (nunca reaproveitar `consumirNumero` para um número que continua não-terminal); prova de que o árbitro de efeitos operacionais (`resolverCicloComEfeitos`) tem exatamente 2 chamadores de produção, ambos centralizados — mas `NfeEventoService.finalizar` (cancelamento) é uma segunda autoridade genuína sobre `Pedido`/estoque, que precisa ficar estruturalmente incapaz de tocar essas duas coisas para a emissão substituída (por ausência de dependência no construtor, não por convenção); mapeamento de saneamento fiscal por cStat concreto (nunca "não recebida/processada" genérico).

**Nenhuma linha de código de SVC/contingência foi escrita.** Todo o resultado está registrado nesta conversa e no `Checkpoint_Interno_Semanal_2026-08-14.md`, não em código nem em `CHECKLIST_ERP_DELIVERY.md` (deliberadamente não atualizado ainda — ver seção 4).

---

## 4. Fase 0 — Roteamento fiscal por UF (implementada, testada, **concluída e commitada em `c30185f`**)

Pré-requisito identificado na auditoria acima, independente da SVC em si: o motor já gerava a NF-e com a UF real da Empresa, mas o transporte (`NfeOrquestradorService`/`NfeTransmitServiceImpl`) descartava essa UF e recalculava de `EmitenteProperties` (configuração global).

**Implementado:**
- `SefazRotasProperties`/`SefazRotaResolver` (novos, `borurio-fiscal/config`) — resolução de rota SEFAZ por UF, fail-closed (UF sem rota configurada nunca cai para SP por omissão)
- `NfeOrquestradorService.processar(...)` — novo parâmetro `ufEmitente` obrigatório no caminho real; caminho legado (2 argumentos, endpoint administrativo deprecated) isolado, único lugar que ainda usa `EmitenteProperties`
- `NfeTransmitServiceImpl` — removidos os 4 `@Value` fixos; cada método resolve a rota a partir do parâmetro `uf` já recebido
- `application-dev/hom/prd.yml` — `sefaz.rotas.SP.*` adicionado (mesmos valores de `sefaz.urls.*`, que permanece intocado — ainda serve inutilização/CC-e/cancelamento/manifestação)

**Testes novos:** `SefazRotaResolverTest` (4), `NfeTransmitServiceImplTest` (4), mais 3 métodos novos em `NfePipelineLocalTest` e 1 em `NfeGeracaoServiceTest`.

**Regressão completa executada nesta sessão (`mvn clean test`, reactor completo):** `borurio-app` **20/20**, `borurio-fiscal` **134/134** (1 skip preexistente, não relacionado), `borurio-web` **454/454** — **total 608/608, 0 falhas/erros**. `git diff --check` limpo.

> Nota de precisão: uma execução anterior no mesmo dia, feita com um filtro `-Dtest=...` explícito, registrou incorretamente 1 erro em `GateBHttpIT` (teste de Gate B que exige `GATEB_DB_URL`, artefato de cache de re-execução do Surefire por causa do filtro explícito, não uma falha real). A execução limpa (`mvn clean test`, sem filtro) confirma que `GateBHttpIT` é corretamente excluído da regressão padrão, como os demais testes `*RealMySqlIT`/`*IT` do projeto — o número correto e definitivo do dia é **608/608**.

**Status:** implementada, testada, regressão verde, **3 achados de correção CORRIGIDOS E REVALIDADOS ANTES DO COMMIT**. Revisão de código (`/code-review`) rodou em segundo plano ao final do dia e encontrou 3 achados, todos no próprio código desta fase:

1. `IllegalStateException` de `SefazRotaResolver` (UF sem rota configurada) era capturada pelo `catch` genérico de `NfeOrquestradorService.processar()` e reclassificada como transmissão incerta/retryable — um erro de configuração determinístico ficava mascarado como indisponibilidade temporária da SEFAZ. **Corrigido:** novo tipo dedicado `SefazRotaNaoConfiguradaException` (extends `IllegalStateException`, pacote `borurio-fiscal/exception`) + catch específico em `NfeOrquestradorService.processar()` que o repropaga sem embrulhar.
2. `SefazRotaResolver.resolver()` só confirmava a entrada da UF no mapa, não os 4 sub-campos (`autorizacao`/`retorno`/`consulta`/`status`). **Corrigido:** validação dos 4 campos dentro do próprio `resolver()`, mesma exceção dedicada, mensagem identificando o(s) campo(s) faltando.
3. `NfeGeracaoService` só caía no fallback de UF para `Empresa.uf == null`, não para vazio/em branco, e `validarEnderecoEmitente` não cobria UF. **Corrigido:** `uf` adicionado à mesma validação de endereço já existente (falha cedo, antes de `marcarTransmitido`/numeração) + expressão do fallback tornada blank-safe como defesa em profundidade.

Regressão pós-correção (1ª rodada): `borurio-app` **20/20**, `borurio-fiscal` **137/137** (1 skip preexistente), `borurio-web` **455/455** — **total 612/612, 0 falhas/erros**.

**2ª revisão de código** (sobre o diff já corrigido, cobrindo a Fase 0 inteira) encontrou mais 3 achados, verificados manualmente contra o código antes de aceitar qualquer um:

4. Caminho legado `NfeGeracaoService.gerar(request, null, ...)` (endpoint administrativo `NfeEnvioController.gerarNfe`) perdeu o fallback `"SP"` que o outro endpoint legado (`processar(xmlNfe, cnpjEmitente)`, 2 args) preservou — os dois caminhos administrativos ficaram com comportamento divergente para `EmitenteProperties.uf` em branco. **Corrigido:** mesmo fallback `"SP"` restaurado no caminho de `NfeGeracaoService`.
5. `SefazRotaNaoConfiguradaException` (extends `IllegalStateException`) caía no handler genérico de `IllegalStateException` em `GlobalExceptionHandler`, virando HTTP 422 com a mensagem interna de remediação (nome de propriedade, UF) ecoada ao chamador OMS — um gap de configuração do servidor sendo relatado como erro do cliente. **Corrigido:** handler dedicado, HTTP 500, mensagem genérica ao cliente, detalhe completo só no log do servidor.
6. `NfeReconciliacaoService.consultarSefazEClassificar` (Gate 3) só capturava `SefazTransmissaoIncertaException` — `SefazRotaNaoConfiguradaException` propagava descoberta, quebrando o contrato documentado do método (pendente→409 retryable) e caindo no mesmo 422 do achado 5. Mesma função também tinha o gap `!= null` (não `isBlank`) em `Empresa.uf`, já corrigido em `NfeGeracaoService` na 1ª rodada mas não replicado aqui. **Corrigido:** catch dedicado (log ERROR, retorna pendente) + mesmo blank-check.

Regressão pós-2ª-correção: `borurio-app` **20/20**, `borurio-fiscal` **137/137** (1 skip preexistente), `borurio-web` **459/459** — **total 616/616, 0 falhas/erros**.

**Banca final (3ª rodada, arquivo por arquivo)** aprovou a arquitetura da Fase 0, mas bloqueou o commit em dois pontos que contradiziam o próprio objetivo da fase, além de exigir canonicalização única da UF e diagnóstico da execução Maven que havia falhado antes de ser chamada de instável:

7. **BLOQUEADOR — `NfeReconciliacaoService` ainda caía em SP.** A correção da 2ª rodada trocou `!= null` por `isBlank`, mas manteve `"SP"` como destino de qualquer UF ausente/inválida — exatamente o fallback silencioso que a Fase 0 existe para eliminar. Uma Empresa real com UF inválida seria consultada em SP. **Corrigido:** Empresa ausente ou com UF em branco lança `BusinessException.emitterAddressIncomplete()` (422, não retryable) — nunca tenta SP.
8. **BLOQUEADOR — erro de configuração virava retryable=true.** `SefazRotaNaoConfiguradaException` era logada como ERROR mas devolvia `Decisao.aindaPendente()`, que vira `EMISSAO_AGUARDANDO_RECONCILIACAO` (409/retryable=true) — contradizendo a própria mensagem de log ("retry sozinho nunca resolve"). **Corrigido:** nova `Decisao.pendenteComErroConfiguracao()` + `BusinessException.reconciliacaoErroConfiguracao()` (500, retryable=false); o estado fiscal da emissão continua intocado em ambos os casos — só a resposta operacional ao chamador muda.
9. **Canonicalização única da UF.** `SefazRotaResolver` ganhou `canonicalizarUf(String)` (`Locale.ROOT`, trim+upper) como única fonte de normalização. `NfeGeracaoService` agora canonicaliza `ufEmitente` uma única vez e usa o mesmo valor em cUF/chave, `montarEmit` (endereço do emitente no XML) e transporte — antes, `montarEmit` relia `empresa.getUf()`/`emitente.getUf()` crus, por conta própria. `NfeReconciliacaoService` reusa a mesma canonicalização.
10. **Empresa real nunca herda `EmitenteProperties`.** O fallback de configuração global (`"SP"`) ficou isolado ao caminho administrativo legado (`empresa == null`) — uma Empresa real presente, mesmo com UF em branco, nunca mais é mascarada por `EmitenteProperties.uf`.

Regressão pós-3ª-correção — **duas execuções consecutivas de `mvn -pl borurio-app,borurio-fiscal,borurio-web -am clean test`, para descartar qualquer instabilidade sem prova**: ambas terminaram `EXIT=0`, com contagem **idêntica** — `borurio-app` **20/20**, `borurio-fiscal` **138/138** (1 skip preexistente), `borurio-web` **466/466** — **total 626/626, 0 falhas/erros nas duas execuções**. `git diff --check` limpo. A execução falha observada anteriormente na sessão não pôde ser atribuída a uma causa específica (a captura de log daquela execução foi truncada às últimas 250 linhas, sem o topo com o teste/exceção de origem) — não foi descartada como “flaky” sem investigação; o protocolo de dupla execução limpa foi aplicado exatamente por não haver prova da causa.

**Fase 0 — CONCLUÍDA E COMMITADA em `c30185f`** (`fix(fiscal): roteia autorizador normal pela uf da empresa`) — 19 arquivos, 942 inserções / 46 deleções, exatamente o conjunto revisado nas 3 rodadas de banca desta seção. Este é o fechamento funcional final do dia para a Fase 0. `git status --short` pós-commit: zero Java/YAML/teste pendente, só os `docs/report/*.md` de hoje continuam untracked. Sem push, sem nova rodada HOM, sem SEFAZ, sem CC acionado. `CHECKLIST_ERP_DELIVERY.md`/`CHECKLIST_OMS_ONBOARDING.md` **ainda não atualizados** com o SHA real — deliberadamente fora do escopo deste fechamento documental, fica para quando for pedido explicitamente.

---

## 5. FECHAMENTO DOCUMENTAL FINAL — 14/08/2026

| Item | Estado hoje | Observação |
|---|---|---|
| **Regressão final** | `borurio-app` 20/20, `borurio-fiscal` 138/138 (1 skip preexistente), `borurio-web` 466/466 — **626/626** em 2 execuções consecutivas idênticas (seção 4) | Última regressão desde `c30185f`; nenhum código mudou depois do commit |
| **Documentação** | `Relatorio_Tecnico_Diario_2026-08-14.md`, `Checkpoint_Interno_Semanal_2026-08-14.md`, `Plano_Pre_CC_2026-08-14.md` — todos atualizados hoje, ainda untracked | Commit documental separado, não feito ainda |
| **Deploy HOM** | NÃO REALIZADO | Release `4a39a88` (22/07) continua sendo o último ativo; nenhum gate desta semana (Estoque/PUT/CC-e/Cancelamento/Fase 0) foi deployado |
| **Migrations HOM** | Nenhuma migration nova esta semana | Gate Estoque, PUT Empresa e Fase 0 não exigiram schema novo; a próxima migration real nasce na Fase 1 SVC (seção 3.6 do checkpoint semanal) |
| **Health HOM** | N/A | Sem deploy novo, sem verificação de health nova a fazer |
| **Smoke OMS** | NÃO ACIONADO | Nenhuma chamada real à SEFAZ nem ao ambiente HOM nesta semana; toda a banca desta semana usou MySQL 8.4 efêmero com SEFAZ mockada |
| **Certificado A1** | Sem mudança — válido até 02/07/2027 (confirmado pelo CC em comunicação anterior, ver checkpoint de 04-08) | Não reverificado nesta sessão, não é um item em aberto |
| **Rate limit** | Sem mudança — 300/min em HOM (corrigido em 30-07, `badf795`/`8652c43`) | Não tocado nesta semana |
| **Observabilidade/requestId** | Sem mudança — `GlobalExceptionHandler.errorBody` já inclui `requestId` do MDC quando presente (infra pré-existente) | A única mudança relacionada a observabilidade desta semana foi o handler dedicado para `SefazRotaNaoConfiguradaException` (ver achado 8), que passou a logar ERROR distinto de WARN para erro de configuração |
| **Segurança** | 1 hardening real: `SefazRotaNaoConfiguradaException` deixou de vazar mensagem interna de configuração (nome de propriedade, UF) ao chamador OMS via HTTP 422 — agora 500 genérico (achado 5/seção 4) | Não é um achado novo de superfície de ataque, é fechamento de vazamento de detalhe interno em erro já existente |
| **Contrato OMS** | **Gap real, não bloqueante:** `INTEGRATION_CONTRACT_PT-BR.md` (seção 8.2a, tabela de `errorCode`) ainda não lista `RECONCILIACAO_ERRO_CONFIGURACAO` (novo, 500, retryable=false), introduzido na banca desta semana | Nenhum endpoint/payload existente mudou de formato — é adição de um `errorCode` novo à tabela de referência, não quebra de contrato. Não tocado nesta sessão (fora do escopo pedido) |
| **Necessidade de novo round interno antes do CC** | Sim, mas limitado — ver lista de bloqueadores abaixo | Não presumir que toda dívida técnica listada bloqueia o CC (ver seção 7 do `Plano_Pre_CC_2026-08-14.md`, atualizada hoje) |

**Bloqueadores reais antes de reabrir para o CC** (não mudou desde o `Plano_Pre_CC`, exceto item 2 agora fechado):
1. Contingência fiscal formal (SVC-AN/SVC-RS) — ainda 0% código, só desenho fechado.
2. ~~Fase 0 commitada~~ — **FECHADO hoje** (`c30185f`).
3. Regressão completa verde pós-Fase 1/2/3 SVC.
4. Deploy HOM da janela completa (Gate 1 até contingência).
5. Migrations da Fase 1 SVC aplicadas e confirmadas em HOM.
6. Health HOM verde pós-deploy.
7. Smoke test interno dos endpoints OMS contra o novo release.

**Itens não bloqueantes** (não impedem uma nova rodada com o CC em HOM, mesmo pendentes): atualização de `INTEGRATION_CONTRACT_PT-BR.md`/`_EN.md` com o novo `errorCode`; Postman collection; diagramas arquiteturais; CI/CD; rotação de credenciais de PRD (bloqueador de PRD, não de HOM/CC). Ver `Plano_Pre_CC_2026-08-14.md` seção 7 para a lista completa.

---

## 6. CHECKPOINT DE SEGUNDA-FEIRA (17/08/2026)

HEAD `c30185f`. Próximo gate: **SVC Fase 1 — persistência/ciclo, sem transporte externo.** Ordem de retomada e palavra-chave detalhadas em `Checkpoint_Interno_Semanal_2026-08-14.md` seção 6.

```
RETOMAR BORURIO — SVC FASE 1 (PERSISTENCIA/CICLO, SEM TRANSPORTE) — POS-C30185F — 17-08-2026
```

Ver `Checkpoint_Interno_Semanal_2026-08-14.md` para o detalhamento técnico completo e `Plano_Pre_CC_2026-08-14.md` para o que efetivamente bloqueia a próxima chamada ao CC.
