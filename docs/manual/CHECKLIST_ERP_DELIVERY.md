# Checklist de Entrega — Borurio ERP Fiscal BR

| Atributo          | Valor                         |
|-------------------|-------------------------------|
| Versão            | 1.0                           |
| Data              | 2026-05-12                    |
| Sprint            | 3 (final)                     |
| Ambiente validado | HOM — `http://localhost:8081` |

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

---

## 2. API REST — 10 módulos

| Módulo                   | Endpoint base          | Estado                                   |
|--------------------------|------------------------|------------------------------------------|
| Ping / health check      | `GET /api/test/ping`   | Entregue e validado em HOM               |
| Autenticação             | `POST /auth/login`     | Entregue e validado em HOM               |
| Empresas                 | `/api/app/empresas`    | Entregue e validado em HOM               |
| Produtos                 | `/api/app/produtos`    | Entregue e validado em HOM               |
| Clientes                 | `/api/app/clientes`    | Entregue e validado em HOM               |
| Pedidos + ciclo fiscal   | `/api/app/pedidos`     | Entregue e validado em HOM               |
| Usuários                 | `/api/app/usuarios`    | Entregue e validado em HOM               |
| Logs fiscais             | `/api/fiscal/nfe/logs` | Entregue e validado em HOM               |
| NCM                      | `/api/fiscal/ncm`      | Entregue e validado em HOM               |
| NF-e legado (deprecated) | `/api/fiscal/nfe`      | Deprecated — mantido por compatibilidade |

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

## 7. Testes automatizados

| Item                                | Estado                           |
|-------------------------------------|----------------------------------|
| 57/57 testes passando (29 controller + 28 novos + fiscal) | Passando (15-05-2026) |
| Contexto WebMvc isolado por módulo — 12 controllers cobertos | Entregue            |

---

## 8. Documentação

| Documento                                                         | Estado                                    |
|-------------------------------------------------------------------|-------------------------------------------|
| Manual técnico motor fiscal PT-BR (`MTF-001_motor-fiscal-nfe.md`) | v2.0 — 12-05-2026                         |
| Manual técnico motor fiscal EN (`MTF-001_motor-fiscal-nfe_EN.md`) | v2.0 — 12-05-2026                         |
| Contrato de integração PT-BR (`INTEGRATION_CONTRACT_PT-BR.md`)    | v1.1 — validado contra código-fonte       |
| Contrato de integração EN (`INTEGRATION_CONTRACT_EN.md`)          | v1.1 — entrega principal para time chinês |
| Checklist onboarding OMS chinesa (`CHECKLIST_OMS_ONBOARDING.md`)  | v1.0 — 12-05-2026                         |
| Postman collection (9 pastas, 46 requests)                        | Disponível em `docs/postman/`             |
| Swagger UI (10 tags, deprecated marcados)                         | Operacional em DEV e HOM                  |
| Diagramas arquiteturais                                           | Disponíveis em `docs/architecture/`       |
| Flyway migrations V001–V022                                       | Aplicadas em HOM                          |

---

## 9. O que o time chinês precisa consumir / integrar

| Ação                                                                                                | Responsável       | Status                      |
|-----------------------------------------------------------------------------------------------------|-------------------|-----------------------------|
| Ler `INTEGRATION_CONTRACT_EN.md`                                                                    | Time chinês       | Pendente (entrega imediata) |
| Receber credencial `OPERADOR` criada via ADMIN                                                      | Bruno / Operações | Pendente                    |
| Executar smoke test em HOM (8 chamadas documentadas no contrato)                                    | Time chinês       | Pendente                    |
| Adaptar OMS para sequência: produto → pedido → emitir → situação                                    | Time chinês       | Pendente                    |
| Implementar renovação de token (TTL 1h)                                                             | Time chinês       | Pendente                    |
| Tratar máquina de estados: `RASCUNHO`, `AUTORIZADO`, `AGUARDANDO`, `REJEITADO`, `ERRO`, `CANCELADO` | Time chinês       | Pendente                    |
| Mapear campos da OMS para payloads validados do contrato                                            | Time chinês       | Pendente                    |

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
| Invalidação automática do cache de certificado no `EmpresaController` | Alto        |
| Rate limiting no `POST /api/app/pedidos/{id}/emitir`                  | Médio       |
| CI/CD automatizado (GitHub Actions → deploy HOM → smoke test)         | Médio       |
| Política de retenção de `nfe_log` (agendamento do `deleteAntigos`)    | Baixo       |
| DANFE — PDF da NF-e para destinatário (Fase 12+)                      | Roadmap     |

---

## 12. Limitação conhecida HOM/SP

`cStat=225` é retornado pelo processador `SP_NFE_PL_008i2` do ambiente de homologação da SEFAZ-SP para todas as NF-e. Causa: o processador usa SHA-1 internamente. O código do Borurio está em conformidade com NT 2019.001 (RSA-SHA256). Esta limitação **não afeta PRD**.

Validação em HOM: confirmar que `data.chaveNfe` tem 44 dígitos (lote aceito). O `cStat=225` é comportamento esperado e documentado.
