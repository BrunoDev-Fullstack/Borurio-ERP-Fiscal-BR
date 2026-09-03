# RELATÓRIO TÉCNICO DIÁRIO — 13/08/2026

## 1. Identificação

| Campo                  | Valor                                                    |
|-------------------------|-----------------------------------------------------------|
| Data                    | 13/08/2026                                                |
| Projeto                 | Borurio ERP Fiscal BR                                     |
| Branch                  | `fix/sefaz-xml-structure`                                 |
| Responsável técnico     | Bruno Ribeiro                                              |
| HEAD atual              | `4791572`                                                  |
| Push                    | NÃO realizado                                              |
| Deploy DEV/HOM          | NÃO realizado                                              |
| SEFAZ real              | NÃO utilizada                                              |

---

## 2. Objetivo do dia

Retomada pela palavra-chave `RETOMAR BORURIO — CCE BANCA FINAL 13/08`, deixada no encerramento de 12-08-2026. O Gate CC-e (evento 110110) já estava implementado e testado desde a sessão anterior, mas com banca final e commit deliberadamente adiados — restavam duas decisões em aberto (ver `Relatorio_Tecnico_Diario_2026-08-12.md`, seção 5): o destino do bugfix compartilhado do parser, e se um IT de rollback dedicado do CC-e era necessário.

O dia fechou as duas decisões, fez a banca técnica final do conjunto e produziu dois commits: um fix isolado do parser e o Gate CC-e completo.

---

## 3. Banca final do CC-e

### 3.1 Rollback da finalização — decisão revertida com prova nova

A avaliação de 12-08-2026 havia julgado um IT de rollback dedicado como redundante com a prova já existente do cancelamento (mesmo mecanismo `TransactionTemplate` + MySQL). Essa avaliação foi revista nesta sessão: o próprio código do orquestrador de CC-e documenta que ele usa um padrão transacional diferente do cancelamento — múltiplas transações internas curtas via `TransactionTemplate`, nunca uma única sessão externa como o cancelamento usa. O IT de concorrência já existente (`NfeCceGateConcorrenciaRealMySqlIT`) provava rollback real da transação de **reserva**, mas nunca exercitava a transação de **finalização** (`aplicarFinalizacao`), que escreve em três tabelas (`nfe_evento`, `nfe_evento_idempotencia`, `nfe_evento_sequencia`).

Escrito `NfeCceFinalizacaoRollbackRealMySqlIT` — contra MySQL 8.4 efêmero, leva a CC-e ao estado real `TRANSMITIDO` pelo fluxo de produção normal e força uma falha real (via um `NfeEventoIdempotenciaMapper` de teste que decora o mapper real, delegando tudo exceto `atualizarResultado`, que lança exceção) depois que a 1ª escrita real da finalização já aconteceu na mesma transação. Nenhuma flag de teste entrou em código de produção. Resultado: as três tabelas confirmadas, por query direta, no estado `TRANSMITIDO` anterior — nenhum campo de resultado, gate ainda ocupado pela mesma identidade, sequência não avançada. **1/1 PASS** na primeira execução.

### 3.2 Split do bugfix do parser — decisão fechada com análise de hunks

Confirmado por análise de hunks (linha a linha, com auxílio de `grep` para identificadores exclusivos do CC-e) que o bugfix de `retEvento`/`infEvento` em `NfeConsultaSituacaoParser` é isolável do Gate CC-e sem refatoração artificial. O código foi reorganizado (extração do método `extrairConteudoEventoOriginal`, reposicionamento de `firstChildElement`) para que a separação ficasse limpa nos arquivos reais, não só em teoria — sem uso de Git nesse passo, só edição de código, revalidada pela suíte focada do parser (21/21) a cada alteração.

O javadoc de `buscarEventoCorrespondente` foi corrigido para não acoplar o fix ao Gate CC-e: removida a referência a `NfeCceClassificador` (mantido só `NfeEventoClassificador`) e corrigida a frase que dizia "já estava commitado no gate de cancelamento" (afirmação falsa — o que já existia em `59b92d5` era o **defeito**, não a correção).

