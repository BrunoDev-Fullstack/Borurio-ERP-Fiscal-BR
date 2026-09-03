# Relatório Técnico Diário — 22/06/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- HOM: UP — Flyway V028 — smoke test multi-CNPJ executado e aprovado
- DEV: não tocado nesta sessão
- Testes: suite completa 126/126 PASS — BUILD SUCCESS

---

## 1. Resumo executivo

Sessão dividida em três blocos: restauração de infraestrutura local, implementação + validação do V028 Multi-CNPJ OMS e auditoria de segurança com hardening.

**Bloco 1 — Restauração do Docker/WSL:**
Na sessão anterior o Docker Desktop havia parado de responder. A causa raiz foi identificada: uma atualização de BIOS desabilitou o `SVM Mode` (AMD Virtualization), que é o pré-requisito para o WSL2 e, por consequência, para o Docker Desktop. Após reativar `SVM Mode = Enabled` na BIOS e reiniciar, o Docker retornou com todos os containers DEV e HOM operacionais.

**Bloco 2 — Implementação V028 Multi-CNPJ OMS:**
O CC (Xiao Li) havia confirmado na sessão de 22/06 o modelo definitivo de multiempresa:

- token por cliente OMS (`codigoEmpresaOms`), não por CNPJ
- múltiplos CNPJs autorizáveis sob o mesmo token
- empresa criada automaticamente a partir do Subject X.509 do certificado — sem pré-cadastro ADMIN
- reautorização de CNPJ existente mantém o token intacto
- adição de novo CNPJ mantém o token intacto
- pedido usa `cnpjEmitente` para selecionar o certificado correto na emissão

A implementação V028 foi realizada na sessão anterior e estava completa no working tree. Este bloco foi de deploy e validação.

**Bloco 3 — Auditoria de segurança e hardening (A-03 + A-04):**
Auditoria completa do branch com 10 objetivos (arquitetura, OMS, autenticação/autorização, JwtFilter/SecurityConfig, entidades OMS, migrations V027/V028, código morto, duplicações, acoplamentos, prontidão PRD). Quatro achados validados com evidência de código; dois corrigidos:

- **A-03 (ALTO) — CORRIGIDO:** Endpoints legados `NfeEnvioController` expostos sem restrição de role. Proteção aplicada via `@PreAuthorize("hasRole('ADMIN')")` nos 4 métodos + `@EnableMethodSecurity` em `SecurityConfig`.
- **A-04 (ALTO) — CORRIGIDO:** `RateLimitInterceptor.resolveIp()` lia `X-Forwarded-For` sem validação de proxy, permitindo bypass do rate limit por qualquer cliente. Corrigido para usar exclusivamente `request.getRemoteAddr()`.
- **C-02 (CRÍTICO) — FALSO POSITIVO:** `CERT_ENCRYPTION_KEY` ausente em `application-hom.yml` era esperado — chave configurada corretamente via variável de ambiente Docker em `.env.hom:103`, padrão idêntico ao `SECURITY_JWT_SECRET`.
- **C-01 (ALTO) — PARCIALMENTE CONFIRMADO:** `emitidoEm` truncado a segundos antes do INSERT via `LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)` garante token determinístico. Risco residual baixo: divergência de timezone JVM/MySQL apenas em misconfiguration.

**Resultados desta sessão:**

1. Migration V028 aplicada em HOM (com reparo manual de estado parcial — ver seção 5)
2. HOM em V028 operacional
3. Smoke test multi-CNPJ M1–M4 e M6 aprovados
4. Três correções técnicas identificadas e aplicadas durante o smoke test
5. Auditoria de segurança — 2 findings corrigidos (A-03, A-04)
6. Suite completa: **126/126 PASS** — nenhuma regressão

Nenhum commit, push ou deploy não autorizado realizado.

---

## 2. Estado Git

```
Branch: fix/sefaz-xml-structure
Working tree: 23 arquivos modificados, 3 arquivos não rastreados
Commits realizados: nenhum nesta sessão — pendentes para commit manual
```

### Arquivos modificados (M — tracked)

#### V028 Multi-CNPJ

