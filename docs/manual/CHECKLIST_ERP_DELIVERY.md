# Checklist de Entrega — Borurio ERP Fiscal BR

| Atributo          | Valor                               |
|-------------------|-------------------------------------|
| Versão            | 1.4                                 |
| Data              | 2026-07-10                          |
| Sprint            | Estoque opcional + reemissão REJEITADO/ERRO + endereço emitente + errorCode/retryable (v1.9) |
| Ambiente validado | HOM — túnel Cloudflare efêmero      |

---

## 1. Motor fiscal NF-e 4.00

| Item                                                                                       | Estado                     |
|--------------------------------------------------------------------------------------------|----------------------------|
| Geração de XML NF-e 4.00 completa (`ide`, `emit`, `dest`, `det`, `total`, `transp`, `pag`) | Entregue e validado em HOM |
| Validação XSD pré-assinatura (`nfe_v4.00_consolidado.xsd`)                                 | Entregue e validado em HOM |
| Assinatura XMLDSIG RSA-SHA256 + C14N (NT 2019.001)                                         | Entregue e validado em HOM |
| Envelope SOAP 1.2 com `indSinc=1` (síncrono)                                               | Entregue e validado em HOM |
| Autenticação mTLS com certificado A1 PKCS12                                                | Entregue e validado em HOM |
| Sequenciador atômico de nNF por CNPJ + série                                               | Entregue e validado em HOM |
| Chave de acesso 44 dígitos com dígito verificador módulo 11                                | Entregue e validado em HOM |
| Cancelamento NF-e (evento 110111)                                                          | Entregue e validado em HOM |
| Carta de Correção — CC-e (evento 110110)                                                   | Entregue e validado em HOM |
| Consulta de situação live (`consSitNFe`)                                                   | Entregue e validado em HOM |
| Manifestação do Destinatário (eventos 210200/210210/210220/210240 — NT 2012.004)           | Entregue — 26-05-2026 (Fase pós-DANFE) |

---

## 2. API REST — 10 módulos

| Módulo                         | Endpoint base                               | Estado                                     |
|--------------------------------|---------------------------------------------|--------------------------------------------|
| Ping / health check            | `GET /api/test/ping`                        | Entregue e validado em HOM                 |
| Autenticação                   | `POST /auth/login`                          | Entregue e validado em HOM                 |
| **Autorização Fiscal OMS**     | `POST /api/integration/fiscal-authorizations` | **Entregue — 22-06-2026 (V028 multi-CNPJ)** |
| Empresas                       | `/api/app/empresas`                         | Entregue e validado em HOM                 |
| Produtos                       | `/api/app/produtos`                         | Entregue e validado em HOM                 |
| Estoque de produtos            | `/api/app/produtos/{id}/estoque`            | Entregue — 18-05-2026 (Fase 12-A)          |
| Clientes                       | `/api/app/clientes`                         | Entregue e validado em HOM                 |
| Pedidos + ciclo fiscal         | `/api/app/pedidos`                          | Entregue e validado em HOM                 |
| Usuários                       | `/api/app/usuarios`                         | Entregue e validado em HOM                 |
| Logs fiscais                   | `/api/fiscal/nfe/logs`                      | Entregue e validado em HOM                 |
| NCM                            | `/api/fiscal/ncm`                           | Entregue e validado em HOM                 |
| DANFE (PDF)                    | `/api/fiscal/nfe/{chave}/danfe`             | Entregue — 18-05-2026 (Fase 12-B)          |
| Manifestação Destinatário      | `POST /api/fiscal/nfe/manifestar`           | Entregue — 26-05-2026                      |
| NF-e legado (deprecated)       | `/api/fiscal/nfe`                           | Deprecated — mantido por compatibilidade   |

---

## 3. Autenticação e controle de acesso

| Item                                                      | Estado                     |
|-----------------------------------------------------------|----------------------------|
| JWT stateless HMAC-SHA256, validade 1h                    | Entregue e validado em HOM |
| RBAC com roles `ADMIN` e `OPERADOR`                       | Entregue e validado em HOM |
| Respostas 401/403 em JSON estruturado                     | Entregue e validado em HOM |
| Proteção anti-XXE em parsers XML                          | Entregue                   |
| `SecurityConfig` com dois `SecurityFilterChain` separados | Entregue                   |