O bugfix isolado foi revisado por Bruno antes de qualquer staging — o conteúdo exato do commit `8b6b19b` foi confirmado previamente, sem ambiguidade sobre o que entraria nele.

---

## 4. Commits realizados

### `8b6b19b` — fix(fiscal): corrige extração de infEvento em procEventoNFe

2 arquivos, 67 inserções / 16 deleções. Contém exclusivamente:
- `NfeConsultaSituacaoParser.java` — escopo de `retEvento` antes de buscar `infEvento` (o bug real: sem esse escopo, `getElementsByTagNameNS` podia capturar o `infEvento` do bloco `evento` — o pedido original ecoado, sem `cStat`/`nProt` — em vez do `infEvento` do `retEvento`, a resposta real da SEFAZ, quando `evento` aparece primeiro no documento); método `firstChildElement` (escopo estrito, filho direto).
- `NfeConsultaSituacaoParserTest.java` — fixture `comProcEventoNFe` reestruturada para refletir a estrutura XML real (`evento` antes de `retEvento`) e o teste `procEventoNFe_eventoAntesDeRetEvento_naoConfundeInfEvento`.

Conferido por `grep` antes da aplicação: nenhuma referência a `ListaEventosRetorno`, `extrairConteudoEventoOriginal`, `listarEventosPorTipo`, `parseIntSeguro`, `xCorrecao`, `conteudoEventoEncontrado` ou `NfeCceClassificador`. `NfeConsultaSituacaoRetorno.java` não participa deste commit.

O defeito corrigido aqui já existia no código de cancelamento commitado em `59b92d5` — nunca havia sido corrigido até este commit. Afeta a reconciliação de cancelamento (136/573) tanto quanto a de CC-e.

### `4791572` — feat(fiscal): implementa Gate CC-e (evento 110110)

30 arquivos, 3033 inserções / 165 deleções — commit principal, aplicado sobre `8b6b19b`. Escopo completo:

- Endpoint OMS `POST /api/app/pedidos/{id}/cce`, `Idempotency-Key` obrigatório.
- `nfe_evento_sequencia` (sequência 1–20, `FOR UPDATE` + `SERIALIZABLE`) e `nfe_evento_idempotencia` (idempotência de operação, persistente) — migration V037.
- Bootstrap de sequência histórica fail-closed via Consulta Situação.
- Matriz de `cStat`: 135 registrado, 136/573 pendente confirmação, 594 rejeição estrutural terminal.
- Reconciliação do 573 validando conteúdo (`xCorrecao`) além da identidade fiscal.
- Endpoint legado `/api/fiscal/nfe/cce` desabilitado para transmissão real (HTTP 410).
- `NfeCceOrquestradorService` completo — fluxo principal, replay por Idempotency-Key, bootstrap, reserva/reabertura, reconciliação, finalização.
- `NfeCceFinalizacaoRollbackRealMySqlIT` — novo, ver seção 3.1.

Detalhamento arquitetural completo (decisões, arquivos criados/modificados) já registrado em `Relatorio_Tecnico_Diario_2026-08-12.md`, seção 4 — não repetido aqui.

---

## 5. Testes executados

| Suíte | Resultado |
|---|---|
| `NfeConsultaSituacaoParserTest` (focado, pós-split e pós-correção de javadoc) | 21/21 PASS |
| `NfeCceClassificadorTest` | 6/6 PASS |
| `NfeCancelamentoOrquestradorServiceTest` | 16/16 PASS |
| `NfeEventoServiceTest` | 15/15 PASS |
| `NfeCceOrquestradorServiceTest` | 33/33 PASS |
| `NfeCceControllerTest` | 2/2 PASS |
| `PedidoOperacaoServiceTest` | 19/19 PASS |
| `NfeCceGateConcorrenciaRealMySqlIT` (MySQL 8.4 efêmero, revalidado) | 2/2 PASS |
| **`NfeCceFinalizacaoRollbackRealMySqlIT` (novo, MySQL 8.4 efêmero)** | **1/1 PASS** |
| Regressão completa do reator (`borurio-app`+`borurio-fiscal`+`borurio-web`), antes dos commits | 578/578, 1 skip preexistente não relacionado, 0 falhas/erros |
| Regressão completa do reator, após `8b6b19b` (fix isolado) | Sem falhas/erros |

