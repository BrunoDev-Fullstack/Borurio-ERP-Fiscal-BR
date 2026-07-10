# Roteiro de Entrega — Integração com Time Chinês (Bless / CC)

| Atributo             | Valor                                                    |
|----------------------|----------------------------------------------------------|
| Versão               | 1.4                                                      |
| Data                 | 10-07-2026                                               |
| Estado do backend    | PRONTO — HOM UP, 190/190 testes, V028 multi-CNPJ ativo, estoque opcional + reemissão + endereço emitente + errorCode/retryable (v1.9) |
| Responsável Bruno    | Operações + Infraestrutura                               |
| Responsável time CN  | Integração OMS                                           |

---

## Como usar este roteiro

Execute os blocos em ordem. Cada bloco tem um dono (**Bruno** ou **Time chinês**) e um critério de conclusão objetivo. Não avance para o bloco seguinte enquanto o critério não estiver cumprido.

---

## BLOCO 0 — Commit e versionamento
**Dono:** Bruno  
**Critério:** branch `fix/sefaz-xml-structure` com todo o trabalho das Fases 12-A, 12-B e V028 comitado

- [x] Commit da Fase 12-B — DANFE — concluído (histórico git)
- [x] Commits V028 — Multi-CNPJ OMS — concluídos (23-06-2026)
  - migration `V028__oms_multiempresa.sql`
  - entidades e mappers (`OmsCompanyCertificate`, `OmsFiscalAuthorizationMapper`, etc.)
  - serviços (`OmsFiscalAuthorizationService`, `OmsCertificadoService`, `JwtUtil`)
  - controller (`PedidoController` — validação `cnpjEmitente` OMS)
  - testes (126/126 PASS)
  - contratos v1.7 (`INTEGRATION_CONTRACT_PT-BR.md`, `INTEGRATION_CONTRACT_EN.md`)
- [x] Fix DA-08 — PRODUCT_NOT_FOUND multi-CNPJ — concluído (30-06-2026, commit 117a447)
  - `PedidoEmissaoService`: empresaId para estoque usa `pedido.getEmpresaId()` (âncora)
  - MTF-001 PT-BR e EN atualizados para v2.7

> **Ponto de verificação**: `git log --oneline -5` mostra commits V028, hardening e fix DA-08 — suite 126/126 PASS.

---

## BLOCO 1 — Acesso externo ao HOM
**Dono:** Bruno / Operações  
**Critério:** Time chinês consegue acessar `GET {tunnel-url}/api/test/ping` e recebe `"status": "UP"`

> **Nota (v1.9 — divergência da prática atual):** este bloco descreve o setup de um túnel nomeado permanente (`hom-api.borurio.com`, DNS fixo). **Na prática, cada sessão de teste com o CC usa um Cloudflare Quick Tunnel efêmero** (`cloudflared tunnel --url http://localhost:8081`), que gera uma URL `*.trycloudflare.com` nova a cada execução — sem DNS fixo, sem persistência como serviço Windows. A URL ativa é informada ao CC manualmente no início de cada sessão (ver `FAQ_SMOKE_TEST_OMS.md` Q7). O setup de túnel nomeado abaixo permanece como opção futura, não implementada — não assumir que `https://hom-api.borurio.com` está no ar sem confirmar antes.

**PRD reservado:** `https://api.borurio.com` (não configurar agora)

- [x] Mecanismo definido: Cloudflare Tunnel (`cloudflared`)
- [x] URL externa confirmada: `https://hom-api.borurio.com`
- [x] Documentos, Postman collection e Swagger atualizados com a URL
- [ ] **Pré-requisito DNS:** confirmar se `borurio.com` está com DNS gerenciado na Cloudflare
  - Se sim: `cloudflared tunnel route dns borurio-hom hom-api.borurio.com` cria o CNAME automaticamente
  - Se não: criar CNAME manualmente no provedor DNS: `hom-api → <TUNNEL_ID>.cfargotunnel.com`
