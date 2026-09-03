# Relatório Técnico Diário — 10/07/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- HOM: UP — cadastro da empresa 8 corrigido em tempo real via API
- Túnel: Cloudflare Quick Tunnel efêmero (`*.trycloudflare.com`) — subido e derrubado dentro da sessão
- Testes: suite completa **190/190 PASS** — BUILD SUCCESS (borurio-app + borurio-fiscal + borurio-web)

---

## 1. Resumo executivo

Dia dividido em quatro blocos: retomada do estoque opcional por empresa (trabalho de sessão anterior, validado hoje com o CC ao vivo), diagnóstico e correção de uma rejeição real da SEFAZ em homologação, implementação de três melhorias solicitadas pelo CC durante o teste, e revisão de código completa com correção dos achados.

**Bloco 1 — Retomada e teste ao vivo com o CC:**
Controle de estoque opcional por empresa (commit `936e771`, sessão anterior) validado hoje em teste ao vivo com o CC (Xiao Li). Túnel Cloudflare subido, `/actuator/health` confirmado, URL e API Key repassadas. CC testou o cenário de estoque zero — funcionou como esperado.

**Bloco 2 — Diagnóstico de rejeição real da SEFAZ (cStat=225):**
Durante o teste ao vivo, o CC recebeu `cStat=225` ("Rejeição: Falha no Schema XML do lote de NFe") ao emitir para a empresa "J ZHENG BIJOUTERIAS" (CNPJ 22418179000134, auto-criada via autorização OMS). Investigação do XML de envio revelou a causa: o bloco `enderEmit` (endereço do emitente) estava incompleto — só continha `UF`/`cPais`/`xPais`, enquanto o `enderDest` (destinatário) estava completo. Causa raiz confirmada no código: empresas auto-criadas via certificado A1 (`OmsFiscalAuthorizationService.localizarOuCriarEmpresa()`) recebem apenas CNPJ, razão social e UF — o certificado não carrega endereço.

Esse achado é distinto do diagnóstico histórico de cStat=225 (divergência de processador SEFAZ-SP, documentado em 08-05-2026) — mesmo `xMotivo`, causa completamente diferente (dado de cadastro, não limitação de ambiente).

**Correção aplicada:** endereço da empresa 8 completado via `PUT /api/app/empresas/{id}` (logradouro=PRATES, número=447, bairro=BOM RETIRO, município=São Paulo, código IBGE=3550308, CEP=01121000), com dados fornecidos pelo CC. Confirmado via GET que persistiu corretamente, sem tocar em nenhum outro campo.

**Bloco 3 — Três melhorias solicitadas pelo CC (implementadas e testadas):**

O CC propôs três melhorias na integração durante a homologação:

1. **Reemissão de pedidos rejeitados/com erro** — `PedidoEmissaoService.emitir()` passa a aceitar `REJEITADO` e `ERRO` além de `RASCUNHO` (`STATUS_EMISSIVEIS`). Reemitir usa o mesmo `pedidoId` — cada tentativa gera `nNF`/`chaveNfe` novos via `NfeSequenciaService`, sem risco de duplicidade na SEFAZ.
2. **Endereço do emitente via criação do pedido** — `POST /api/app/pedidos` passa a aceitar 6 campos opcionais (`emitLogradouro`, `emitNumero`, `emitBairro`, `emitCodigoMunicipio`, `emitMunicipio`, `emitCep`). Quando o cadastro da empresa está incompleto, o sistema completa automaticamente **só os campos ausentes** — nunca sobrescreve endereço já cadastrado.
3. **Padronização de códigos de erro** — novo campo `retryable` (booleano) em todo envelope de erro da API, não só nos de negócio. Novos `errorCode`: `EMITTER_ADDRESS_INCOMPLETE` (bloqueia `/emitir` antes de chamar a SEFAZ), `SEFAZ_REJECTED` (expõe `cStat`/`xMotivo` estruturados, HTTP 422 em vez do HTTP 200 anterior), `SEFAZ_TIMEOUT`/`SEFAZ_UNAVAILABLE` (falhas de rede, retryable=true), `XML_SCHEMA_INVALID`.