`git diff --check`: limpo em toda a janela, antes e depois dos commits.

Container MySQL efêmero (Docker, `mysql:8.4`, porta local isolada) usado nos IT reais foi removido ao final da sessão. `borurio-mysql-dev`/`borurio-mysql-hom` não foram tocados em nenhum momento. Nenhum deploy ou serviço externo de DEV/HOM/SEFAZ foi acionado nesta banca; as validações ocorreram localmente, com MySQL 8.4 efêmero quando aplicável.

---

## 6. Estado Git

```
$ git log --oneline -3
4791572 feat(fiscal): implementa Gate CC-e (evento 110110)
8b6b19b fix(fiscal): corrige extração de infEvento em procEventoNFe
59b92d5 feat(fiscal): implementa ciclo seguro de cancelamento NF-e

$ git status --short
 M docs/manual/CHECKLIST_ERP_DELIVERY.md
?? docs/report/Relatorio_Tecnico_Diario_2026-08-13.md
?? (docs/report/ — relatórios e checkpoints técnicos anteriores, fora de commit conforme prática já estabelecida)
```

Nenhum push realizado. Nenhum código alterado além do que já estava em working tree antes desta sessão de banca (a correção de javadoc e a extração de método do parser foram feitas e commitadas dentro do próprio fluxo descrito na seção 3–4). Restam para commit documental: este relatório e o checklist atualizado — separados do código, aguardando revisão.

---

## 7. Próximas etapas

1. Revisão e commit documental (checklist + este relatório) — separado dos commits de código, por decisão explícita.
2. Ajuste de estoque por empresa para o cenário solicitado pelo CC (`controleEstoqueAtivo=false`).
3. Contingência fiscal formal.
4. Regressão final consolidada e documentação (manual técnico, contrato de integração — PT-BR primeiro, depois EN).
5. Preparação e disponibilização em HOM, smoke test.
6. Nova rodada integrada com o CC.
7. Push final — bloqueado até o fechamento completo do conjunto acima.

Estoque e contingência **não foram tocados** nesta sessão — seguem pendentes, sem alteração de estado.

---

## 8. Continuação da sessão (tarde/noite) — Gate Estoque OMS

Retomada pela palavra-chave `RETOMAR BORURIO — GATE ESTOQUE OMS 13/08`, depois do fechamento documental do CC-e (commit `e2167e8`). Próximo item do backlog do CC: `POST /api/app/pedidos/{id}/emitir` não deve bloquear por falta de estoque quando a empresa não controla estoque no Borurio.

### 8.1 Auditoria — infraestrutura já resolvia o pedido, sem mudança de produção

Auditoria fria do código real (não presumida) encontrou que `controleEstoqueAtivo=false` já é lido e propagado ponta a ponta: `PedidoEmissaoService.emitir()` só chama `EstoqueService.reservarItens` quando `controlaEstoque=true`; `NfeEmissaoService.resolverCicloComEfeitos` só baixa/desfaz reserva quando `controlaEstoque=true`; `NfeEventoService` (cancelamento) só estorna quando `controlaEstoque=true`. `INSUFFICIENT_STOCK` só nasce dentro de `reservarItens` — inalcançável quando a flag está desativada. Default (`controleEstoqueAtivo=null`) resolve para `true` (fail-safe), e a migration V029 já grava `NOT NULL DEFAULT 1` — nenhuma empresa existente muda de comportamento.

**Conclusão: nenhuma alteração de regra de produção foi necessária.** A auditoria encontrou três lacunas de **prova**, não de comportamento — fechadas nesta sessão:

1. Sem teste explícito de isolamento entre duas empresas com flags diferentes.
2. Sem cobertura explícita de reemissão e de `PENDENTE_CONFIRMACAO` com estoque desativado (existiam só por inferência arquitetural).
3. Sem IT real contra MySQL provando a combinação estoque-desativado + concorrência + saldo zero.

### 8.2 Provas adicionadas