---

## 4. Multiempresa

| Item                                                                   | Estado                        |
|------------------------------------------------------------------------|-------------------------------|
| Isolamento de dados por `empresa_id` em `produto`, `pedido`, `nfe_log` | Entregue e validado em HOM    |
| `empresa_id` extraído do JWT via ThreadLocal — não enviado no body     | Entregue e validado em HOM    |
| Certificado A1 por empresa com cache em memória (`ConcurrentHashMap`)  | Entregue e validado em HOM    |
| Criptografia AES-256-GCM para senha do certificado                     | Entregue — passthrough em HOM |
| **Multi-CNPJ OMS (V028)** — token por cliente OMS; múltiplos CNPJs sob o mesmo token | **Entregue — 22-06-2026 — validado em HOM** |
| Auto-criação de empresa a partir do Subject X.509 na autorização OMS  | Entregue — 22-06-2026         |
| Validação `cnpjEmitente` OMS em `POST /pedidos` — fail-fast antes de persistir | Entregue — 22-06-2026 |
| Token determinístico — `emitidoEm` truncado a segundos garante token idêntico em cenários B/C/D | Entregue — 22-06-2026 |
| **DA-08 — fix PRODUCT_NOT_FOUND multi-CNPJ** — estoque usa `pedido.getEmpresaId()` (âncora), não `empresa.getId()` (fiscal do CNPJ emitente) | **Entregue — 30-06-2026 (commit 117a447)** |
| **Endereço do emitente via `POST /pedidos`** — completa automaticamente o cadastro da empresa (auto-criada via certificado, sem endereço) quando incompleto | **Entregue — 10-07-2026** |
| `NfeGeracaoService.validarEnderecoEmitente()` — bloqueia `/emitir` antes da SEFAZ se endereço incompleto (`EMITTER_ADDRESS_INCOMPLETE`) | **Entregue — 10-07-2026** |

---

## 5. Snapshot fiscal imutável

