# Borurio ERP Fiscal BR

Plataforma de integração logística cross-border com motor fiscal NF-e 4.00 nativo, autenticação JWT multiempresa e API REST completa. Atua como camada de integração entre a OMS logística chinesa e o ecossistema fiscal brasileiro (SEFAZ-SP). Validado em ambiente de homologação.

---

## Posicionamento do produto

```
OMS Logística (China)
        │
        │  REST API — JSON · JWT · Bearer
        ▼
Borurio ERP Fiscal BR
├── Autenticação & RBAC
├── Multitenancy (empresa_id)
├── Gestão: Produto, Pedido, Cliente
├── Motor Fiscal NF-e 4.00
│   ├── Geração XML, validação XSD
│   ├── Assinatura XMLDSIG RSA-SHA1 (conforme schema oficial vigente)
│   └── Transmissão SOAP + consulta de status
        │
        │  mTLS · Certificado A1 PKCS12
        ▼
SEFAZ-SP (Secretaria da Fazenda do Estado de São Paulo)
```

A OMS consome a API REST do Borurio para criar produtos, abrir pedidos e acionar o ciclo completo de emissão de NF-e. Todo o protocolo fiscal brasileiro (XML, XSD, assinatura digital, SOAP, mTLS) é encapsulado pelo Borurio e invisível para a OMS.

---

## Status atual

| Camada | Estado |
|---|---|
| Motor fiscal NF-e 4.00 | Operacional em HOM — `cStat=100` obtido em 14-07-2026 (marco histórico) e reconfirmado em 22-07-2026 com `modFrete=2`, usando uma empresa vinculada ao token com cadastro fiscal aceito pela SEFAZ |
| Modalidade de frete por fluxo (`modFrete`) | OMS/marketplace = 2 (Terceiros); endpoint legado = 9 (Sem Transporte) — validado em HOM |
| Revogação/rotação global de token OMS | Operacional em HOM — auditoria append-only, controle otimista por versão, idempotência (Gate 7H) |
| API REST — 10 módulos | Operacional em HOM (validado 12-05-2026) |
| Autenticação JWT + RBAC | Operacional em HOM (validado 12-05-2026) |
| Multiempresa (isolamento por empresa_id) | Operacional em HOM (validado 08-05-2026) |
| Certificado A1 por empresa | Operacional em HOM (validado 11-05-2026) |
| 299/299 testes (borurio-core + borurio-app + borurio-fiscal + borurio-web) | Passando (22-07-2026) |
| Swagger UI | Disponível em `/swagger-ui/index.html` |
| Postman collection | Disponível em `docs/postman/` |
| Contrato de integração PT-BR / EN | Disponível em `docs/manual/` |
| Onboarding OMS chinesa | Documentação pronta — ambiente HOM disponível para testes externos do CC; go-live depende da validação final do CC |
| Deploy PRD | Pendente (Fase 11) |

---

## Arquitetura de módulos

```
borurio-erp-br
├── borurio-core     → envelope de resposta (Result<T>, PageResponse), utilitários base
├── borurio-app      → entidades ERP, MyBatis mappers, services: Empresa, Produto, Pedido, DbUser
├── borurio-fiscal   → motor NF-e: XML, XSD, XMLDSIG, SOAP, sequenciador, auditoria, NCM
└── borurio-web      → Spring Boot, API REST, JWT, bridges pedido ↔ NF-e, Swagger
```

**Regra de fronteira:** `borurio-fiscal` não importa `borurio-app`. A integração entre os dois domínios ocorre exclusivamente no `borurio-web` via `CertificadoContexto` (tipos JDK puros).

---

## Stack tecnológica

| Componente | Versão |
|---|---|
| Java | 17 |
| Spring Boot | 3.3.2 |
| Spring Security | 6.x — stateless JWT |
| MyBatis | annotations |
| MySQL | 8.4 |
| Flyway | V001–V032 aplicadas em HOM |
| springdoc-openapi | 2.6.0 |
| Docker | porta 8080 (DEV), 8081 (HOM) |

---

## O que está implementado e validado em HOM

### Autenticação e controle de acesso

- `POST /auth/login` → retorna token JWT (HMAC-SHA256, validade 1h)
- Campo `username` é o e-mail do usuário cadastrado
- JWT embute `empresa_id` como claim `"eid"` — propagado automaticamente por ThreadLocal
- Roles: `ADMIN` (gerencia empresas e usuários) / `OPERADOR` (produtos, pedidos, emissão)
- Endpoints ADMIN-only: `POST /api/app/empresas`, `PUT /api/app/empresas/**`, `GET /api/app/usuarios`, `GET /api/app/usuarios/**`, `/api/admin/**` (revogação/rotação de autorização OMS)
- Toda requisição autenticada por token OMS é validada contra a autorização ativa no banco a cada chamada (revogação surte efeito imediato, sem esperar expiração do JWT)