- `PedidoEmissaoServiceTest.emitir_duasEmpresasNaMesmaExecucao_naoInfluenciamEstoqueUmaDaOutra` — isolamento multiempresa, nível unitário (adequado: `controlaEstoque(empresaId)` é resolvido do zero a cada chamada, sem cache).
- `PedidoEmissaoServiceTest.emitir_reemissaoMesmoPedido_controlaEstoqueFalse_nuncaMovimentaEstoque` — duas chamadas reais de `emitir()` sobre o mesmo pedido, `verifyNoInteractions(estoqueService)` cobrindo as duas.
- `NfeEmissaoServiceTest.resolverCicloComEfeitos_pendenteConfirmacao_controlaEstoqueFalse_naoTocaEstoque` — assertion dedicada, não inferida da combinação já existente (PENDENTE_CONFIRMACAO+true).
- `NfeEmissaoEstoqueDesativadoRealMySqlIT` (novo) — MySQL 8.4 efêmero real, `EstoqueServiceImpl` **real** (não mock), produto com saldo zero, 5 chamadas concorrentes de `resolverCicloComEfeitos(AUTORIZADO, controlaEstoque=false)` sobre o mesmo ciclo: exactly-once fiscal preservado, produto/`estoque_movimento` comprovadamente intocados na tabela real.

### 8.3 Evidências de teste — Gate Estoque

| Suíte | Resultado |
|---|---|
| `PedidoEmissaoServiceTest` | 52/52 PASS |
| `NfeEmissaoServiceTest` | 26/26 PASS |
| `NfeReconciliacaoServiceTest` | 24/24 PASS |
| `NfeEventoServiceTest` | 15/15 PASS |
| `NfeCancelamentoOrquestradorServiceTest` | 16/16 PASS |
| `EstoqueServiceTest` | 11/11 PASS |
| **`NfeEmissaoEstoqueDesativadoRealMySqlIT`** (MySQL 8.4 efêmero, real) | **1/1 PASS** |

`PedidoEmissaoService`, `NfeEmissaoService`, `NfeEventoService` — **nenhum alterado**. Todas as provas acima passam contra a regra de produção já existente.

**Status: Gate Estoque tecnicamente aprovado, sem mudança de produção. Ainda não commitado.**

> **Atualização 14-08-2026:** commitado em `9e845ba` — ver seção 12.

---

## 9. Achado — `PUT /api/app/empresas/{id}`

A auditoria do Gate Estoque, ao investigar como configurar a empresa do CC, encontrou um defeito de contrato real e independente neste endpoint.

### 9.1 Contrato anterior — inconsistente, comprovado por prova executável

- O único payload documentado no projeto (`MTF-001_motor-fiscal-nfe.md` §14.3 / `ROTEIRO_ENTREGA_TIME_CHINES.md` BLOCO 5 — configurar certificado só com `certPath`/`certSenha`/`certTipo`) **já retornava HTTP 422** pelo Bean Validation (`@NotBlank` em `cnpj`/`razaoSocial`/`crt`/`uf`) — nunca alcançava o service, provado via MockMvc contra o payload documentado literalmente.
- Com esses 4 campos preenchidos, outros 4 campos `NOT NULL` no banco (`serieNfePadrao`, `indFinalPadrao`, `ativo`, `controleEstoqueAtivo`) não tinham proteção nenhuma (nem Bean Validation, nem merge) — alcançavam o mapper como `null` e produziam `SQLIntegrityConstraintViolationException`, sem handler específico, caindo no fallback genérico → **HTTP 500**. Provado isoladamente contra MySQL real.
- `Empresa` usada diretamente como corpo da requisição não distinguia "campo ausente" de "campo enviado como `null`" e permitia mass-assignment (embora `id`/`criadoEm`/`atualizadoEm` já fossem reescritos no service/mapper, então não exploráveis).

### 9.2 Decisão arquitetural — Rota B

Atualização parcial explícita, com DTO dedicado — não substituição completa (Rota A) nem PATCH novo (Rota C). Justificativa: o único uso real e documentado do endpoint (inclusive um item **crítico** do checklist de PRD — configurar o certificado A1 real) já pressupõe atualização parcial; nunca existiu, em nenhuma versão da documentação, um exemplo de payload completo.