| Arquivo | O que mudou |
|---|---|
| `app/entity/OmsCompanyCertificate.java` | Campos `cnpj` e `empresaId` adicionados à entidade |
| `app/exception/BusinessException.java` | Novos factory methods: `cnpjNotAuthorizedForOmsClient`, `certNotFoundForCnpj`, `companyInactive` |
| `app/mapper/OmsCompanyCertificateMapper.java` | `buscarAtivoPorAuthIdECnpj(authId, cnpj)` adicionado; `desativarCertsAtivos` recebe `cnpj`; INSERT atualizado com `cnpj` e `empresa_id` |
| `app/mapper/OmsFiscalAuthorizationMapper.java` | `buscarPorSlot` reduzido de 3 para 2 parâmetros (sem `empresaId`); INSERT inclui `emitido_em` explicitamente |
| `web/auth/JwtUtil.java` | Overload `generateOmsToken(..., LocalDateTime issuedAt)` — permite reproduzir token idêntico usando `emitidoEm` do banco |
| `web/controller/app/PedidoController.java` | Injeção de `OmsCertificadoService`; validação de `cnpjEmitente` OMS na criação do pedido — fail-fast antes de persistir |
| `web/service/NfeGeracaoService.java` | `resolverPorJti` → `resolverPorJtiECnpj(jtiOms, cnpjEmitente)` — seleciona certificado pelo CNPJ correto em cenário multi-CNPJ |
| `web/service/OmsCertificadoService.java` | `resolverPorJtiECnpj(jti, cnpj)` adicionado; `cnpjAutorizadoParaJti(jti, cnpj)` adicionado (check leve sem lançar exceção); `resolverPorJti` marcado `@Deprecated` |
| `web/service/OmsFiscalAuthorizationService.java` | Reescrito para quatro cenários A/B/C/D; auto-criação de empresa; `emitidoEm` truncado a segundos no INSERT para garantir token determinístico; resposta retorna `empresaId` da empresa âncora |
| `web/service/PedidoEmissaoService.java` | `resolverEmpresaParaEmissao` usa `cnpjEmitente` do pedido para selecionar a empresa correta em contexto OMS multi-CNPJ |
| `web/test/controller/PedidoControllerTest.java` | `@MockBean OmsCertificadoService` adicionado — `PedidoController` passou a depender do serviço nesta sessão |
| `web/test/service/OmsFiscalAuthorizationServiceTest.java` | Stubs de `generateOmsToken` atualizados de 4 para 5 parâmetros; 14 testes em 8 `@Nested` cobrindo cenários A/B/C/D e validações |

#### Hardening A-03 + A-04

| Arquivo | O que mudou |
|---|---|
| `web/config/RateLimitInterceptor.java` | A-04: `resolveIp()` removida leitura de `X-Forwarded-For`; usa apenas `request.getRemoteAddr()` |
| `web/config/SecurityConfig.java` | A-03: `@EnableMethodSecurity` adicionado — habilita `@PreAuthorize` em todo o contexto web |
| `web/controller/fiscal/NfeEnvioController.java` | A-03: `@PreAuthorize("hasRole('ADMIN')")` nos 4 endpoints deprecated (`/enviar`, `/gerar`, `/status`, `/{chave}`); `@Tag` atualizado para indicar requisito ADMIN |

#### Documentação

| Arquivo | O que mudou |
|---|---|
| `docs/manual/INTEGRATION_CONTRACT_PT-BR.md` | v1.6 → v1.7 — seções multi-CNPJ: 3.3, 4, 6.3, 8.2a, 9.1b, 10, 11 |
| `docs/manual/INTEGRATION_CONTRACT_EN.md` | v1.6 → v1.7 — mesmos conteúdos, inglês |
| `docs/manual/CHECKLIST_ERP_DELIVERY.md` | Atualizado para V028 |
| `docs/manual/CHECKLIST_OMS_ONBOARDING.md` | Bloco 0B smoke test multi-CNPJ adicionado |
| `docs/manual/FAQ_SMOKE_TEST_OMS.md` | Perguntas e respostas para cenários V028 |
| `docs/manual/MTF-001_motor-fiscal-nfe.md` | v2.6 — seções 3.1/3.2/9.5/10.6/11.3/16 atualizadas para V028 |
| `docs/manual/MTF-001_motor-fiscal-nfe_EN.md` | v2.6 — mesmos conteúdos, inglês |
| `docs/manual/ROTEIRO_ENTREGA_TIME_CHINES.md` | v1.2 — Bloco 0B e bloqueadores V028 |

