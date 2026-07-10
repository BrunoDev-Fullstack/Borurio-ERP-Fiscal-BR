# Changelog — Borurio ERP Fiscal BR

Histórico consolidado de mudanças do motor fiscal NF-e 4.00 e da integração OMS. Ordem cronológica reversa (mais recente primeiro).

Duas numerações de versão coexistem neste projeto e ambas são referenciadas aqui:
- **MTF** — versão do manual técnico do motor fiscal (`docs/manual/MTF-001_motor-fiscal-nfe.md`), acompanha a evolução do código/arquitetura.
- **Contrato** — versão do contrato de integração OMS (`docs/manual/INTEGRATION_CONTRACT_PT-BR.md`), acompanha a evolução do payload/comportamento da API consumida pelo OMS.

Nem toda mudança de código gera mudança de versão do contrato, e vice-versa — por isso as duas colunas.

---

## [2026-07-10] — MTF 2.8 · Contrato 1.9

**Contexto:** homologação ao vivo com o CC (Xiao Li). Estoque opcional (implementado em sessão anterior, commit `936e771`) validado com sucesso; durante o teste, a SEFAZ rejeitou uma NF-e com `cStat=225` para uma empresa auto-criada via OMS sem endereço cadastrado. Diagnóstico levou a três melhorias solicitadas pelo CC.

### Adicionado
- Reemissão de pedidos `REJEITADO`/`ERRO` no mesmo `pedidoId` via `POST /emitir` (`PedidoEmissaoService.STATUS_EMISSIVEIS`) — não é mais necessário criar um pedido novo após uma rejeição. Cada nova tentativa gera `nNF`/`chaveNfe` novos via `NfeSequenciaService`.
- Endereço do emitente opcional em `POST /api/app/pedidos` (`emitLogradouro`, `emitNumero`, `emitBairro`, `emitCodigoMunicipio`, `emitMunicipio`, `emitCep`) — completa automaticamente apenas os campos ausentes do cadastro da empresa, sem sobrescrever endereço já preenchido.
- Campo `retryable` (booleano) em todo envelope de erro da API — indica se a mesma requisição pode ser reenviada sem alteração (falha transitória) ou se a causa precisa ser corrigida primeiro.
- Novos `errorCode`: `EMITTER_ADDRESS_INCOMPLETE` (422, retryable=false — bloqueia `/emitir` antes de chamar a SEFAZ), `SEFAZ_REJECTED` (422, retryable=false — expõe `data.cStat`/`data.xMotivo`), `SEFAZ_TIMEOUT` (503, retryable=true), `SEFAZ_UNAVAILABLE` (503, retryable=true), `XML_SCHEMA_INVALID` (422, retryable=false).
- `NfeGeracaoService.validarEnderecoEmitente()` — intercepta cadastro de emitente incompleto antes de montar/transmitir o XML.
- `XmlSchemaValidationException` (borurio-fiscal) — exceção dedicada para falhas de validação de schema local, antes genérica.
- Smoke test 9.1c no contrato de integração (endereço do emitente + reemissão).

### Alterado
- `POST /emitir` **não retorna mais HTTP 200** quando a SEFAZ rejeita a NF-e (`cStat≥200`) — retorna HTTP 422 `SEFAZ_REJECTED`. Antes: HTTP 200 com o `cStat` embutido em `data.soapRetorno`, exigindo parse do XML.
- Precondição de `/emitir`: aceita `RASCUNHO`, `REJEITADO` ou `ERRO` (antes: só `RASCUNHO`).
- Catch de erro em `PedidoEmissaoService.emitir()` preserva a `chaveNfe` já persistida em vez de zerá-la incondicionalmente.
- Atualização do cadastro da empresa (endereço) em `PedidoController.criar()` passa a ocorrer **depois** da criação do pedido, não antes — evita mutação de dado numa requisição que falha.
- Fallback genérico do `GlobalExceptionHandler` (toda a API, não só NF-e) — `retryable` corrigido de `true` para `false` como padrão seguro.

### Corrigido
- Documentação: `cStat=225` deixou de ser descrito como exclusivamente "limitação do ambiente HOM-SP" — confirmada uma segunda causa real (cadastro do emitente sem endereço) durante homologação ao vivo. Ambas as causas agora documentadas lado a lado (ver `MTF-001` seção 13.1).
- Documentação: descrição anterior de que uma rejeição (`cStat≥200`) deixava o pedido em `AGUARDANDO` estava incorreta — sempre resultou em `REJEITADO`, mesmo antes desta versão.