### 9.3 Implementação — ainda NÃO commitada

- `EmpresaAtualizacaoRequest` (novo, `borurio-web/dto`) — rastreamento de presença via setters customizados (sem Lombok, sem biblioteca nova): cada setter grava seu nome num `Set<String>` ao ser chamado pelo Jackson — comportamento padrão do binding (setter só é chamado para propriedade presente no JSON, mesmo com valor `null`; nunca chamado para propriedade ausente). Provado contra o pipeline HTTP/Jackson real, não presumido.
- Campo ausente preserva o valor persistido; campo presente aplica o valor enviado.
- `null` explícito em campo obrigatório (`cnpj`, `razaoSocial`, `crt`, `uf`, `serieNfePadrao`, `indFinalPadrao`, `ativo`, `controleEstoqueAtivo`) → `BusinessException` 422, nenhuma escrita.
- `null` explícito em campo nullable (`nomeFantasia`, `ie`, endereço, `certPath`, `certSenha`, `certTipo`) → limpa de verdade.
- CNPJ diferente do persistido → 422 (`EMPRESA_CNPJ_IMUTAVEL`); mesmo CNPJ (com ou sem máscara) → aceito, idempotente, sem efeito colateral.
- `id`, `criadoEm`, `atualizadoEm` não existem no DTO — estruturalmente impossíveis de mass-assignment.
- `certSenha` nunca aparece em nenhum response (já garantido por `EmpresaResponse`, confirmado) nem em `toString()` (DTO sem Lombok `@Data`/`@ToString`); só é (re)criptografada quando o campo chega presente com valor novo — nunca reencripta um valor já criptografado que estava sendo preservado.
- `EmpresaAtualizacaoService` (novo, `borurio-web/service`) faz o merge campo a campo (explícito, sem reflection/`BeanUtils`) e reaproveita `EmpresaService.atualizar`/`EmpresaMapper.atualizar` sem alteração nenhuma.
- `EmpresaMapper.java`, `EmpresaServiceImpl.java`, `Empresa.java` e o `POST /empresas` (`salvar`) permaneceram intactos.

### 9.4 Evidências de teste — PUT Empresa

| Suíte | Resultado |
|---|---|
| `EmpresaControllerTest` (já aprovado, só +1 `@MockBean` pela mudança de construtor) | 5/5 PASS |
| `EmpresaControllerAtualizarPayloadParcialDiagnosticoTest` (reescrito — provava o defeito, agora prova o comportamento correto) | 5/5 PASS |
| `EmpresaAtualizacaoServiceTest` (novo) | 17/17 PASS |
| **`EmpresaAtualizarOmiteControleEstoqueRealMySqlIT`** (reescrito, MySQL 8.4 efêmero real) | **9/9 PASS** |
| Regressão completa (`borurio-app`+`borurio-fiscal`+`borurio-web`) | **613/613**, 1 skip preexistente não relacionado, 0 falhas/erros |

`git diff --check`: limpo. Container MySQL efêmero removido ao final da sessão — `borurio-mysql-dev`/`borurio-mysql-hom` não foram tocados em nenhum momento.

**Status: implementado, regressão completa verde. Banca arquitetural final pendente (ver seção 11). Ainda NÃO commitado.**

> **Atualização 14-08-2026:** banca arquitetural realizada (ver seção 11 e 12) — `EmpresaAtualizacaoService` mantido em `borurio-web`; lost update comprovado e corrigido com lock pessimista; commitado em `849da14`.

---

## 10. Estado Git ao final do dia (13-08-2026, encerramento)

```
$ git log --oneline -3
e2167e8 docs(fiscal): registra fechamento do Gate CC-e
4791572 feat(fiscal): implementa Gate CC-e (evento 110110)
8b6b19b fix(fiscal): corrige extração de infEvento em procEventoNFe
```

**Commitado hoje** (seções 3–4 deste relatório): `8b6b19b` (fix parser), `4791572` (Gate CC-e), `e2167e8` (documentação do fechamento do CC-e).