- [ ] Instalar e iniciar o Cloudflare Tunnel na máquina que hospeda o HOM:
  ```powershell
  winget install Cloudflare.cloudflared
  cloudflared tunnel login
  cloudflared tunnel create borurio-hom
  cloudflared tunnel route dns borurio-hom hom-api.borurio.com
  cloudflared tunnel run borurio-hom   # testar manualmente primeiro
  cloudflared service install          # persistir como serviço Windows
  net start cloudflared
  ```
  Config em `%USERPROFILE%\.cloudflared\config.yml`:
  ```yaml
  tunnel: <TUNNEL_ID>
  credentials-file: C:\Users\bruno\.cloudflared\<TUNNEL_ID>.json
  ingress:
    - hostname: hom-api.borurio.com
      service: http://localhost:8081
    - service: http_status:404
  ```
- [ ] Validar externamente: `curl https://hom-api.borurio.com/api/test/ping` retorna `"status":"UP"`
- [ ] Adicionar `https://hom-api.borurio.com` ao `CORS_ALLOWED_ORIGINS` no `docker/env/.env.hom` e reiniciar container HOM:
  ```
  CORS_ALLOWED_ORIGINS=https://hom-api.borurio.com,http://localhost:8080,http://localhost:3000
  ```
  ```powershell
  docker restart borurio-web-hom
  ```
- [x] `CHECKLIST_OMS_ONBOARDING.md` atualizado com URL `https://hom-api.borurio.com`

> **Ponto de verificação**: Time chinês faz `GET https://hom-api.borurio.com/api/test/ping` de qualquer rede e recebe `"status": "UP"`.

---

## BLOCO 2 — Credenciais para o time chinês (V028 — modelo OMS via certificado)

**Dono:** Bruno  
**Critério:** Time chinês recebe a `X-Api-Key` e consegue obter o token OMS via `POST /api/integration/fiscal-authorizations`

> **Nota V028:** o OMS **não usa login com usuário e senha**. A autenticação é feita diretamente com o certificado A1 da empresa emitente. A `X-Api-Key` é o único segredo que precisa ser compartilhado via canal seguro.

- [ ] Gerar (ou confirmar existência de) `X-Api-Key` para o integrador OMS:
  ```sql
  -- Verificar se já existe
  SELECT id, descricao, hash FROM oms_api_key WHERE ativo = 1;
  -- Se não existir, criar via endpoint ADMIN:
  -- POST /api/admin/oms/api-keys  { "descricao": "OMS JCHO PRD" }
  ```
- [ ] Enviar para o time chinês via canal seguro:
  - URL base HOM: `https://hom-api.borurio.com`
  - `X-Api-Key` (plaintext — enviada **uma única vez** via canal seguro; não salvar em arquivo)
  - Link para `INTEGRATION_CONTRACT_EN.md` e `CHECKLIST_OMS_ONBOARDING.md`
  - Instruções do Bloco 0B do checklist (autorização com certificado A1)

> **O time chinês não precisa de e-mail nem senha** — apenas da X-Api-Key e do certificado A1 da empresa.

> **Ponto de verificação**: Time chinês executa `POST /api/integration/fiscal-authorizations` e recebe `data.token` JWT válido.

---

## BLOCO 3 — Smoke test pelo time chinês
**Dono:** Time chinês  
**Critério:** Todos os blocos do `CHECKLIST_OMS_ONBOARDING.md` executados, incluindo Bloco 0B multi-CNPJ

- [ ] Bloco 0 — Pré-requisitos (X-Api-Key + URL + Postman)
- [ ] **Bloco 0B — Autorização fiscal OMS (V028)**
  - M1: autorizar CNPJ1 — confirmar `data.token` + `data.empresaId`
  - M2: autorizar CNPJ2 com mesmo `codigoEmpresaOms` — confirmar token **idêntico** ao M1
  - M3: reenviar CNPJ1 com mesmo cert — confirmar token **idêntico** (cenário B)