### Arquivos novos não rastreados (?? — untracked)

| Arquivo | Descrição |
|---|---|
| `web/src/main/resources/sql/migration/V028__oms_multiempresa.sql` | Migration Flyway — altera `oms_fiscal_authorization` (slot por cliente OMS sem `empresa_id`) e `oms_company_certificate` (adiciona `cnpj`, `empresa_id`, coluna gerada `cnpj_ativo_unico`); inclui pré-condição, verificação pós-execução e rollback manual documentados |
| `web/src/test/java/.../NfeEnvioControllerTest.java` | 12 testes A-03: 401 anônimo, 403 não-ADMIN, 200 ADMIN para cada um dos 4 endpoints deprecated; usa `@Import(SecurityConfig.class)` para ativar method security no `@WebMvcTest` |
| `docs/report/Relatorio_Tecnico_2026-06-22.md` | Este relatório |

---

## 3. Arquitetura V028 — modelo multiempresa OMS

### 3.1 Quatro cenários de autorização

| Cenário | Condição | Comportamento |
|---|---|---|
| A — Novo cliente OMS | `codigoEmpresaOms` nunca visto | Cria slot + empresa âncora + insere cert + emite **novo token** |
| B — Mesmo CNPJ, mesmo cert | Slot existente + CNPJ já autorizado + thumbprint idêntico | Nenhuma alteração no banco — **retorna token existente** |
| C — Mesmo CNPJ, cert novo | Slot existente + CNPJ já autorizado + thumbprint diferente | Desativa cert anterior do CNPJ + insere novo + atualiza `tokenExpiraEm` — **mantém JTI** |
| D — CNPJ novo, cliente existente | Slot existente + CNPJ inédito | Insere cert para o novo CNPJ — **retorna token existente sem alteração** |

### 3.2 Token determinístico — decisão técnica

**Problema:** O método `generateOmsToken` chamava `.setIssuedAt(new Date())` em toda invocação. Em cenários B/C/D, onde o JTI é reutilizado, o token string resultante diferia porque o campo `iat` (milissegundos) mudava a cada chamada.

**Raiz do problema:** `new Date()` tem precisão de milissegundos; `CURRENT_TIMESTAMP` do MySQL tem precisão de segundos. O `emitidoEm` recuperado do banco para uso em B/C/D diferia em frações do `iat` original gerado em A.

**Solução:** No caso A, `emitidoEm` é definido em Java como `LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)` antes do INSERT — eliminando a diferença de precisão. O mesmo valor é gravado no banco (`emitido_em` incluído no INSERT). Em B/C/D, `auth.getEmitidoEm()` (lido do banco) reproduz exatamente o mesmo `iat` que foi usado em A, garantindo token string idêntico.

### 3.3 `empresaId` na resposta — empresa âncora

O campo `empresaId` na resposta do endpoint de autorização identifica sempre a empresa âncora — a empresa criada/vinculada na **primeira** autorização do cliente OMS. Em cenários C e D, onde a empresa do CNPJ atual pode ter um id diferente, o `auth.getEmpresaId()` (empresa âncora) é usado na resposta, não `empresa.getId()` (empresa do CNPJ sendo autorizado no momento). O campo `cnpj` e `razaoSocial` na resposta referem-se ao CNPJ sendo autorizado na chamada atual.

### 3.4 Validação de CNPJ OMS na criação do pedido — fail-fast

Antes desta sessão, a validação `CNPJ_NOT_AUTHORIZED` só ocorria em `POST /api/app/pedidos/{id}/emitir`. Um pedido com `cnpjEmitente` inválido era criado (RASCUNHO) e só rejeitado na emissão.

O contrato (seção 9.1b, M6) especifica que `POST /api/app/pedidos` deve retornar HTTP 403 para CNPJ não autorizado. A validação foi movida para a criação: `PedidoController` chama `omsCertificadoService.cnpjAutorizadoParaJti(jti, cnpj)` — uma consulta leve que não carrega o certificado — antes de persistir o pedido.

---

## 4. Deploy HOM — V028

### 4.1 Pré-condição verificada

Duplicatas em `(integrator_id, codigo_oms)` que impediriam a nova `UNIQUE KEY`: **0 linhas** — limpo para aplicar.