**Implementado e validado hoje, ainda NÃO commitado** (seções 8–9): todos os testes novos/alterados do Gate Estoque (seção 8.2) e toda a implementação da Rota B do `PUT /empresas` (seção 9.3), incluindo os dois arquivos de teste reescritos que antes eram diagnósticos. Working tree no fim do dia:

```
 M borurio-app/.../BusinessException.java
 M borurio-web/.../EmpresaController.java
 M borurio-web/.../EmpresaControllerTest.java
 M borurio-web/.../NfeEmissaoServiceTest.java
 M borurio-web/.../PedidoEmissaoServiceTest.java
?? borurio-web/.../EmpresaAtualizacaoRequest.java
?? borurio-web/.../EmpresaAtualizacaoService.java
?? borurio-web/.../EmpresaControllerAtualizarPayloadParcialDiagnosticoTest.java
?? borurio-web/.../EmpresaAtualizacaoServiceTest.java
?? borurio-web/.../EmpresaAtualizarOmiteControleEstoqueRealMySqlIT.java
?? borurio-web/.../NfeEmissaoEstoqueDesativadoRealMySqlIT.java
?? (relatórios históricos de docs/report/, fora de commit conforme prática já estabelecida)
```

`docs/manual/CHECKLIST_ERP_DELIVERY.md` já foi commitado em `e2167e8` (reflete o fechamento do CC-e) — está limpo no working tree, sem diff pendente. Ainda não reflete o Gate Estoque nem a Rota B do `PUT /empresas` (trabalho desta tarde/noite), por isso continua como pendência (ver item 5 abaixo).

Registrado explicitamente: nenhum deploy realizado; nenhum SEFAZ real acionado; nenhuma configuração da empresa do CC alterada; nenhum push realizado. As alterações de estoque e do `PUT /empresas` permanecem somente no working tree.

---

## 11. Pendências para retomada em 14/08/2026

1. Banca arquitetural final de `EmpresaAtualizacaoService` — especialmente sua localização em `borurio-web` versus uma camada em `borurio-app`, dado que concentra regras de merge, imutabilidade de CNPJ e validação de estado que possivelmente pertencem à camada de negócio de `Empresa`, e não à camada de exposição HTTP.
   > **Atualização 14-08-2026:** banca realizada — veredito é manter em `borurio-web`. Motivo: o serviço orquestra semântica específica do request HTTP parcial (presença de campos, `EmpresaAtualizacaoRequest`) e depende de `CertSenhaEncryptor` (infraestrutura de segurança da borda REST, não regra de domínio de `Empresa`); `borurio-app` não pode depender de `borurio-web` (direção de dependência única), e mover só a lógica fragmentaria uma operação pequena sem necessidade. Mesmo padrão dos demais orquestradores de `borurio-web/service` (CC-e, cancelamento, emissão). Nenhuma refatoração de camada foi aberta.
2. Avaliar e provar ou descartar o risco de lost update (sobrescrita perdida) em duas atualizações parciais concorrentes sobre a mesma empresa. Inferência a partir da arquitetura descrita, **não observada em nenhum teste até agora** — nenhum IT de concorrência real foi escrito para este cenário especificamente.
   > **Atualização 14-08-2026:** risco **comprovado** contra MySQL 8.4 real (IT dedicado, leitura sem lock + UPDATE de linha inteira perdia uma das duas alterações concorrentes) e depois **corrigido** com lock pessimista (`EmpresaMapper.buscarPorIdParaAtualizar`, `SELECT ... FOR UPDATE`) dentro de uma única transação (`TransactionTemplate`). IT final de concorrência revalidado: 4/4 (campos diferentes, mesmo campo, empresas diferentes, rollback). Ver seção 12.
3. Renomear, se aprovado, os dois arquivos de teste que ainda carregam nomes de diagnóstico/escopo antigo: `EmpresaControllerAtualizarPayloadParcialDiagnosticoTest` (não é mais diagnóstico) e `EmpresaAtualizarOmiteControleEstoqueRealMySqlIT` (testa muito mais do que omissão de `controleEstoqueAtivo` agora).
   > **Atualização 14-08-2026:** renomeados para `EmpresaControllerAtualizacaoParcialTest` e `EmpresaAtualizacaoParcialRealMySqlIT`, depois da correção do lost update ficar verde.