| Item                                                                                          | Estado                     |
|-----------------------------------------------------------------------------------------------|----------------------------|
| Cópia de `codigo`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn` no item do pedido | Entregue e validado em HOM |
| `csosn` default `"400"` quando nulo no produto                                                | Entregue                   |
| `origem` default `0` quando nulo no produto                                                   | Entregue                   |
| Snapshot congelado no momento da criação do pedido                                            | Entregue e validado em HOM |

---

## 6. Auditoria fiscal

| Item                                                           | Estado                     |
|----------------------------------------------------------------|----------------------------|
| Tabela `nfe_documento` com estado persistido de cada NF-e      | Entregue e validado em HOM |
| Tabela `nfe_log` com eventos `ENVIO_NFE` e `TRANSMISSAO_SEFAZ` | Entregue e validado em HOM |
| Falhas de log não interrompem fluxo fiscal                     | Entregue                   |

---

## 6b. Estoque mínimo fiscal (Fase 12-A — 18-05-2026)

| Item                                                                                    | Estado   |
|-----------------------------------------------------------------------------------------|----------|
| Reserva atômica de estoque antes da transmissão SEFAZ (lança 422 se insuficiente)      | Entregue |
| Baixa definitiva após `cStat=100` (AUTORIZADO)                                          | Entregue |
| Desfazer reserva após REJEITADO ou ERRO (helper seguro — nunca mascara resultado SEFAZ) | Entregue |
| Manutenção da reserva em AGUARDANDO (Opção A — liberação manual ou novo ciclo)          | Entregue |
| Estorno de baixa após CANCELADO (`estoque += qtd` auditado)                             | Entregue |
| Entrada manual de estoque via `POST /api/app/produtos/{id}/estoque/entrada` [ADMIN]     | Entregue |
| Consulta de saldo em tempo real via `GET /api/app/produtos/{id}/estoque`                | Entregue |
| Tabela `estoque_movimento` — auditoria completa de todos os movimentos                  | Entregue |
| Isolamento multiempresa — `empresa_id` em todos os UPDATEs atômicos                    | Entregue |
| Controle de estoque opcional por empresa (`controleEstoqueAtivo`) — desativado nunca reserva/baixa/estorna e nunca retorna `INSUFFICIENT_STOCK` | Entregue — 10-07-2026 |

---

## 7. Testes automatizados

| Item                                | Estado                           |
|-------------------------------------|----------------------------------|
| **190/190 testes passando — BUILD SUCCESS** (borurio-app + borurio-fiscal + borurio-web) | **Passando (10-07-2026)** |
| Cenários A/B/C/D de autorização OMS cobertos em `OmsFiscalAuthorizationServiceTest` (14 testes, 8 `@Nested`) | Entregue — 22-06-2026 |
| Validação `cnpjEmitente` OMS em `PedidoControllerTest` — `@MockBean OmsCertificadoService` | Entregue — 22-06-2026 |
| A-03 coberto em `NfeEnvioControllerTest` — 401/403/200 para os 4 endpoints deprecated (12 testes) | Entregue — 22-06-2026 |
| Reemissão REJEITADO/ERRO — `PedidoEmissaoServiceTest` (5 novos cenários) | Entregue — 10-07-2026 |
| Endereço do emitente via pedido — `PedidoControllerTest` (3 novos cenários) | Entregue — 10-07-2026 |
| `errorCode`/`retryable` — `GlobalExceptionHandlerTest` (8 cenários), `NfeGeracaoServiceTest` (novo) | Entregue — 10-07-2026 |
| Revisão de código (8 agentes + 9 verificações) — 4 bugs encontrados e corrigidos | Entregue — 10-07-2026 |
| Contexto WebMvc isolado por módulo — MockitoExtension para serviços           | Entregue               |

---

## 8. Documentação

| Documento                                                         | Estado                                                          |
|-------------------------------------------------------------------|-----------------------------------------------------------------|
| Manual técnico motor fiscal PT-BR (`MTF-001_motor-fiscal-nfe.md`) | v2.8 — 10-07-2026 (estoque opcional; reemissão REJEITADO/ERRO; endereço emitente; errorCode/retryable; seção 13.1 corrigida) |
| Manual técnico motor fiscal EN (`MTF-001_motor-fiscal-nfe_EN.md`) | v2.8 — 10-07-2026 (mesmo escopo da versão PT-BR) |
| Contrato de integração PT-BR (`INTEGRATION_CONTRACT_PT-BR.md`)    | **v1.9** — 10-07-2026 (reemissão; endereço emitente; errorCode/retryable; smoke test 9.1c) |
| Contrato de integração EN (`INTEGRATION_CONTRACT_EN.md`)          | **v1.9** — 10-07-2026 (mesmo escopo da versão PT-BR) |
| Checklist onboarding OMS chinesa (`CHECKLIST_OMS_ONBOARDING.md`)  | **v1.9** — 10-07-2026 (endereço emitente Bloco 4; errorCodes v1.9 no Bloco 5)    |
| FAQ Smoke Test OMS (`FAQ_SMOKE_TEST_OMS.md`)                       | **v1.3** — 22-06-2026 (multi-CNPJ; Q16–Q20 adicionadas)            |
| Roteiro entrega time chinês (`ROTEIRO_ENTREGA_TIME_CHINES.md`)     | **v1.2** — 22-06-2026 (OMS sem login; Bloco 2 reescrito)           |
| Postman collection (10 pastas, 49 requests)                        | Disponível em `docs/postman/` (pendente atualização V028)          |
| Swagger UI (10 tags, deprecated marcados)                          | Operacional em DEV e HOM                                           |
| Diagramas arquiteturais                                            | Disponíveis em `docs/architecture/`                                |
| Flyway migrations V001–V028                                        | V001–V028 aplicadas em HOM — V028 com reparo manual (22-06-2026)   |

---

## 9. O que o time chinês precisa consumir / integrar

| Ação                                                                                                | Responsável       | Status                      |
|-----------------------------------------------------------------------------------------------------|-------------------|-----------------------------|
| Ler `INTEGRATION_CONTRACT_EN.md` v1.7                                                               | Time chinês       | Pendente (entrega imediata) |
| Receber `X-Api-Key` OMS via canal seguro                                                            | Bruno / Operações | Pendente                    |
| Executar `POST /api/integration/fiscal-authorizations` para cada CNPJ emitente (Bloco 0B)          | Time chinês       | Pendente                    |
| Executar smoke test M1–M4/M6 em HOM (multi-CNPJ; ver seção 9.1b do contrato v1.7)                 | Time chinês       | Pendente                    |
| Adaptar OMS para sequência: autorização OMS → produto → pedido (com `cnpjEmitente`) → emitir → situação | Time chinês  | Pendente                    |
| Implementar renovação de token ao aproximar-se do `tokenExpiraEm` (data do certificado A1)         | Time chinês       | Pendente                    |
| Tratar máquina de estados: `RASCUNHO`, `AUTORIZADO`, `AGUARDANDO`, `REJEITADO`, `ERRO`, `CANCELADO` | Time chinês      | Pendente                    |
| Tratar `HTTP 403 CNPJ_NOT_AUTHORIZED` na criação de pedidos                                        | Time chinês       | Pendente                    |
| Mapear campos da OMS para payloads validados do contrato v1.7                                       | Time chinês       | Pendente                    |

---

## 10. O que ainda bloqueia integração real (PRD)

| Bloqueador                                                   | Prioridade   | Responsável       |
|--------------------------------------------------------------|--------------|-------------------|
| Certificados A1 de produção com CNPJ real (`tpAmb=1`)        | Crítico      | Bruno / Operações |
| `CERT_ENCRYPTION_KEY` configurada em PRD via secrets manager | Crítico      | Bruno / Operações |
| URL de PRD definida e acessível externamente                 | Crítico      | Operações         |

---

## 11. Pendências que não bloqueiam integração HOM

| Item                                                                  | Prioridade  |
|-----------------------------------------------------------------------|-------------|
| CI/CD automatizado (GitHub Actions → deploy HOM → smoke test)         | Médio       |
| Postman collection — atualizar com endpoint `/api/integration/fiscal-authorizations` e `cnpjEmitente` | Médio |
| M5 smoke test — emissão SEFAZ com cert A1 real de CNPJ2 (depende de cert disponível) | Baixo |
| ~~Invalidação automática do cache de certificado no `EmpresaController`~~ | ✓ Entregue |
| ~~Rate limiting no `POST /api/app/pedidos/{id}/emitir`~~              | ✓ Entregue  |
| ~~Política de retenção de `nfe_log`~~                                 | ✓ Entregue  |
| ~~DANFE — PDF da NF-e para destinatário (Fase 12-B)~~                 | ✓ Entregue (18-05-2026) |
| ~~Multi-CNPJ OMS (V028)~~                                             | ✓ Entregue (22-06-2026) |

---

## 12. Limitação conhecida HOM/SP — corrigido v1.9

`cStat=225` **não é sempre** uma limitação de ambiente. Duas causas distintas já identificadas (ver MTF-001 seção 13.1):

1. **Processador `SP_NFE_PL_008i2`** — hipótese de divergência SHA-1/RSA-SHA256 entre processadores da SEFAZ-SP. Limitação de ambiente, sem ação corretiva possível sem violar NT 2019.001. Não afeta PRD.
2. **Cadastro do emitente incompleto** (achado em homologação real, 10-07-2026) — mesmo `cStat=225`, causa raiz é dado (endereço da empresa emitente ausente), não ambiente. Corrigido e agora bloqueado preventivamente via `EMITTER_ADDRESS_INCOMPLETE` antes de chamar a SEFAZ.

Validação em HOM: verificar sempre `data.xMotivo` antes de assumir causa 1. Desde v1.9, `cStat≥200` retorna HTTP 422 `SEFAZ_REJECTED` (não mais HTTP 200) — não usar mais `data.chaveNfe` isoladamente como critério de sucesso.