- [ ] Bloco 2 — Healthcheck (`GET /api/test/ping` → `"status": "UP"`)
- [ ] Bloco 3 — Cadastro de produto (confirmar `data.id` + `csosn="400"` default)
- [ ] Bloco 4 — Criação de pedido com `cnpjEmitente = CNPJ2` (confirmar `status="RASCUNHO"`)
- [ ] Bloco 4 — Negativo: pedido com `cnpjEmitente` não autorizado → confirmar `HTTP 403 CNPJ_NOT_AUTHORIZED`
- [ ] Bloco 5 — Emissão NF-e (confirmar `chaveNfe` com 44 dígitos)
- [ ] Bloco 6 — Consulta de situação (confirmar máquina de estados)
- [ ] Bloco 8 — Verificações de segurança (401 sem token, X-Api-Key inválida → 401)
- [ ] Bloco 9 — Bloqueadores PRD (identificados; responsabilidade Bruno/Operações)
- [ ] **(v1.9)** Bloco 4/5 — Endereço do emitente incompleto → `EMITTER_ADDRESS_INCOMPLETE`; completar via `emit*` e reemitir
- [ ] **(v1.9)** Bloco 5 — Reemissão de pedido `REJEITADO`/`ERRO` no mesmo `pedidoId` (smoke test R1–R5 do contrato, seção 9.1c)

**Extras recomendados (não bloqueantes):**
- [ ] Time chinês testa `GET /api/fiscal/nfe/{chave}/danfe` — confirma recebimento do PDF
- [ ] Time chinês testa `GET /api/app/produtos/{id}/estoque` — confirma saldo em tempo real
- [ ] M5: emissão com cert A1 real de CNPJ2 (depende de cert disponível)

> **Ponto de verificação**: Time chinês reporta "smoke test concluído" com token multi-CNPJ funcionando e chaveNfe de 44 dígitos.

---

## BLOCO 4 — Integração OMS (desenvolvimento time chinês)
**Dono:** Time chinês  
**Critério:** OMS consegue emitir NF-e end-to-end via API do Borurio sem intervenção manual

Referência: `INTEGRATION_CONTRACT_EN.md` — sequência obrigatória:

```
produto cadastrado → pedido criado → POST /emitir → GET /situacao (poll)
```

- [ ] Mapear campos da OMS para payloads do Borurio (ver seções 4, 5, 6 do contrato EN v1.9)
- [ ] **Implementar fluxo multi-CNPJ (V028):**
  - Armazenar a `X-Api-Key` de forma segura (não expor em logs ou repositório)
  - Chamar `POST /api/integration/fiscal-authorizations` com o certificado A1 de **cada** CNPJ emitente
  - Guardar o token retornado (único para todos os CNPJs do `codigoEmpresaOms`)
  - Incluir `cnpjEmitente` (14 dígitos) em **cada** `POST /api/app/pedidos`
  - O token expira na data do certificado A1 (`tokenExpiraEm`) — implementar reautorização antes do vencimento
- [ ] Implementar polling de `GET /situacao` pós-emissão
- [ ] Tratar máquina de estados: `RASCUNHO → AGUARDANDO → AUTORIZADO / REJEITADO / ERRO` — **(v1.9)** `REJEITADO`/`ERRO` não são mais terminais: chamar `/emitir` de novo no mesmo `pedidoId` após corrigir a causa, sem criar pedido novo
- [ ] Garantir que `empresa_id` **nunca** é enviado no body (é extraído do JWT automaticamente)
- [ ] Tratar HTTP 403 `CNPJ_NOT_AUTHORIZED` na criação do pedido — indica que o CNPJ não foi autorizado via `/fiscal-authorizations`
- [ ] Tratar comportamento de estoque: verificar `GET /api/app/produtos/{id}/estoque` antes de emitir se necessário
- [ ] Tratar HTTP 422 de estoque insuficiente (pedido continua em `RASCUNHO` — não tentar reemitir sem ajustar qtd)
- [ ] **(v1.9)** Usar o campo `retryable` de toda resposta de erro pra decidir entre reenvio automático (`true`) e correção manual antes de reenviar (`false`) — não inferir pelo texto de `message`
- [ ] **(v1.9)** Tratar `errorCode: EMITTER_ADDRESS_INCOMPLETE` — enviar campos `emit*` em `POST /api/app/pedidos` pra completar o cadastro da empresa emitente
- [ ] **(v1.9)** Tratar `errorCode: SEFAZ_REJECTED` — `/emitir` não retorna mais HTTP 200 quando a SEFAZ rejeita; usar `data.cStat`/`data.xMotivo`