4. Só depois dos dois pontos acima: preparar staging e commits manuais separados (Gate Estoque e Rota B do PUT Empresa, presumivelmente como commits distintos, mesmo padrão usado no CC-e).
   > **Atualização 14-08-2026:** feito — `9e845ba` (Gate Estoque) e `849da14` (Rota B/PUT Empresa), commits distintos.
5. Atualizar `CHECKLIST_ERP_DELIVERY.md` para refletir o Gate Estoque e a Rota B do `PUT /empresas` com os SHAs reais dos commits que ainda serão feitos — deliberadamente **não feito hoje**, por decisão explícita (checklist reflete estado consolidado/versionado; o trabalho desta tarde/noite ainda não está commitado). O checklist já reflete corretamente o fechamento do CC-e (commit `e2167e8`).
   > **Atualização 14-08-2026:** checklist atualizado para v1.11 com os SHAs `9e845ba`/`849da14` (consolidação documental, ainda não commitada — ver seção 12).
6. Seguir para o próximo e último item funcional do backlog do CC: contingência fiscal formal (SVC-AN/SVC-RS/EPEC) — **não iniciado**.
   > **Atualização 14-08-2026:** continua não iniciado — próximo gate funcional após esta consolidação documental.
7. HOM (nova rodada) continua bloqueado até o fechamento completo das etapas acima.
   > **Atualização 14-08-2026:** continua bloqueado.
8. Push continua bloqueado.
   > **Atualização 14-08-2026:** continua bloqueado.

---

## 12. Fechamento — banca de 14-08-2026 (Gate Estoque + PUT Empresa)

**Commits confirmados:**
- `9e845ba` — `test(fiscal): valida emissao com controle de estoque desativado` (Gate Estoque)
- `849da14` — `feat(app): implementa atualizacao parcial segura de empresa` (Rota B/PUT Empresa, incluindo a correção do lost update)

HEAD atual: `849da14`.

**Gate Estoque (`9e845ba`):** confirma que `controleEstoqueAtivo=false` já era suportado pela lógica de produção sem nenhuma mudança em `PedidoEmissaoService`/`NfeEmissaoService`/`NfeEventoService`. Não houve remoção global da validação de estoque — empresas com controle ativo continuam bloqueando saldo insuficiente; empresas com controle desativado não reservam, não baixam, não desfazem nem estornam estoque. Isolamento multiempresa, reemissão, `PENDENTE_CONFIRMACAO` sem efeito de estoque e concorrência foram provados.

**PUT Empresa (`849da14`):** hardening independente concluído — DTO parcial específico, distinção campo omitido vs. `null` explícito, CNPJ imutável, `certSenha` protegida, merge controlado, `SELECT ... FOR UPDATE`, `TransactionTemplate`, correção do lost update, concorrência e rollback validados contra MySQL 8.4.

**Resultados exatos das suítes desta banca:**
- `EmpresaAtualizacaoServiceTest`: 17/17
- `EmpresaAtualizacaoLostUpdateRealMySqlIT` (concorrência final, pós-correção): 4/4
- `EmpresaAtualizacaoParcialRealMySqlIT`: 9/9
- `EmpresaControllerTest`: 5/5
- `EmpresaControllerAtualizacaoParcialTest`: 5/5
- Regressão final: `borurio-app` 20/20, `borurio-fiscal` 123/123 (1 skip preexistente, não relacionado), `borurio-web` 488/488 — **total 631/631, 0 falhas/erros**
- `git diff --check`: limpo
- MySQL 8.4 efêmero removido ao final; nenhum deploy ou serviço externo de DEV/HOM/SEFAZ acionado

**Fila atual:**
1. Contingência fiscal formal (SVC-AN/SVC-RS/EPEC) — próximo gate funcional, não iniciado.
2. Regressão/documentação final consolidada.
3. Preparação HOM + smoke test.
4. Nova rodada integrada com o CC.
5. Push — continua bloqueado.