Cada melhoria implementada com testes dedicados, em blocos isolados para commit em separado (working tree ainda não commitado — ver seção 2).

**Bloco 4 — Revisão de código e correções:**
Revisão completa via `/code-review` (8 agentes de busca em paralelo — correção linha-a-linha, comportamento removido, cross-file, reuso, simplificação, eficiência, altitude, convenções — seguida de 9 verificações independentes). Resultado: 6 achados confirmados + 1 plausível. Corrigidos os 4 mais críticos:

1. `PedidoEmissaoService` zerava `chaveNfe` ao marcar um pedido como `ERRO`, mesmo quando já existia uma chave real de uma tentativa anterior (retry após `REJEITADO`) — corrigido para preservar a chave existente.
2. `PedidoController.criar()` atualizava o cadastro da empresa **antes** de criar o pedido, sem transação compartilhada — se a criação do pedido falhasse depois, a empresa ficava mutada permanentemente. Corrigido: atualização do endereço agora só acontece depois que o pedido é criado com sucesso.
3. Fallback genérico do `GlobalExceptionHandler` (aplica-se a toda a API, não só NF-e) marcava `retryable=true` para qualquer exceção não mapeada — corrigido para `false` (padrão seguro).
4. `SEFAZ_REJECTED` estava marcado `retryable=true`, contradizendo o próprio critério do requisito (rejeição da SEFAZ geralmente é dado incorreto, não falha transitória) — corrigido para `false`, consistente com `EMITTER_ADDRESS_INCOMPLETE`/`XML_SCHEMA_INVALID`.

Três achados de menor severidade (duplicação de classificação de erro de rede entre módulos, duplicação da checagem de endereço completo, novo modo de falha em `buscarPorCnpj` por CNPJ divergente) registrados mas não corrigidos — ficam como pendência.

**Resultados desta sessão:**

1. Empresa 8 (J ZHENG BIJOUTERIAS) com cadastro de endereço completo em HOM
2. 3 melhorias solicitadas pelo CC implementadas e testadas
3. Revisão de código completa — 4 correções aplicadas
4. Suite completa: **190/190 PASS** (partindo de 126/126 no início do dia)
5. Documentação técnica revisada e atualizada em todos os 6 documentos de `docs/manual/` — contrato de integração v1.9, manual técnico v2.8

Nenhum commit, push ou deploy em HOM fora do combinado. Cadastro da empresa 8 foi a única alteração de dado em HOM, feita via API com senha de admin fornecida diretamente no terminal (não salva em arquivo nem memória).

---

## 2. Estado Git

```
Branch: fix/sefaz-xml-structure
Working tree: 18 arquivos modificados, 3 arquivos não rastreados
Commits realizados: nenhum nesta sessão — separar em 3 blocos ao commitar (ver seção 6)
Último commit: 936e771 (sessão anterior)
```

### Arquivos modificados — Requisitos 2/3/4

| Arquivo | O que mudou |
|---|---|
| `app/entity/Pedido.java` | 6 campos transientes de endereço do emitente (`emitLogradouro`...`emitCep`), não persistidos |
| `app/exception/BusinessException.java` | Campo `retryable` + `data` estruturado; novos factories: `emitterAddressIncomplete`, `sefazRejected`, `sefazTimeout`, `sefazUnavailable`, `xmlSchemaInvalid` |
| `fiscal/utils/XsdValidator.java` | Lança `XmlSchemaValidationException` (novo, unchecked) em vez de `Exception` genérica para falhas de schema |
| `fiscal/exception/XmlSchemaValidationException.java` | Novo arquivo |
| `web/controller/app/PedidoController.java` | `atualizarEnderecoEmitenteSeNecessario()` — completa endereço da empresa após criação do pedido (reordenado na correção); doc do `/emitir` atualizada |
| `web/exception/GlobalExceptionHandler.java` | Campo `retryable` em todo envelope de erro; fallback genérico com `retryable=false` (corrigido) |
| `web/service/NfeGeracaoService.java` | `validarEnderecoEmitente()` — bloqueia antes de chamar a SEFAZ se endereço incompleto |
| `web/service/PedidoEmissaoService.java` | `STATUS_EMISSIVEIS` (RASCUNHO/REJEITADO/ERRO); `traduzirFalhaTransmissao()`; lança `sefazRejected` em vez de retornar 200; preserva `chaveNfe` existente no catch (corrigido) |
| `web/test/controller/PedidoControllerTest.java` | +3 testes (endereço do emitente via pedido) |
| `web/test/service/PedidoEmissaoServiceTest.java` | +5 testes (reemissão REJEITADO/ERRO, retryable) |
| `web/test/exception/GlobalExceptionHandlerTest.java` | Novo arquivo — 8 testes |
| `web/test/service/NfeGeracaoServiceTest.java` | Novo arquivo — 1 teste (endereço incompleto) |