### Dado (empresa 8 — HOM)
- Cadastro de endereço completado para "J ZHENG BIJOUTERIAS" (CNPJ 22418179000134) via `PUT /api/app/empresas/8`, dados fornecidos pelo CC.

### Revisão de código
- 8 agentes de busca + 9 verificações independentes — 4 achados confirmados corrigidos nesta versão (chaveNfe zerada, empresa mutada sem rollback, retryable incorreto no fallback global e em `SEFAZ_REJECTED`). 3 achados de menor severidade registrados no backlog arquitetural (P2.7, P2.8, P2.9) — não corrigidos nesta versão.

### Testes
- Suite completa: 126/126 → **190/190 PASS**.

---

## [2026-07-10] — MTF 2.8 (parcial, sessão anterior) · Contrato 1.8

### Adicionado
- Controle de estoque opcional por empresa (`Empresa.controleEstoqueAtivo`, commit `936e771`). Empresas com a flag desativada nunca recebem `INSUFFICIENT_STOCK` em `/emitir` e nunca têm saldo alterado (reserva, baixa, estorno, cancelamento).

### Compatibilidade
- Nenhuma mudança de comportamento para empresas existentes (default: controle de estoque ativo).

---

## [2026-06-30] — MTF 2.7

### Corrigido
- **DA-08** — bug `PRODUCT_NOT_FOUND` no fluxo multi-CNPJ: `PedidoEmissaoService` usava `empresa.getId()` (empresa fiscal do CNPJ emitente) em vez de `pedido.getEmpresaId()` (empresa-âncora) para operações de estoque. Separação corrigida — estoque sempre usa a âncora, NF-e sempre usa o CNPJ emitente correto. Commit `117a447`.

---

## [2026-06-22] — MTF 2.6 · Contrato 1.7 / 1.6.1

**Contexto:** V028 — Multi-CNPJ OMS.

### Adicionado
- `POST /api/integration/fiscal-authorizations` — sessão fiscal por certificado A1, sem login de usuário para o OMS.
- Um cliente OMS (`codigoEmpresaOms`) pode autorizar múltiplos CNPJs sob o **mesmo token**.
- Auto-criação de empresa a partir do Subject X.509 do certificado (CNPJ, razão social, UF) — sem pré-cadastro ADMIN.
- Token determinístico via `emitidoEm` truncado a segundos — garante token idêntico nos cenários B/C/D de reautorização.
- Campo `cnpjEmitente` em `POST /api/app/pedidos` — seleciona o certificado correto na emissão multi-CNPJ.
- Novos `errorCode`: `COMPANY_INACTIVE`, `CNPJ_NOT_AUTHORIZED`, `CERT_NOT_FOUND_FOR_CNPJ`.
- Smoke test multi-CNPJ (seção 9.1b do contrato).

### Removido
- `errorCode: COMPANY_NOT_FOUND` — empresa agora é sempre auto-criada, nunca "não encontrada" nesse endpoint.

### Segurança (hardening A-03 / A-04)
- **A-03:** endpoints legados de `NfeEnvioController` (deprecated) protegidos com `@PreAuthorize("hasRole('ADMIN')")`.
- **A-04:** `RateLimitInterceptor.resolveIp()` não lê mais `X-Forwarded-For` sem validação de proxy — usa exclusivamente `getRemoteAddr()`.

### Testes
- Suite completa: 114/114 → **126/126 PASS**.

---

## [2026-06-18] — Contrato 1.6.1

### Corrigido
- Documentação: resposta de `POST /api/integration/fiscal-authorizations` **não usa** o envelope `Result<>` — DTO retornado diretamente na raiz.

---

## [2026-06-17] — MTF 2.5 (parcial) · Contrato 1.6

### Adicionado
- Sessão OMS por Certificado A1 (P1.13) — precursora do modelo multi-CNPJ da V028.
- Novos `errorCode`: `INVALID_API_KEY`, `COMPANY_NOT_FOUND`, `INVALID_CERTIFICATE`, `CNPJ_CERTIFICATE_MISMATCH`, `CERTIFICATE_EXPIRED`, `AUTHORIZATION_REVOKED`.

---

## [2026-06-10] — Contrato 1.5

### Alterado
- Campos fiscais do item do pedido (`codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`) passam a ser **obrigatórios** e enviados pelo OMS em cada item — sistema deixa de copiar dados fiscais do produto cadastrado. Campo ausente retorna HTTP 400.

---

## [2026-06-01] — Contrato 1.4

