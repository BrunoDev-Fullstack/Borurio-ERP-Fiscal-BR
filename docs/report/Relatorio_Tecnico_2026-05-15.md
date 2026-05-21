# Relatório Técnico Diário — 15/05/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- Testes automatizados borurio-web: **57 / 0 falhas**
- Testes automatizados borurio-fiscal: **32 / 0 falhas / 1 skipped**
- Total consolidado: **89 testes passando + 1 skip esperado**

---

## 1. Objetivo do dia

Dois eixos de trabalho executados em sequência:

1. **Cobertura de testes de controllers** — fechar os 8 arquivos de teste que estavam ausentes, elevando a suíte borurio-web de 30 para 57 testes.
2. **Correções de qualidade B-01 a B-04** — quatro itens técnicos identificados na análise da branch: branding, environment dinâmico, credenciais hardcoded e tabela de tradução no contrato EN.
3. **Sincronização de documentação** — MTF-001 (PT-BR e EN) e contratos trazidos para o estado real do código, corrigindo afirmações de "pendente" que na verdade já estavam implementadas.

---

## 2. Atividades executadas

### Bloco B — Correções de qualidade

#### B-01 — Branding banner.txt
- **Arquivo**: `borurio-web/src/main/resources/banner.txt`
- **Correção**: imprimia "JCHO ERP" no startup → agora imprime "BORURIO ERP FISCAL BR"

#### B-02 — Environment dinâmico no PingController
- **Arquivo**: `borurio-web/src/main/java/br/com/borurio/web/controller/PingController.java`
- **Correção**: campo `environment` estava hardcoded como literal `"dev"` → substituído por `@Value("${spring.profiles.active:default}")`, refletindo o perfil Spring ativo em qualquer ambiente (dev / hom / prd)

#### B-03 — Remoção de credenciais hardcoded no SslConfig
- **Arquivo**: `borurio-fiscal/src/main/java/br/com/borurio/fiscal/config/SslConfig.java`
- **Correção**: caminho do certificado e senha estavam hardcoded no código-fonte → substituídos por `System.getProperty("fiscal.cert.path", fallback)` e `System.getProperty("fiscal.cert.senha", "")`. Nenhum valor sensível permanece no repositório.

#### B-04 — Seção 8.3 PT-BR + tabela de tradução EN
- Seção 8.3 "Referência de Mensagens da API" criada em `INTEGRATION_CONTRACT_PT-BR.md` (15 entradas)
- Seção 8.3 "Portuguese Message Translation Reference" espelhada em `INTEGRATION_CONTRACT_EN.md` com mapeamento PT→EN das mesmas 15 mensagens
- Em ambos os contratos: seção anterior 8.3 (Paginação / Pagination) renumerada para 8.4
- Em ambos os contratos: observação sobre `environment` corrigida em 2 pontos (seção 6.1 e tabela da seção 10) — estava "fixo como 'dev'" / "hardcoded as 'dev'" → agora reflete o perfil Spring ativo (alinhado ao B-02)

> **Regra aplicada**: PT-BR atualizado primeiro; EN espelhado após confirmação do PT-BR.

---

### Bloco C — Testes de controllers (borurio-web)

Foram criados 8 arquivos de teste `@WebMvcTest`, todos exercitando a camada de controller com mocks de serviço:

| Arquivo de teste | Controller coberto | Testes |
|---|---|---|
| `AuthControllerTest` | `AuthController` | 3 |
| `PingControllerTest` | `PingController` | 2 |
| `EmpresaControllerTest` | `EmpresaController` | 5 |
| `NcmControllerTest` | `NcmController` | 5 |
| `NfeLogControllerTest` | `NfeLogController` | 4 |
| `NfeCancelamentoControllerTest` | `NfeCancelamentoController` | 3 |
| `NfeCceControllerTest` | `NfeCceController` | 3 |
| `NfeInutilizacaoControllerTest` | `NfeInutilizacaoController` | 3 |
| **TOTAL novos** | | **28** |

**Pré-existentes mantidos**: 29 testes em PedidoController, ProdutoController, ClienteController, NfeEnvioController.  
**Total borurio-web**: **57 testes, 0 falhas**.

#### Descobertas técnicas durante os testes

**`@WebMvcTest` não carrega `SecurityConfig` customizado.**  
A fatia de teste carrega o `JwtFilter`, que depende de `JwtUtil` e `UserDetailsService`. O `SecurityConfig` (com `.requestMatchers("/auth/**").permitAll()`) não é incluído. Resultado: todos os requests sem autenticação retornam 401 pelo Spring Security padrão, independente de `permitAll()`.  
Solução: `@WithMockUser` em todos os testes que exercitam lógica do controller + `@MockBean JwtUtil` + `@MockBean UserDetailsService` em todas as classes de teste.