### 4.2 Estado parcial detectado

O Flyway detectou falha prévia (`success=0` para V028 na `flyway_schema_history`). A migration havia sido tentada em sessão anterior e havia parcialmente executado:

| Passo | Estado ao chegar |
|---|---|
| 1a–1b: `oms_fiscal_authorization` — nova UNIQUE KEY | ✓ Aplicado |
| 2a–2b: DROP de index e coluna gerada antiga | ✓ Aplicado |
| 2c–2g: ADD `cnpj`, `empresa_id`, coluna gerada, constraints | ✗ Não executado |

### 4.3 Reparo executado

Passos 2c–2g executados manualmente via `docker exec`. Backfill (passo 2d) processou 1 linha — certificado existente (empresa JCHO). Todos os campos `cnpj` e `empresa_id` preenchidos corretamente. `flyway_schema_history` atualizado para `success=1` via UPDATE direto.

### 4.4 Resultado do deploy

```
Flyway validated 28 migrations
Current version: 028
Schema up to date. No migration necessary.
Started Application in 6.187s
Health: UP
```

---

## 5. Smoke test multi-CNPJ — resultado

Smoke test executado conforme seção 9.1b do contrato v1.7.

| # | Request | Critério | Resultado |
|---|---|---|---|
| M1 | `POST /api/integration/fiscal-authorizations` — CNPJ1 (cliente SMOKE-001) | HTTP 200 · token emitido · empresa auto-criada | **PASS** |
| M2 | `POST /api/integration/fiscal-authorizations` — CNPJ2 (mesmo `codigoEmpresaOms`) | HTTP 200 · **token string idêntico ao M1** · empresaId = âncora | **PASS ✓ CRITÉRIO PRINCIPAL** |
| M3 | `POST /api/integration/fiscal-authorizations` — CNPJ1, mesmo cert reenviado (cenário B) | HTTP 200 · token string idêntico ao M1 · sem alteração no banco | **PASS** |
| M4 | `POST /api/app/pedidos` com `cnpjEmitente = CNPJ2` | HTTP 200 · `status = RASCUNHO` · `cnpjEmitente = 12345678000195` preservado | **PASS** |
| M5 | `POST /api/app/pedidos/{id}/emitir` com cert do CNPJ2 | Cert autoassinado inválido para SEFAZ-HOM | **SKIPPED** — executar com cert A1 real |
| M6 | `POST /api/app/pedidos` com `cnpjEmitente` não autorizado | HTTP 403 · `errorCode: CNPJ_NOT_AUTHORIZED` | **PASS** |

Os certs de smoke test (autoassinados via `keytool`, CNPJs sintéticos, senhas de teste) foram usados apenas em memória e no ambiente HOM local — nenhum dado sensível exposto.

---

## 6. Testes locais

| Módulo | Testes | Status |
|---|---|---|
| `borurio-core` | — | BUILD SUCCESS |
| `borurio-app` | — | BUILD SUCCESS |
| `borurio-fiscal` | 33 | 33/33 PASS |
| `borurio-web` | 93 | 93/93 PASS |
| **Total** | **126** | **126/126 PASS** |

Regressão: nenhuma.

### Novos/atualizados: `OmsFiscalAuthorizationServiceTest`

| Classe aninhada | Testes | Cenários cobertos |
|---|---|---|
| `PrimeiraAutorizacaoTests` | 1 | Caso A com empresa já existente |
| `EmpresaTests` | 2 | Caso A empresa inexistente (auto-cria) + empresa inativa |
| `NovoCnpjMesmoClienteOmsTests` | 1 | Caso D — novo CNPJ; token mantido; cert inserido |
| `MesmoCertificadoTests` | 1 | Caso B — mesmo cert; nenhuma alteração |
| `CertificadoNovoMesmoCnpjTests` | 1 | Caso C — novo cert; JTI mantido; cert anterior desativado |
| `CertificadoValidacaoTests` | 4 | Base64 inválido; senha errada; cert expirado; CNPJ divergente |
| `ApiKeyTests` | 2 | API Key ausente; API Key inválida |
| `CnpjNaoAutorizadoTests` | 2 | CNPJ não autorizado (novo CNPJ antes de autorizar via M1) |

### Novo: `NfeEnvioControllerTest` (A-03)