### Documentação atualizada

| Arquivo | Versão | Mudança |
|---|---|---|
| `docs/manual/INTEGRATION_CONTRACT_PT-BR.md` | 1.7 → 1.9 | Reemissão, endereço emitente, errorCode/retryable, seção 9.3 corrigida, smoke test 9.1c |
| `docs/manual/INTEGRATION_CONTRACT_EN.md` | 1.7 → 1.9 | Espelha PT-BR |
| `docs/manual/MTF-001_motor-fiscal-nfe.md` | 2.7 → 2.8 | Fluxo de emissão atualizado, seção 13.1 com causa 2 do cStat=225 |
| `docs/manual/MTF-001_motor-fiscal-nfe_EN.md` | 2.7 → 2.8 | Espelha PT-BR |
| `docs/manual/CHECKLIST_OMS_ONBOARDING.md` | 1.7 → 1.9 | Bloco 4 (emit*), Bloco 5/6/7 corrigidos (cStat=225, reemissão) |
| `docs/manual/CHECKLIST_ERP_DELIVERY.md` | 1.3 → 1.4 | Itens de hoje, seção 12 corrigida |
| `docs/manual/FAQ_SMOKE_TEST_OMS.md` | 1.3 → 1.4 | Q4 reescrita, Q9 com novos códigos, Q21–Q24 novas |
| `docs/manual/ROTEIRO_ENTREGA_TIME_CHINES.md` | 1.3 → 1.4 | Nota sobre túnel efêmero vs. nomeado; itens v1.9 no Bloco 3/4 |

**Inconsistência encontrada e corrigida na revisão documental:** múltiplos documentos descreviam `cStat=225` como exclusivamente "limitação do ambiente HOM-SP" que retornava HTTP 200 com o pedido em `AGUARDANDO` — essa segunda parte já estava logicamente incorreta antes de hoje (cStat≥200 sempre resultou em `REJEITADO`, nunca `AGUARDANDO`, mesmo antes da v1.9). Corrigido em todos os documentos, com as duas causas (ambiente vs. dado) agora documentadas lado a lado.

---

## 3. Diagnóstico — cStat=225 (causa 2: cadastro incompleto)

### XML comparado

```
enderEmit (emitente) — INCOMPLETO:
  <UF>SP</UF><cPais>1058</cPais><xPais>Brasil</xPais>

enderDest (destinatário) — completo:
  <xLgr>...</xLgr><nro>...</nro><xBairro>...</xBairro>
  <cMun>...</cMun><xMun>...</xMun><UF>SP</UF><CEP>...</CEP>
  <cPais>1058</cPais><xPais>Brasil</xPais>
```

### Causa raiz
`OmsFiscalAuthorizationService.localizarOuCriarEmpresa()` (linha ~175-194) auto-cria a empresa usando apenas CNPJ, razão social (do Subject X.509) e UF — nenhum outro campo de endereço é extraído do certificado A1, porque o certificado não carrega esse dado.