**`EmpresaControllerTest` — 422 no teste de salvar.**  
Body do request não incluía `crt` e `uf`, que possuem `@NotBlank` na entidade. A validação `@Valid` rejeitava antes de chegar ao controller.  
Solução: body corrigido incluindo `"crt":"1","uf":"SP"`.

**`NfeLogControllerTest` — `EmpresaContextHolder` retorna null em slice.**  
ThreadLocal não é populado via JWT no contexto de teste.  
Solução: mock configurado com `isNull()` como matcher: `when(service.listarPaginado(isNull(), anyInt(), anyInt()))`.

---

### Bloco D — NfeAuthorizeServiceTest (borurio-fiscal)
- **Arquivo**: `borurio-fiscal/src/test/java/br/com/borurio/fiscal/mock/NfeAuthorizeServiceTest.java`
- Adicionado stub para o método `deleteAntigos` no mock — faltava após a implementação do `NfeLogRetencaoScheduler` (C-12 da sessão anterior)

---

### Bloco E — Documentação MTF-001 v2.0 → v2.1 (PT-BR e EN)

Foram identificadas e corrigidas 3 afirmações de "pendente/gap" que contradiziam o estado real do código:

| Item | O que o doc dizia | Estado real do código |
|---|---|---|
| Cache invalidation | `EmpresaController.atualizar()` não chama `invalidar()` — gap | `invalidar(empresaId)` **é chamado** (C-10, sessão anterior) |
| Rate limiting | Listado como Fase 11 pendente | `RateLimitInterceptor` **implementado** (C-11, sessão anterior) |
| Retenção de logs | Listado como Fase 11 pendente | `NfeLogRetencaoScheduler` **implementado** (C-12, sessão anterior) |

Demais correções aplicadas ao MTF-001 (v2.1, ambas versões):

| Localização | Correção |
|---|---|
| Cabeçalho | versão 2.0 → 2.1; data 12-05-2026 → 15-05-2026; entrada adicionada ao histórico |
| Seção 1.1 (fechado) | 5 entradas adicionadas: 57 testes, rate limiting, scheduler, CORS, SecureRandom |
| Seção 1.2 (pendente) | Rate limiting e cache invalidation removidos (já implementados) |
| Seção 5.2 | `new Random().nextInt(100_000_000)` → `SecureRandom.nextInt(100_000_000)` com nota explicativa |
| Seção 10.4 / 13.2 / 15 DA-04 | "Gap identificado" → "Implementado: `EmpresaController.atualizar()` chama `invalidar(empresaId)`" |
| Seção 16 Fase 11 | Cache, rate limiting e retenção marcados com ~~strikethrough~~ + ✓ Implementado |

`CHECKLIST_ERP_DELIVERY.md` atualizado: `30/30 testes de controller` → `57/57 testes passando (12 controllers cobertos + fiscal)`.

---

## 3. Validações realizadas

### borurio-web — suíte completa

| Controller | Testes | Falhas |
|---|---|---|
| AuthController | 3 | 0 |
| PingController | 2 | 0 |
| EmpresaController | 5 | 0 |
| NcmController | 5 | 0 |
| NfeLogController | 4 | 0 |
| NfeCancelamentoController | 3 | 0 |
| NfeCceController | 3 | 0 |
| NfeInutilizacaoController | 3 | 0 |
| PedidoController | 9 | 0 |
| ProdutoController | 8 | 0 |
| ClienteController | 8 | 0 |
| NfeEnvioController | 4 | 0 |
| **TOTAL** | **57** | **0** |

### borurio-fiscal

| Suíte | Total | Skip |
|---|---|---|
| NfePipelineLocalTest | 6 | 0 |
| NfeSequenciaServiceTest | 3 | 0 |
| CpfCnpjValidatorTest | 11 | 0 |
| XsdValidatorTest | 3 | 0 |
| Demais testes unitários | 9 | 0 |
| TesteSefazSSL | 1 | 1 (@Disabled — sem .pfx local) |
| **TOTAL** | **32** | **1** |

---

## 4. Estado atual do projeto

### Arquivos aguardando commit (19 total)

**Código modificado (6 arquivos)**

| Arquivo | Correção |
|---|---|
| `borurio-fiscal/.../config/SslConfig.java` | B-03: credenciais removidas |
| `borurio-fiscal/.../mock/NfeAuthorizeServiceTest.java` | Stub `deleteAntigos` |
| `borurio-web/.../controller/PingController.java` | B-02: `@Value` environment dinâmico |
| `borurio-web/.../resources/application-dev.yml` | Fallbacks `fiscal.emitente.*` para DEV |
| `borurio-web/.../resources/banner.txt` | B-01: branding BORURIO ERP FISCAL BR |
| `borurio-web/.../resources/logback-dev.xml` | 5 refs jcho → borurio |

**Testes novos (8 arquivos)**