### Adicionado
- Header de rastreabilidade `X-Request-Id` em toda resposta (com `requestId` também no corpo de erros de negócio).
- Idempotência via `externalOrderId` na criação de pedidos.
- Batch upsert de produtos (`POST /api/app/produtos/batch`) — HTTP 207 Multi-Status, até 200 itens por requisição.
- Endpoints de Manifestação do Destinatário (eventos 210200/210210/210220/210240).

---

## [2026-05-27] — Contrato 1.3

### Adicionado
- `cfop` passa a ser opcional no cadastro de produto — default `"5102"` se omitido.

### Segurança
- Rotação de senha do certificado/banco HOM (M3).

---

## [2026-05-26] — MTF 2.5

### Adicionado
- Manifestação do Destinatário (eventos 210200/210210/210220/210240, NT 2012.004) — `POST /api/fiscal/nfe/manifestar`.
- Validação de `cStat` na resposta SEFAZ (rejeição correta quando `cStat≠128/135`).
- `xml_retorno` persistido em `nfe_log` mesmo em caso de erro de transmissão.

### Testes
- Suite: 75/75 → 82/82 PASS.

---

## [2026-05-21] — MTF 2.4

### Corrigido
- 3 bugs em `DanfePdfGenerator`: formatação monetária pt_BR nos totais, `DecimalFormat` recriado por chamada (thread-safety), label de protocolo condicional.

### Testes
- Suite: 66/66 → 75/75 PASS.

---

## [2026-05-18] — MTF 2.2 / 2.3 · Contrato 1.2

**Fase 12-A — Estoque mínimo fiscal:**

### Adicionado
- Reserva atômica de estoque antes da transmissão SEFAZ (HTTP 422 se insuficiente).
- Baixa definitiva após `cStat=100` (AUTORIZADO); desfazer reserva após `REJEITADO`/`ERRO`; estorno após `CANCELADO`.
- Tabela `estoque_movimento` — auditoria completa de movimentos.
- `GET /api/app/produtos/{id}/estoque` e `POST /api/app/produtos/{id}/estoque/entrada` [ADMIN].

**Fase 12-B — DANFE:**

### Adicionado
- Geração de PDF do DANFE (`DanfePdfGenerator`, OpenPDF 1.3.30) — `GET /api/fiscal/nfe/{chave}/danfe`.
- Watermark "SEM VALOR FISCAL" automática quando `tpAmb=2` (homologação).

### Testes
- Suite: 61/61 → 66/66 PASS.

---

## [2026-05-15] — MTF 2.1

### Corrigido
- `SecureRandom` para geração de `cNF` (substituiu `new Random()`).
- Rate limiting implementado (`/auth/login` 10 req/min, `/emitir` 30 req/min).
- Agendamento de retenção de `nfe_log`.
- Gap de invalidação de cache de certificado corrigido.

### Testes
- Suite: 57/57 PASS.

---

## [2026-05-12] — MTF 2.0

### Adicionado
- Sprint 3 concluído; RBAC (`ADMIN`/`OPERADOR`); `/situacao` com resposta estruturada; endpoints de integração; smoke test inicial.

---

## [2026-05-11] — MTF 1.0

Versão inicial do manual técnico do motor fiscal. Estado do código nesse momento:

### Adicionado
- Geração de XML NF-e 4.00 completa (`ide`, `emit`, `dest`, `det`, `total`, `transp`, `pag`).
- Validação XSD pré-assinatura.
- Assinatura XMLDSIG RSA-SHA256 + C14N (NT 2019.001).
- Envelope SOAP 1.2 síncrono (`indSinc=1`).
- Autenticação mTLS com certificado A1 PKCS12.
- Sequenciador atômico de `nNF` por CNPJ + série.
- Cancelamento (evento 110111) e Carta de Correção — CC-e (evento 110110).
- Consulta de situação live (`consSitNFe`).
- Multiempresa — isolamento por `empresa_id`.
- Fases 1–9 concluídas; Fase 10 em elaboração.

---

## Notas de manutenção deste arquivo

- Ao fechar uma sessão de trabalho com mudança de versão em `MTF-001` e/ou `INTEGRATION_CONTRACT`, adicionar uma entrada aqui **antes** do commit.
- Uma entrada por data de fechamento de versão, não por commit individual — várias mudanças do mesmo dia entram na mesma entrada.
- Histórico anterior a 11-05-2026 (Fases 1-9, pré-MTF-001) não reconstituído neste changelog — ver git log e `docs/report/` para o histórico bruto de commits daquele período, se necessário.