| Classe aninhada | Testes | Cenários cobertos |
|---|---|---|
| `Gerar` | 3 | 401 anônimo · 403 não-ADMIN · 200 ADMIN |
| `Enviar` | 3 | 401 anônimo · 403 não-ADMIN · 200 ADMIN |
| `Status` | 3 | 401 anônimo · 403 não-ADMIN · 200 ADMIN |
| `ConsultarChave` | 3 | 401 anônimo · 403 não-ADMIN · 200 ADMIN |

> `@Import(SecurityConfig.class)` é obrigatório no `@WebMvcTest`: sem ele, Spring usa auto-config default sem `@EnableMethodSecurity` e sem o CSRF disable do nosso config, fazendo os testes retornarem 200 onde esperavam 403.

---

## 7. Auditoria de segurança — achados e resoluções

Auditoria completa da branch com 10 objetivos: arquitetura real, OMS Multi-CNPJ, autenticação/autorização, JwtFilter/JwtUtil/SecurityConfig, entidades OMS, migrations V027/V028, código morto, duplicações, acoplamentos indevidos e prontidão PRD. Resultado geral: **sistema pronto para HOM; 2 achados ALTO corrigidos antes do PR.**

### Achados classificados

| ID | Severidade | Descrição | Resolução |
|---|---|---|---|
| C-01 | ALTO | `emitidoEm` poderia divergir entre Java e MySQL por diferença de precisão (ms vs s) | **PARCIALMENTE CONFIRMADO** — truncamento explícito `ChronoUnit.SECONDS` antes do INSERT já implementado; risco residual de timezone JVM/MySQL muito baixo |
| C-02 | CRÍTICO | `CERT_ENCRYPTION_KEY` ausente em `application-hom.yml` | **FALSO POSITIVO** — chave configurada em `docker/env/.env.hom:103` via variável de ambiente Docker, padrão idêntico ao `SECURITY_JWT_SECRET` |
| A-03 | ALTO | 4 endpoints legados `NfeEnvioController` sem restrição de role — qualquer usuário autenticado (incluindo tokens OMS) poderia acessar | **CORRIGIDO** — `@PreAuthorize("hasRole('ADMIN')")` nos 4 métodos + `@EnableMethodSecurity` em `SecurityConfig` + 12 testes cobrindo 401/403/200 |
| A-04 | ALTO | `RateLimitInterceptor.resolveIp()` lia `X-Forwarded-For` sem validar origem do proxy, permitindo que qualquer cliente forjasse o header e bypassasse o rate limit | **CORRIGIDO** — removida leitura do `X-Forwarded-For`; usa apenas `request.getRemoteAddr()` |

### Nota sobre C-02 — padrão de segredo por Docker env var

A chave `CERT_ENCRYPTION_KEY` está em `docker/env/.env.hom` (fora do controle de versão, não em `application-hom.yml`). Este é o padrão correto: segredos nunca em YAML, sempre em variável de ambiente injetada no container. O Spring Boot resolve via relaxed binding: `CERT_ENCRYPTION_KEY` → `cert.encryption.key` → `@Value("${cert.encryption.key:}")`.

---

## 8. Documentação atualizada

| Documento | Versão antes | Versão após | Mudanças principais |
|---|---|---|---|
| `INTEGRATION_CONTRACT_PT-BR.md` | 1.6 | **1.7** | Seção 3.3 multi-CNPJ; seção 4 contexto OMS; 6.3 `cnpjEmitente`; 8.2a empresa âncora; 9.1b smoke test M1–M6; erros `CNPJ_NOT_AUTHORIZED`, `CERT_NOT_FOUND_FOR_CNPJ`, `COMPANY_INACTIVE`; changelog |
| `INTEGRATION_CONTRACT_EN.md` | 1.6 | **1.7** | Mesmos conteúdos em inglês |
| `CHECKLIST_ERP_DELIVERY.md` | 1.x | **1.7** | Itens V028 adicionados |
| `CHECKLIST_OMS_ONBOARDING.md` | 1.x | **1.7** | Bloco 0B — smoke test multi-CNPJ M1–M6 |
| `FAQ_SMOKE_TEST_OMS.md` | — | **1.0** | FAQ para cenários V028 durante onboarding |
| `MTF-001_motor-fiscal-nfe.md` | 2.5 | **2.6** | Seções 3.1/3.2/9.5/10.6/11.3/16 — V028 |
| `MTF-001_motor-fiscal-nfe_EN.md` | 2.5 | **2.6** | Mesmos conteúdos, inglês |
| `ROTEIRO_ENTREGA_TIME_CHINES.md` | 1.1 | **1.2** | Bloco 0 atualizado; bloqueadores V028 |