`AuthControllerTest`, `EmpresaControllerTest`, `NcmControllerTest`, `NfeCancelamentoControllerTest`, `NfeCceControllerTest`, `NfeInutilizacaoControllerTest`, `NfeLogControllerTest`, `PingControllerTest`

**Documentação (5 arquivos)**

`CHECKLIST_ERP_DELIVERY.md`, `INTEGRATION_CONTRACT_PT-BR.md`, `INTEGRATION_CONTRACT_EN.md`, `MTF-001_motor-fiscal-nfe.md`, `MTF-001_motor-fiscal-nfe_EN.md`

---

## 5. Checklist pendente

### Fase 11 — Preparação PRD

- [ ] Corrigir `application-prd.yml` — referências `jcho` remanescentes
- [ ] Corrigir `logback-prd.xml` — referências `jcho` remanescentes
- [ ] Proteger ou desabilitar Swagger UI em PRD
- [ ] **[CRÍTICO]** Configurar `CERT_ENCRYPTION_KEY` em PRD via secrets manager
- [ ] **[CRÍTICO]** Certificados A1 PRD com CNPJ real (`tpAmb=1`)

### Fase 11 — CI/CD

- [ ] GitHub Actions: job `test` → `build` → `deploy-hom` → smoke test
- [ ] Smoke test automatizado pós-deploy (healthcheck + ping)

### Fase 11 — Monitoramento

- [ ] Prometheus metrics (`/actuator/prometheus`)
- [ ] Loki para centralização de logs
- [ ] Alertas para falhas de transmissão SEFAZ

### Integração time chinês (OMS)

- [ ] Fornecer credenciais OPERADOR ao time chinês
- [ ] Confirmar URL externa HOM (VPN / SSH tunnel)
- [ ] Time chinês executa sequência smoke test (8 etapas — CHECKLIST_OMS_ONBOARDING.md)
- [ ] Time chinês implementa OMS → Borurio integration

### Fase 12+ (roadmap)

- [ ] DANFE PDF (geração de PDF da NF-e autorizada)

---

## 6. Riscos e observações

### R1 — CERT_ENCRYPTION_KEY não configurada em PRD
**Probabilidade**: Certa (sem evidência de configuração). **Impacto**: Crítico.
`CertSenhaEncryptor` usa AES-256-GCM para proteger a senha do certificado. Se a chave não estiver configurada em PRD, todas as transmissões SEFAZ falham. Bloqueador de go-live.

### R2 — application-prd.yml e logback-prd.xml com refs jcho
**Probabilidade**: Confirmada. **Impacto**: Médio.
Logs e configurações de PRD com identidade incorreta. Deve ser corrigido antes do primeiro deploy PRD.

### R3 — Swagger exposto em PRD
**Probabilidade**: Alta (sem proteção atual). **Impacto**: Médio-Alto.
Swagger UI ativo em PRD expõe a estrutura completa da API. Deve ser desabilitado ou protegido antes do go-live.

### R4 — 19 arquivos não versionados
**Probabilidade**: Risco ativo. **Impacto**: Alto.
Todo o trabalho desta sessão está uncommitted na branch `fix/sefaz-xml-structure`. Uma falha de hardware resultaria em perda total.

### R5 — cStat=225 em PRD (residual)
**Probabilidade**: Baixa. **Impacto**: Alto.
Diagnosticado como limitação do processador HOM/SP (SP_NFE_PL_008i2 com SHA-1 fixo). Em PRD o processador aceita SHA-256. Sem teste em PRD com `tpAmb=1`, há incerteza residual. Primeiro deploy PRD deve ter monitoramento próximo dos logs.

---

## 7. Próximo passo recomendado

**Prioridade 1 — Versionamento do trabalho acumulado**
19 arquivos aguardando commit representam risco de perda. Primeira ação recomendada ao retomar.

**Prioridade 2 — application-prd.yml + logback-prd.xml** (estimativa: 1h)
Correção das referências `jcho` no perfil PRD. Pré-requisito para qualquer deploy PRD.

**Prioridade 3 — Proteção Swagger PRD** (estimativa: 1h)
Desabilitar ou proteger com IP allowlist / BasicAuth antes do go-live.

**Prioridade 4 — CERT_ENCRYPTION_KEY em PRD** (estimativa: 1–2h)
Bloqueador crítico de go-live. Configurar via secrets manager antes de qualquer transmissão real.

---

## 8. Ponto exato de retomada

**Branch**: `fix/sefaz-xml-structure`
**Testes**: 57/57 (borurio-web) + 32/32 + 1 skip (borurio-fiscal)
**Arquivos pendentes de commit**: 19 (6 código + 8 testes + 5 docs)
**Próxima fase técnica**: Fase 11 — preparação PRD (`application-prd.yml`, `logback-prd.xml`, Swagger, CERT_ENCRYPTION_KEY)

---

*Relatório gerado em 15/05/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
