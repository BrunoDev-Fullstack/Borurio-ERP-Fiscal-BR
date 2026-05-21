# Roteiro de Entrega — Integração com Time Chinês (Bless / CC)

| Atributo             | Valor                                         |
|----------------------|-----------------------------------------------|
| Versão               | 1.0                                           |
| Data                 | 18-05-2026                                    |
| Estado do backend    | PRONTO — HOM UP, 66/66 + 33/33 testes, v024  |
| Responsável Bruno    | Operações + Infraestrutura                    |
| Responsável time CN  | Integração OMS                                |

---

## Como usar este roteiro

Execute os blocos em ordem. Cada bloco tem um dono (**Bruno** ou **Time chinês**) e um critério de conclusão objetivo. Não avance para o bloco seguinte enquanto o critério não estiver cumprido.

---

## BLOCO 0 — Commit e versionamento
**Dono:** Bruno  
**Critério:** branch `fix/sefaz-xml-structure` com todo o trabalho das Fases 12-A e 12-B comitado

- [x] Commit da Fase 12-B — DANFE (5 arquivos de código + 3 docs + 1 relatório) — concluído (histórico git)
  - `DanfePdfGenerator.java`
  - `DanfeService.java`
  - `DanfeServiceImpl.java`
  - `DanfeController.java`
  - `DanfeControllerTest.java`
  - `MTF-001_motor-fiscal-nfe.md` (v2.3)
  - `MTF-001_motor-fiscal-nfe_EN.md` (v2.3)
  - `CHECKLIST_ERP_DELIVERY.md`
  - `Relatorio_Tecnico_18-05-2026.md`

> **Ponto de verificação**: `git log --oneline -5` mostra commit com "danfe" no título.

---

## BLOCO 1 — Acesso externo ao HOM
**Dono:** Bruno / Operações  
**Critério:** Time chinês consegue acessar `GET https://hom-api.borurio.com/api/test/ping` e recebe `"status": "UP"`

**URL confirmada:** `https://hom-api.borurio.com` (Cloudflare Tunnel — HTTPS, TLS 1.3)  
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

## BLOCO 2 — Credenciais para o time chinês
**Dono:** Bruno  
**Critério:** Time chinês recebe e-mail + senha de usuário com role `OPERADOR` e consegue fazer login

- [ ] Autenticar como ADMIN no HOM:
  ```
  POST /auth/login
  { "username": "<admin_email>", "password": "<admin_senha>" }
  ```
- [ ] Criar usuário OPERADOR para o time chinês:
  ```
  POST /api/app/usuarios
  Authorization: Bearer <token_admin>
  {
    "nome": "OMS Integration",
    "email": "oms@empresa-chinesa.com",
    "password": "<senha_temporaria>",
    "role": "OPERADOR"
  }
  ```
- [ ] Enviar para o time chinês (canal seguro):
  - URL base HOM externa
  - E-mail do usuário OPERADOR
  - Senha temporária
  - Link para `INTEGRATION_CONTRACT_EN.md` e `CHECKLIST_OMS_ONBOARDING.md`

> **Ponto de verificação**: Time chinês faz `POST /auth/login` e recebe `token` JWT válido.

---

## BLOCO 3 — Smoke test pelo time chinês
**Dono:** Time chinês  
**Critério:** Todos os 9 blocos do `CHECKLIST_OMS_ONBOARDING.md` marcados como executados

- [ ] Bloco 0 — Pré-requisitos (credenciais + URL + Postman)
- [ ] Bloco 1 — Autenticação (`POST /auth/login` → token JWT)
- [ ] Bloco 2 — Healthcheck (`GET /api/test/ping` → `"status": "UP"`)
- [ ] Bloco 3 — Cadastro de produto (confirmar `data.id` + `csosn="400"` default)
- [ ] Bloco 4 — Criação de pedido (confirmar `status="RASCUNHO"` + snapshot fiscal)
- [ ] Bloco 5 — Emissão NF-e (confirmar `chaveNfe` com 44 dígitos)
- [ ] Bloco 6 — Consulta de situação (confirmar máquina de estados)
- [ ] Bloco 7 — Operações pós-autorização (opcional em HOM — cStat=225 esperado)
- [ ] Bloco 8 — Verificações de segurança (401 sem token, 403 OPERADOR em `/usuarios`)
- [ ] Bloco 9 — Bloqueadores PRD (identificados; responsabilidade Bruno/Operações)

**Extras recomendados (não bloqueantes):**
- [ ] Time chinês testa `GET /api/fiscal/nfe/{chave}/danfe` — confirma recebimento do PDF
- [ ] Time chinês testa `GET /api/app/produtos/{id}/estoque` — confirma saldo em tempo real

> **Ponto de verificação**: Time chinês reporta "smoke test concluído" com chaveNfe de 44 dígitos em mãos.

---

## BLOCO 4 — Integração OMS (desenvolvimento time chinês)
**Dono:** Time chinês  
**Critério:** OMS consegue emitir NF-e end-to-end via API do Borurio sem intervenção manual

Referência: `INTEGRATION_CONTRACT_EN.md` — sequência obrigatória:

```
produto cadastrado → pedido criado → POST /emitir → GET /situacao (poll)
```

- [ ] Mapear campos da OMS para payloads do Borurio (ver seções 4, 5, 6 do contrato EN)
- [ ] Implementar renovação automática de token (TTL 1h — antes que expire)
- [ ] Implementar polling de `GET /situacao` pós-emissão
- [ ] Tratar máquina de estados: `RASCUNHO → AGUARDANDO → AUTORIZADO / REJEITADO / ERRO`
- [ ] Garantir que `empresa_id` **nunca** é enviado no body (é extraído do JWT automaticamente)
- [ ] Garantir que `naturezaOperacao` e `serieNfe` estão sendo enviados ou omitidos conscientemente (defaults aplicados)
- [ ] Tratar comportamento de estoque: verificar `GET /api/app/produtos/{id}/estoque` antes de emitir se necessário
- [ ] Tratar HTTP 422 de estoque insuficiente (pedido continua em `RASCUNHO` — não tentar reemitir sem ajustar qtd)

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
| Commit Fase 12-B | Bruno | Bloco 0 |
| URL externa HOM | Bruno / Operações | Bloco 1 |
| Criação usuário OPERADOR | Bruno | Bloco 2 |
| Smoke test HOM | Time chinês | Bloco 3 |
| Integração OMS | Time chinês | Bloco 4 |
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