### Módulos REST disponíveis

| Módulo | Endpoint base | Auth |
|---|---|---|
| Ping / health check | `GET /api/test/ping` | Público |
| Autenticação | `POST /auth/login` | Público |
| Empresas | `/api/app/empresas` | Autenticado (GET) · ADMIN (POST, PUT) |
| Produtos | `/api/app/produtos` | Autenticado |
| Clientes | `/api/app/clientes` | Autenticado |
| Pedidos + ciclo fiscal | `/api/app/pedidos` | Autenticado |
| Usuários | `/api/app/usuarios` | ADMIN |
| Estoque de produtos | `/api/app/produtos/{id}/estoque` | Autenticado (GET) · ADMIN (POST entrada) |
| DANFE (PDF) | `/api/fiscal/nfe/{chave}/danfe` | Autenticado |
| Logs fiscais | `/api/fiscal/nfe/logs` | Autenticado |
| NCM | `/api/fiscal/ncm` | Autenticado |
| NF-e (legado, deprecated) | `/api/fiscal/nfe` | Autenticado |
| Administração de autorização OMS | `/api/admin/oms-authorizations/{id}/revogar`, `/rotacionar` | ADMIN — não faz parte do contrato público da OMS |

### Fluxo fiscal validado

```
POST /auth/login                           → token JWT
POST /api/app/produtos                     → cadastro com snapshot fiscal
POST /api/app/pedidos                      → pedido em RASCUNHO; snapshot congelado nos itens
POST /api/app/pedidos/{id}/emitir          → XML NF-e 4.00 gerado, assinado e transmitido à SEFAZ
GET  /api/app/pedidos/{id}/situacao        → estado local + consulta live consSitNFe
POST /api/app/pedidos/{id}/cancelar        → cancelamento (evento 110111), somente AUTORIZADO
POST /api/app/pedidos/{id}/cce             → Carta de Correção (evento 110110), somente AUTORIZADO
GET  /api/fiscal/nfe/{chave}/danfe         → PDF DANFE para entrega ao destinatário
```

Cancelamento, CC-e, inutilização de numeração e consulta de situação já estão implementados e cobertos por testes automatizados. Permanece pendente a revalidação desses fluxos contra a SEFAZ real em contexto multi-CNPJ (emissão por uma empresa diferente da empresa-âncora do token).

### Estados do pedido

| Status | Condição |
|---|---|
| `RASCUNHO` | Criado, não transmitido |
| `AUTORIZADO` | cStat=100 — NF-e aprovada, estoque baixado |
| `AGUARDANDO` | cStat=104 ou retorno não parseável |
| `REJEITADO` | cStat ≥ 200 — SEFAZ recusou (retorna HTTP 422 `SEFAZ_REJECTED` com `data.cStat`/`data.xMotivo`) |
| `ERRO` | Exceção durante transmissão (HTTP 500) |
| `CANCELADO` | Evento de cancelamento autorizado — imutável |

### Motor fiscal NF-e 4.00

- Geração de XML completa: `<ide>`, `<emit>`, `<dest>`, `<det>`, `<total>`, `<transp>`, `<pag>`
- Validação XSD contra `nfe_v4.00_consolidado.xsd` (pré-assinatura)
- Assinatura XMLDSIG RSA-SHA1 + C14N (conforme `xmldsig-core-schema_v1.01.xsd` oficial vigente)
- Envelope SOAP 1.2 com `indSinc=1` (processamento síncrono)
- Autenticação mTLS com certificado A1 PKCS12
- Sequenciador atômico de número NF-e por CNPJ + série
- Chave de acesso 44 dígitos com dígito verificador módulo 11

### Multiempresa e integração OMS

- Isolamento de dados em `produto`, `pedido`, `nfe_log` por `empresa_id`
- `empresa_id` extraído do JWT a cada request — não enviado no body
- Certificado A1 por empresa com cache `ConcurrentHashMap` em memória
- Criptografia AES-256-GCM para senha do certificado (passthrough em HOM)
- **Multi-CNPJ OMS (V028):** token único por cliente OMS (`codigoEmpresaOms`); múltiplos CNPJs sob o mesmo token; catálogo de produtos pertence à empresa-âncora; `cnpjEmitente` seleciona o certificado de assinatura sem alterar o contexto de estoque (ver DA-08 no MTF-001)
- **Modalidade de frete (`modFrete`) por fluxo de emissão:** o fluxo OMS/marketplace declara `ModalidadeFrete.CONTA_TERCEIROS` (código `2`) — a plataforma contrata o transporte, nunca o emitente nem o destinatário; o endpoint legado/administrativo preserva `ModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE` (código `9`), comportamento anterior inalterado. Não há input de frete vindo da OMS nesta versão — decisão interna do Borurio

