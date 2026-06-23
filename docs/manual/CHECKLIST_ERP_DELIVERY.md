# Checklist de Entrega — Borurio ERP Fiscal BR

| Atributo          | Valor                               |
|-------------------|-------------------------------------|
| Versão            | 1.2                                 |
| Data              | 2026-06-22                          |
| Sprint            | V028 (multi-CNPJ OMS)               |
| Ambiente validado | HOM — `https://hom-api.borurio.com` |

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

---

## 7. Testes automatizados

| Item                                | Estado                           |
|-------------------------------------|----------------------------------|
| **126/126 testes passando — BUILD SUCCESS** (borurio-web 93 + fiscal 33) | **Passando (22-06-2026)** |
| Cenários A/B/C/D de autorização OMS cobertos em `OmsFiscalAuthorizationServiceTest` (14 testes, 8 `@Nested`) | Entregue — 22-06-2026 |
| Validação `cnpjEmitente` OMS em `PedidoControllerTest` — `@MockBean OmsCertificadoService` | Entregue — 22-06-2026 |
| A-03 coberto em `NfeEnvioControllerTest` — 401/403/200 para os 4 endpoints deprecated (12 testes) | Entregue — 22-06-2026 |
| Contexto WebMvc isolado por módulo — MockitoExtension para serviços           | Entregue               |

---

## 8. Documentação

| Documento                                                         | Estado                                                          |
|-------------------------------------------------------------------|-----------------------------------------------------------------|
| Manual técnico motor fiscal PT-BR (`MTF-001_motor-fiscal-nfe.md`) | v2.6 — 22-06-2026 (V028 multi-CNPJ OMS)                        |
| Manual técnico motor fiscal EN (`MTF-001_motor-fiscal-nfe_EN.md`) | v2.6 — 22-06-2026 (V028 multi-CNPJ OMS)                        |
| Contrato de integração PT-BR (`INTEGRATION_CONTRACT_PT-BR.md`)    | **v1.7** — 22-06-2026 (multi-CNPJ; cnpjEmitente; cenários A/B/C/D) |
| Contrato de integração EN (`INTEGRATION_CONTRACT_EN.md`)          | **v1.7** — 22-06-2026 (multi-CNPJ; cnpjEmitente; cenários A/B/C/D) |
| Checklist onboarding OMS chinesa (`CHECKLIST_OMS_ONBOARDING.md`)  | **v1.7** — 22-06-2026 (V028 multi-CNPJ; errorCodes atualizados)    |
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

## 12. Limitação conhecida HOM/SP

`cStat=225` é retornado pelo processador `SP_NFE_PL_008i2` do ambiente de homologação da SEFAZ-SP para todas as NF-e. Causa: o processador usa SHA-1 internamente. O código do Borurio está em conformidade com NT 2019.001 (RSA-SHA256). Esta limitação **não afeta PRD**.

Validação em HOM: confirmar que `data.chaveNfe` tem 44 dígitos (lote aceito). O `cStat=225` é comportamento esperado e documentado.