---

## 9. Posição do backlog

| ID | Item | Status |
|---|---|---|
| **V028** | **Multi-CNPJ OMS — deploy HOM + smoke test** | **FECHADO — 22/06/2026** |
| **A-03** | **NfeEnvioController deprecated — acesso sem role** | **FECHADO — 22/06/2026** |
| **A-04** | **RateLimitInterceptor — X-Forwarded-For bypass** | **FECHADO — 22/06/2026** |
| P1.1 | RateLimitInterceptor: memory leak de IPs inativos (ConcurrentHashMap cresce sem TTL) | Aberto |
| P1.2 | Redis configurado em PRD sem implementação real | Aberto |
| P1.3 | MinIO no compose sem uso nos services | Aberto |
| P1.4 | Backfill EmpresaMapper sem guard de idempotência | Aberto |
| P1.5 | NfeEnvioController deprecated ainda no Swagger (agora com ADMIN obrigatório — risco reduzido) | Parcialmente mitigado |
| P1.6 | NfeGeracaoService em borurio-web (lógica fiscal fora de borurio-fiscal) | Aberto |
| P1.7 | borurio-app declara spring-boot-starter-web sem necessidade | Aberto |
| P1.8 | borurio-fiscal depende de spring-security-core só para log de auditoria | Aberto |
| P1.9 | CXF só para NFeStatusServico | Aberto |
| P1.10 | Retry Resilience4j não confirmado em `transmitirXml()` | Aberto |
| P1.11 | Cache de certificados sem validação de `getNotAfter()` | Aberto |
| P1.12 | Reforma Tributária IBS/CBS — blocos ausentes no NfeXmlBuilder | Aberto |

---

## 10. Segurança e cuidados respeitados

| Regra | Status |
|---|---|
| Token JWT nunca exibido | OK |
| API Key de smoke (`SMOKE-TEST-KEY-2026`) usada apenas em HOM local para este teste | OK |
| API Key de produção do CC nunca exibida — hash no banco, plaintext não recuperável | OK |
| Certificados A1 reais (JCHO) não manipulados nesta sessão | OK |
| Senhas de certificado não gravadas em arquivo local | OK |
| Certs autoassinados de smoke usados apenas no ambiente HOM local | OK |
| PRD não tocado | OK |
| Nenhuma chamada à SEFAZ realizada (M5 skipped) | OK |
| Commits não realizados — aguardando revisão e commit manual | OK |
| Push não executado | OK |

---

## 11. Pendências para a próxima sessão

| Prioridade | Ação |
|---|---|
| **1** | Commit manual por blocos da branch `fix/sefaz-xml-structure` (ver blocos sugeridos no final deste relatório) |
| **2** | Atualizar PR #2 com o novo HEAD (pós-V028 + A-03/A-04) |
| **3** | Enviar mensagem ao CC informando que multi-CNPJ está disponível em HOM — rascunho pronto, aguarda revisão e envio manual |
| **4** | M5 smoke test com cert A1 real (quando Bless disponibilizar cert do segundo CNPJ) |

---

## 12. Ponto de retomada

| Campo | Valor |
|---|---|
| Branch | `fix/sefaz-xml-structure` |
| Último commit de código | `94cfb38` — docs(integration): corrige resposta do contrato OMS 1.6.1 |
| Working tree | 23 modificados + 3 não rastreados — commits pendentes |
| HOM | UP — Flyway V028 — smoke test aprovado |
| Suite | 126/126 PASS — BUILD SUCCESS |
| CC/Xiao Li | Aguardando mensagem de disponibilidade multi-CNPJ em HOM — enviar após commits e atualização do PR |
| M5 smoke test | Pendente cert A1 real do segundo CNPJ — não bloqueante para commits |
| Auditoria | A-03 e A-04 corrigidos e testados; C-01 e C-02 revisados e documentados |

---

*Relatório gerado em 22/06/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