### Correção de dado (imediata)
```
PUT /api/app/empresas/8
{ logradouro: "PRATES", numero: "447", bairro: "BOM RETIRO",
  municipio: "São Paulo", codigoMunicipio: "3550308", cep: "01121000" }
```
Confirmado via GET subsequente — persistido corretamente, nenhum outro campo alterado.

### Correção estrutural (código, v1.9)
`NfeGeracaoService.validarEnderecoEmitente()` — nova checagem antes de montar/transmitir o XML. Se a empresa resolvida tiver qualquer um dos 6 campos de endereço ausente, lança `BusinessException.emitterAddressIncomplete()` (HTTP 422, `retryable=false`) sem chamar a SEFAZ.

---

## 4. Revisão de código — achados e resoluções

Revisão via skill `/code-review` em nível "high effort": 8 agentes finder em paralelo + 9 verificações independentes (1 voto, recall-biased).

| # | Achado | Severidade | Veredicto | Resolução |
|---|---|---|---|---|
| 1 | `chaveNfe` zerada no catch de `emitir()` mesmo com chave real de tentativa anterior | Alta | CONFIRMADO | Corrigido — preserva `pedido.getChaveNfe()` |
| 2 | Empresa mutada antes de `pedidoService.criar()`, sem rollback | Alta | CONFIRMADO | Corrigido — reordenado para depois do sucesso |
| 3 | Fallback global do `GlobalExceptionHandler` com `retryable=true` para toda a API | Alta | CONFIRMADO | Corrigido — `retryable=false` |
| 4 | `SEFAZ_REJECTED` com `retryable=true` contradiz o próprio critério do requisito | Média-alta | CONFIRMADO | Corrigido — `retryable=false` |
| 5 | Classificação de erro de rede duplicada entre `PedidoEmissaoService` (web) e `SefazRetryConfig` (fiscal) | Média | CONFIRMADO | Não corrigido — pendência |
| 6 | Checagem de endereço completo duplicada entre `PedidoController` e `NfeGeracaoService`, sem `Empresa.enderecoCompleto()` | Média | CONFIRMADO | Não corrigido — pendência |
| 7 | `buscarPorCnpj` pode lançar HTTP 400 se CNPJ da empresa divergir do certificado OMS (edição manual pós-autorização) | Baixa-média | PLAUSÍVEL | Não corrigido — pendência |

Dois candidatos adicionais (checagem de UF ausente na validação de endereço; risco de regressão para empresas legadas sem endereço) foram **refutados** na verificação — já protegidos por validações existentes no código (XSD oficial já bloqueava esses casos antes mesmo desta mudança).

Suite completa após as 4 correções: **190/190 PASS**.

---

## 5. Segurança e cuidados respeitados

- Senha do admin fornecida diretamente no terminal para a atualização de endereço via API — nunca salva em arquivo ou memória.
- Arquivos temporários contendo token/senha apagados imediatamente após uso.
- Nenhum commit, push ou deploy em HOM fora do escopo combinado.
- Nenhuma alteração de código nesta etapa de documentação (revisão documental pura, conforme solicitado).

---

## 6. Pendências para a próxima sessão

1. Commit em blocos isolados dos 3 requisitos — sugestão de mensagens:
   - `feat(oms): permitir reemissao de pedidos rejeitados e com erro`
   - `feat(oms): atualizar cadastro do emitente via criacao de pedido`
   - `feat(oms): padronizar codigos de erro da integracao fiscal`
2. Commit da documentação atualizada (separado ou junto).
3. Achados 5, 6 e 7 da revisão de código — decidir se e quando corrigir.
4. Rotacionar a senha do admin usada nesta sessão (exposta em texto na conversa, prática padrão do projeto).
5. Validar com o CC se as 3 melhorias resolveram o fluxo de homologação por completo.

---

## 7. Ponto de retomada

Ambiente HOM no ar, cadastro da empresa 8 corrigido, código e documentação prontos para commit. Working tree com 18 arquivos modificados + 3 novos, todos revisados e testados (190/190). Próxima sessão: commits (3 blocos + docs) e resposta do CC sobre a validação final.