### Auditoria fiscal

- Tabela `nfe_documento`: estado persistido de cada NF-e (chave, cStat, xMotivo, nProt, xmlProtocolo)
- Tabela `nfe_log`: dois eventos por emissão — `ENVIO_NFE` (com usuário) e `TRANSMISSAO_SEFAZ` (com empresa_id)
- Tabela `oms_fiscal_authorization_audit`: append-only, um evento por revogação/rotação de autorização OMS (`ROTACAO`/`REVOGACAO`), com versão anterior/nova, `Idempotency-Key` e `requestId` — nunca armazena o JWT
- Falhas de log nunca interrompem o fluxo fiscal

---

## Status da homologação SEFAZ-SP

Em 14-07-2026, a causa raiz da rejeição `cStat=225` foi identificada e corrigida: o motor assinava o XML com RSA-SHA256, mas o schema XMLDSig oficial vigente da SEFAZ (`xmldsig-core-schema_v1.01.xsd`, confirmado no pacote oficial `PL_010e_v1.02`) exige RSA-SHA1/SHA-1. Após a correção da assinatura e de mais quatro problemas encontrados em cascata (grupo `indIntermed` ausente, `indFinal` não condicional ao tipo de destinatário, IE do emitente ausente no cadastro, exigências de payload de CFOP/endereço/texto de homologação), o motor obteve `cStat=100` ("Autorizado o uso da NF-e") em HOM/SP para a empresa homologada atual (vinculada ao token, com IE aceita pela SEFAZ), tanto em teste interno quanto em teste do integrador chinês via OMS. Detalhes completos em `docs/manual/MTF-001_motor-fiscal-nfe.md` (seção 13).

O cadastro histórico do emitente (mantido no sistema desde etapas anteriores do projeto, não apto para emissão atual) segue bloqueado em HOM — IE cassada por inatividade desde 2024, pendência cadastral externa, não corrigível por código.

Em 22-07-2026, com o release do commit `4a39a88` (revogação/rotação global de token OMS + modalidade de frete por fluxo) já ativo em HOM, uma nova emissão real foi autorizada pela SEFAZ-SP (`cStat=100`, protocolo registrado) usando uma empresa vinculada ao token com cadastro fiscal aceito pela SEFAZ, confirmando `<modFrete>2</modFrete>` no XML efetivamente transmitido e autorizado — a primeira validação real do fluxo OMS/marketplace com a modalidade de frete correta. Na mesma janela, a revogação/rotação de autorização OMS foi validada de ponta a ponta em HOM real: token anterior invalidado, token novo funcional, replay idempotente sem duplicidade de auditoria.

**Importante:** essas validações em homologação não são suficientes para considerar a integração pronta para produção. O ambiente HOM permanece disponível para os testes que a OMS chinesa (CC) considerar necessários; o planejamento de go-live só se inicia após a validação final e confirmação do CC. Um teste controlado em produção (operação fiscal real, passível de cancelamento, acompanhado pelo contador) continua sendo pré-requisito obrigatório para o go-live e ainda não foi realizado. O sistema não está em produção.

---

## Documentação disponível