> **Ponto de verificação**: OMS emite NF-e em HOM via integração automática (sem chamada manual do Postman).

---

## BLOCO 5 — Preparação PRD [Bruno / Operações]
**Dono:** Bruno / Operações  
**Critério:** Ambiente PRD configurado e pronto para configurar o certificado A1 real da Jcho Factory Ltda (já entregue pela Bless)

> **ATENÇÃO: estes itens bloqueiam o go-live. Nenhum deles pode ser substituído.**

- [ ] **[CRÍTICO]** Configurar o certificado A1 real da Jcho Factory Ltda (já entregue pela Bless) com `tpAmb=1` em PRD
  - Tipo: PKCS12 (.pfx), emitido por AC autorizada pela ICP-Brasil
  - Cadastrar via `PUT /api/app/empresas/{id}` (campos `certPath`, `certSenha`, `certTipo`)
- [ ] **[CRÍTICO]** Configurar `CERT_ENCRYPTION_KEY` em PRD
  - Gerar: `openssl rand -base64 32`
  - Injetar via secrets manager / variável de ambiente: `CERT_ENCRYPTION_KEY=<valor>`
  - **Nunca** colocar no repositório ou no arquivo `.yml`
- [ ] **[CRÍTICO]** Definir URL PRD externamente acessível
- [ ] Configurar `tpAmb=1` nas propriedades de PRD (`application-prd.yml`)
- [ ] Verificar que Swagger está desabilitado em PRD (`springdoc.swagger-ui.enabled=false`)
- [ ] Primeiro deploy PRD: monitorar logs de transmissão SEFAZ — primeiro `cStat=100` confirma PRD funcional

> **Ponto de verificação**: `POST /api/app/pedidos/{id}/emitir` em PRD retorna `cStat=100` (AUTORIZADO).

---

## BLOCO 6 — Go-live
**Dono:** Bruno + Time chinês  
**Critério:** OMS em produção emitindo NF-e reais com `tpAmb=1`

- [ ] Time chinês aponta OMS para URL PRD
- [ ] Primeira NF-e real emitida e autorizada (`cStat=100`)
- [ ] DANFE gerado e entregue ao destinatário (`GET /api/fiscal/nfe/{chave}/danfe`)
- [ ] Monitorar `GET /api/app/pedidos/{id}/situacao` — confirmar `status="AUTORIZADO"`
- [ ] Confirmar controle de estoque: `estoqueDisponivel` decrementado corretamente

---

## Resumo de bloqueadores por responsável

| Bloqueador | Responsável | Fase |
|---|---|---|
| Commits V028 (migration + código + docs) | Bruno | Bloco 0 |
| URL externa HOM | Bruno / Operações | Bloco 1 |
| X-Api-Key entregue ao CC via canal seguro | Bruno | Bloco 2 |
| Smoke test HOM — incluindo multi-CNPJ M1–M4/M6 | Time chinês | Bloco 3 |
| Integração OMS com V028 (cnpjEmitente + token multi-CNPJ) | Time chinês | Bloco 4 |
| Certificado A1 PRD | Bruno / Operações | Bloco 5 |
| CERT_ENCRYPTION_KEY PRD | Bruno / Operações | Bloco 5 |
| URL PRD | Operações | Bloco 5 |

---

## Documentos de referência

| Documento | Caminho | Para quem |
|---|---|---|
| Contrato de integração EN | `docs/manual/INTEGRATION_CONTRACT_EN.md` | Time chinês |
| Manual técnico motor fiscal EN | `docs/manual/MTF-001_motor-fiscal-nfe_EN.md` | Time chinês / técnico |
| Checklist onboarding OMS | `docs/manual/CHECKLIST_OMS_ONBOARDING.md` | Time chinês |
| Checklist entrega ERP | `docs/manual/CHECKLIST_ERP_DELIVERY.md` | Bruno / operações |
| Postman collection | `docs/postman/borurio-erp-collection.json` | Time chinês |
| Swagger UI (HOM) | `https://hom-api.borurio.com/swagger-ui/index.html` | Time chinês |