| Documento | Caminho | Descrição |
|---|---|---|
| Manual técnico motor fiscal (PT-BR) | `docs/manual/MTF-001_motor-fiscal-nfe.md` | Arquitetura interna, fluxos, decisões de design, checklists operacionais |
| Manual técnico motor fiscal (EN) | `docs/manual/MTF-001_motor-fiscal-nfe_EN.md` | Versão em inglês do manual técnico |
| Contrato de integração (PT-BR) | `docs/manual/INTEGRATION_CONTRACT_PT-BR.md` | Contrato validado — integração ERP logístico externo |
| Contrato de integração (EN) | `docs/manual/INTEGRATION_CONTRACT_EN.md` | Versão em inglês — entrega principal para time chinês |
| Checklist onboarding OMS chinesa | `docs/manual/CHECKLIST_OMS_ONBOARDING.md` | Passo a passo para integração da OMS com o Borurio |
| Checklist de entrega do ERP | `docs/manual/CHECKLIST_ERP_DELIVERY.md` | Estado completo de entrega e pendências |
| Postman collection | `docs/postman/borurio-erp-collection.json` | 10 pastas, 49 requests, variáveis `{{baseUrl}}` e `{{token}}` |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` (DEV) | Documentação interativa — 10 tags, deprecated marcados |
| Diagramas arquiteturais | `docs/architecture/` | Topologias gerais, fluxo fiscal, ambientes, segurança e CI/CD |

---

## Estrutura do repositório

```
borurio-erp-br/
├── .github/                  → GitHub Actions (build, segurança)
├── borurio-core/             → módulo núcleo
├── borurio-app/              → módulo de negócio ERP
├── borurio-fiscal/           → motor fiscal NF-e
├── borurio-web/              → API REST + Spring Boot
├── docker/                   → configuração Docker Compose
├── docs/
│   ├── architecture/         → diagramas drawio e exports
│   ├── data/                 → referências de dados
│   ├── manual/               → manuais técnicos, contratos e checklists
│   ├── postman/              → Postman collection end-to-end
│   ├── report/               → relatórios e evidências
│   └── xml/                  → XMLs de referência fiscal
├── scripts/                  → scripts operacionais
└── sql/                      → referências SQL
```

---

## Ambientes

| Ambiente | URL | Estado |
|---|---|---|
| DEV | `http://localhost:8080` | Operacional |
| HOM (local) | `http://localhost:8081` | Operacional — release `4a39a88`, atualizado em 22-07-2026 |
| HOM (externo) | `https://hom-api.borurio.com` | **Pendente** — Cloudflare Tunnel (Bloco 1 do ROTEIRO) |
| PRD | Definido por operações | Pendente (Fase 11) |

**Deploy HOM (manual):** a imagem é construída pelo `Dockerfile` da raiz (multi-stage: build Maven + runtime `eclipse-temurin`) — não há montagem de JAR externo via volume.
```bash
docker compose -f docker/docker-compose.hom.yml --env-file docker/env/.env.hom build borurio-web-hom
docker compose -f docker/docker-compose.hom.yml --env-file docker/env/.env.hom up -d --no-deps --no-build --force-recreate borurio-web-hom
```

---

## Segurança

- JWT stateless HMAC-SHA256, sem sessão servidor
- RBAC com dois níveis: `ADMIN` e `OPERADOR`
- Endpoints 401/403 retornam JSON estruturado (não redirect HTML)
- Proteção anti-XXE em todos os parsers XML
- `CERT_ENCRYPTION_KEY` (AES-256-GCM) para senha de certificado — passthrough em HOM, obrigatório em PRD
- `SecurityConfig` com dois `SecurityFilterChain` separados (actuator e aplicação)

---

## Pendências — Fase 11 (PRD)

| Item | Prioridade |
|---|---|
| `CERT_ENCRYPTION_KEY` configurada em PRD via secrets manager | Crítico |
| Certificados A1 de produção com CNPJ real (`tpAmb=1`) | Crítico |
| Rate limiting no `POST /api/app/pedidos/{id}/emitir` | Médio |
| CI/CD automatizado (GitHub Actions → deploy HOM → smoke test) | Médio |
| Política de retenção de `nfe_log` (agendamento do `deleteAntigos`) | Baixo |

---

## Integração com a OMS logística chinesa

O Borurio está pronto para integração em HOM. O backend e a documentação estão disponíveis no repositório. Há dois bloqueadores operacionais pendentes antes do smoke test externo: ativação do Cloudflare Tunnel (`https://hom-api.borurio.com`) e criação da credencial OPERADOR para o time de integração. Enquanto isso, o time pode executar localmente usando `docker/env/.env.dev.template` conforme documentado no `CHECKLIST_OMS_ONBOARDING.md`.

**Documentos para o time chinês:**

| Documento | Caminho |
|---|---|
| Contrato de integração (EN) | `docs/manual/INTEGRATION_CONTRACT_EN.md` |
| Checklist de onboarding | `docs/manual/CHECKLIST_OMS_ONBOARDING.md` |
| Manual técnico (EN) | `docs/manual/MTF-001_motor-fiscal-nfe_EN.md` |
| Postman collection | `docs/postman/borurio-erp-collection.json` |

**Sequência de integração:**

1. Criar credencial OPERADOR via ADMIN para o time chinês
2. Compartilhar `INTEGRATION_CONTRACT_EN.md` e `CHECKLIST_OMS_ONBOARDING.md`
3. Time chinês executa smoke test em HOM (8 chamadas documentadas no contrato)
4. OMS adapta sequência interna: produto → pedido → emitir → situação
5. Integração PRD aguarda certificados A1 de produção (Fase 11)

---

## Autor

Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps  
Contato: contato@borurio.com.br
