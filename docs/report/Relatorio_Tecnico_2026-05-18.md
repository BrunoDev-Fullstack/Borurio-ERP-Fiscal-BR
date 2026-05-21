# Relatório Técnico Diário — 18/05/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- Testes automatizados borurio-web: **66 / 0 falhas**
- Testes automatizados borurio-fiscal: **33 / 0 falhas / 1 skipped**
- Total consolidado: **99 testes passando + 1 skip esperado**
- HOM: **UP** — Flyway em `v024`; MySQL UP; porta 8081

---

## 1. Objetivo do dia

Dois eixos de trabalho executados em sequência:

1. **Aplicação das migrations V023 + V024 em HOM** — build do JAR, deploy via `docker cp` + `docker restart`, confirmação via logs do Flyway e healthcheck.
2. **Implementação da Fase 12-B — DANFE** — geração de PDF do Documento Auxiliar da Nota Fiscal Eletrônica a partir do XML assinado persistido em `nfe_documento`.

---

## 2. Atividades executadas

### Bloco A — Deploy HOM (V023 + V024)

**Sequência executada:**
```
mvn -pl borurio-web -am clean package -DskipTests -q
docker cp borurio-web/target/borurio-web-1.0.0.jar borurio-web-hom:/app/app.jar
docker restart borurio-web-hom
```

**Confirmação via logs:**
```
2026-05-18 17:53:17 INFO  org.flywaydb.core.internal.command.DbMigrate
  - Successfully applied 2 migrations to schema `borurio_fiscal_hom`,
    now at version v024 (execution time 00:00.133s)
```

**Healthcheck:**
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "MySQL" } },
    "ping": { "status": "UP" }
  }
}
```

V023 (`estoque_movimento`) e V024 (`produto.estoque_reservado`) aplicados com sucesso. HOM em `v024`.

---

### Bloco B — DANFE (Fase 12-B)

#### B-01 — `DanfePdfGenerator.java`

**Arquivo**: `borurio-fiscal/src/main/java/br/com/borurio/fiscal/danfe/DanfePdfGenerator.java`

Gera o PDF do DANFE usando OpenPDF 1.3.30 (LGPL). Layout A4 portrait com margens de 15pt:

| Seção | Conteúdo |
|---|---|
| Header (3 colunas) | Emitente (nome, CNPJ, IE, CRT, endereço) · DANFE + NF-e nº/série · Protocolo de autorização |
| Natureza da operação | `natOp` + data de emissão formatada (dd/MM/yyyy HH:mm) |
| Chave de acesso | 44 dígitos formatados em grupos de 4 + barcode Code128 |
| Emitente / Destinatário | Blocos lado a lado com CNPJ/CPF, IE, endereço completo |
| Tabela de itens | 10 colunas: N · Código · Descrição · NCM · CFOP · UN · Qtde · V.Unit. · V.Total · CSOSN |
| Totais | Valor total produtos · Valor total NF-e · Ambiente |
| Dados adicionais | `infCpl` (quando presente) |

**Watermark legal (tpAmb=2):**
- Quando `tpAmb=2` (homologação), exibe marca d'água diagonal "SEM VALOR FISCAL" em cinza claro (RGB 210,210,210).
- Implementado via `PdfPageEventHelper.onEndPage()` — aplica automaticamente em todas as páginas, sem interferir na tabela de conteúdo.

**Barcode:**
- `Barcode128` com a chave de acesso completa (44 dígitos).
- `barcode.setFont(null)` — texto da chave já está na seção anterior; evita duplicidade.
- Geração is best-effort: se falhar (ex: contexto de PDF indisponível), o DANFE é emitido sem o barcode mas com a chave em texto (já suficiente para compliance).

**Formatação de valores:**
- Monetários: `#,##0.00` (Locale pt_BR) — ex: `1.234,56`
- Quantidades: `#,##0.####` — até 4 casas decimais sem zeros à direita

#### B-02 — `DanfeService.java` + `DanfeServiceImpl.java`

**Arquivos**:
- `borurio-fiscal/src/main/java/br/com/borurio/fiscal/danfe/DanfeService.java`
- `borurio-fiscal/src/main/java/br/com/borurio/fiscal/danfe/DanfeServiceImpl.java`

**Fluxo:**
1. Busca `NfeDocumento` por `chaveNfe` via `NfeDocumentoService.buscarPorChave()`
2. Lança `NoSuchElementException` se não encontrado → HTTP 404
3. Valida que `xmlNfe` está presente; lança `IllegalStateException` se nulo → HTTP 422
4. Formata `dhRecbto` (LocalDateTime) para `dd/MM/yyyy HH:mm:ss`
5. Chama `DanfeXmlParser.parse(xmlNfe, nProt, dhRecbto, cStat, chaveNfe)`
6. Chama `DanfePdfGenerator.gerar(danfeData)` e retorna `byte[]`

**Fonte de dados escolhida:**
- `nfe_documento.xml_nfe` — XML assinado, **sempre presente** após transmissão (diferente de `xml_protocolo` que pode ser nulo em casos de AGUARDANDO/REJEITADO)
- `nProt`, `dhRecbto`, `cStat` lidos diretamente da entidade `NfeDocumento`

#### B-03 — `DanfeController.java`

**Arquivo**: `borurio-web/src/main/java/br/com/borurio/web/controller/fiscal/DanfeController.java`

```
GET /api/fiscal/nfe/{chave}/danfe
```

- Autenticação JWT obrigatória (qualquer role: ADMIN ou OPERADOR)
- Validação prévia: chave deve ter exatamente 44 caracteres → HTTP 400 sem chamar o serviço
- Headers de resposta: `Content-Type: application/pdf` + `Content-Disposition: attachment; filename="danfe-{chave}.pdf"`
- Tratamento explícito de `NoSuchElementException` (404), `IllegalStateException` (422), `Exception` (500)
- `@Tag(name = "NF-e")` — integrado ao grupo Swagger existente

**Tabela de respostas:**

| Código | Causa |
|---|---|
| 200 | PDF gerado com sucesso |
| 400 | Chave com comprimento ≠ 44 dígitos |
| 401 | Token JWT ausente ou inválido |
| 404 | NF-e não encontrada em `nfe_documento` |
| 422 | XML da NF-e não disponível |
| 500 | Falha interna na geração do PDF |

#### B-04 — `DanfeControllerTest.java`

**Arquivo**: `borurio-web/src/test/java/br/com/borurio/web/controller/DanfeControllerTest.java`

5 cenários `@WebMvcTest(DanfeController.class)`:

| Teste | Cenário | Resultado esperado |
|---|---|---|
| `getDanfe_nfeExistente_retornaPdf` | NF-e encontrada, PDF gerado | 200 + `application/pdf` + bytes do PDF |
| `getDanfe_nfeNaoEncontrada_retorna404` | `NoSuchElementException` | 404 |
| `getDanfe_xmlIndisponivel_retorna422` | `IllegalStateException` | 422 |
| `getDanfe_chaveCurta_retorna400` | Chave com 3 dígitos | 400 |
| `semToken_retorna401` | Sem `Authorization` header | 401 |

Padrão aplicado: `@MockBean DanfeService` + `@MockBean JwtUtil` + `@MockBean UserDetailsService`.

---

### Bloco C — Documentação (v2.3)

**Regra aplicada**: PT-BR atualizado primeiro; EN espelhado após confirmação.

#### `MTF-001_motor-fiscal-nfe.md` — v2.2 → v2.3

| Localização | Alteração |
|---|---|
| Cabeçalho | Versão 2.2 → 2.3; entrada v2.3 adicionada ao histórico |
| Seção 1.1 (fechado) | 6 entradas adicionadas: DANFE, endpoint, watermark, 66/66 testes, V023–V024 em HOM |
| Seção 1.2 (pendente) | DANFE removido (item estava pendente — agora entregue) |
| Seção 3.1 | Título V001–V022 → V001–V024 |
| Seção 12.4 (nova) | DANFE: endpoint, respostas, arquitetura (tabela 5 classes), fonte de dados, watermark, biblioteca |
| Seção 16 Roadmap | Fase 12-B adicionada como CONCLUÍDA com tabela de 5 itens |

#### `MTF-001_motor-fiscal-nfe_EN.md` — v2.1 → v2.3

Mesmas alterações espelhadas em inglês. Inclui:
- Versões v2.2 e v2.3 adicionadas ao histórico
- Seção 1.1: 12 entradas adicionadas (Fase 12-A + Fase 12-B)
- Seção 1.2: DANFE removido dos pendentes
- Seção 12.4 em inglês
- Seção 16 com Phase 12-B concluída

#### `CHECKLIST_ERP_DELIVERY.md`

| Localização | Alteração |
|---|---|
| API table | Linha DANFE adicionada (`/api/fiscal/nfe/{chave}/danfe`) |
| Seção 7 (testes) | 61/61 → 66/66 |
| Seção 8 (docs) | MTF-001 PT-BR e EN: v2.2 → v2.3 |
| Seção 11 (pendências) | DANFE marcado como ~~strikethrough~~ ✓ Entregue |

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
| ProdutoController | 12 | 0 |
| ClienteController | 8 | 0 |
| NfeEnvioController | 4 | 0 |
| DanfeController | 5 | 0 |
| **TOTAL** | **66** | **0** |

### borurio-fiscal

| Suíte | Total | Skip |
|---|---|---|
| NfePipelineLocalTest | 6 | 0 |
| NfeSequenciaServiceTest | 3 | 0 |
| CpfCnpjValidatorTest | 11 | 0 |
| XsdValidatorTest | 3 | 0 |
| Demais testes unitários | 10 | 0 |
| TesteSefazSSL | 1 | 1 (`@Disabled` — sem .pfx local) |
| **TOTAL** | **33** | **1** |

---

## 4. Estado atual do projeto

### Arquivos aguardando commit (9 total)

**Código novo — DANFE (5 arquivos)**

| Arquivo | Descrição |
|---|---|
| `borurio-fiscal/.../danfe/DanfePdfGenerator.java` | Gerador PDF OpenPDF — layout A4, barcode, watermark |
| `borurio-fiscal/.../danfe/DanfeService.java` | Interface do serviço DANFE |
| `borurio-fiscal/.../danfe/DanfeServiceImpl.java` | Implementação — busca NfeDocumento, chama parser + generator |
| `borurio-web/.../controller/fiscal/DanfeController.java` | REST GET `/api/fiscal/nfe/{chave}/danfe` |
| `borurio-web/test/.../DanfeControllerTest.java` | 5 cenários WebMvcTest |

**Documentação atualizada (3 arquivos)**

| Arquivo | Alteração |
|---|---|
| `docs/manual/MTF-001_motor-fiscal-nfe.md` | v2.2 → v2.3 (seção 12.4 DANFE, roadmap Fase 12-B) |
| `docs/manual/MTF-001_motor-fiscal-nfe_EN.md` | v2.1 → v2.3 (espelho PT-BR completo) |
| `docs/manual/CHECKLIST_ERP_DELIVERY.md` | DANFE entregue, 66/66 testes, docs v2.3 |

**Relatório (1 arquivo)**

| Arquivo | Descrição |
|---|---|
| `docs/report/Relatorio_Tecnico_18-05-2026.md` | Este documento |

> **Nota:** `DanfeData.java` e `DanfeXmlParser.java` foram criados e commitados na sessão anterior (fazem parte do commit 6153812).

---

## 5. Checklist pendente

### Integração time chinês (OMS)

- [ ] Fornecer credenciais OPERADOR ao time chinês
- [ ] Confirmar URL externa HOM (VPN / SSH tunnel / URL pública)
- [ ] Time chinês executa sequência smoke test (blocos 0–8 do `CHECKLIST_OMS_ONBOARDING.md`)
- [ ] Time chinês implementa integração OMS → Borurio

### Fase 11 — PRD (bloqueadores críticos)

- [ ] **[CRÍTICO]** `CERT_ENCRYPTION_KEY` configurada em PRD via secrets manager
- [ ] **[CRÍTICO]** Certificados A1 PRD com CNPJ real (`tpAmb=1`)
- [ ] **[CRÍTICO]** URL PRD definida e acessível externamente

### Fase 11 — CI/CD (diferido)

- [ ] GitHub Actions: job `test` → `build` → `deploy-hom` → smoke test automático
- [ ] Decisão de abordagem: runner local vs SSH HOM vs workflow separado

### Ajustes pontuais (não bloqueantes para HOM)

- [ ] `application-dev.yml:121-122` — fallback emitente `JCHO GLOBAL LTDA` → substituir por emitente de teste Borurio (opcional)
- [ ] `application-hom.yml:104` — `certificado-jcho.pfx` → renomear coordenado com Docker + docs + infra

---

## 6. Riscos e observações

### R1 — CERT_ENCRYPTION_KEY não configurada em PRD
**Probabilidade**: Certa. **Impacto**: Crítico.
Sem a chave, todas as transmissões SEFAZ falham em PRD. Bloqueador de go-live.

### R2 — DANFE em ambiente HOM mostra "SEM VALOR FISCAL"
**Probabilidade**: Sempre. **Impacto**: Esperado e correto.
Comportamento legal obrigatório. Em PRD (`tpAmb=1`), a watermark não é exibida.

### R3 — cStat=225 em HOM/SP (residual)
**Probabilidade**: Alta em HOM. **Impacto**: Nenhum — documentado e esperado.
O DANFE pode ser gerado mesmo com `cStat=225` (lote aceito com chave de 44 dígitos). A seção de protocolo no DANFE exibirá `cStat=225` em vez de `nProt`, que é o comportamento correto para NF-es não autorizadas.

### R4 — 9 arquivos não versionados
**Probabilidade**: Risco ativo. **Impacto**: Alto.
Todo o trabalho desta sessão está uncommitted. Commit manual a ser realizado por Bruno após revisão deste relatório.

---

## 7. Próximo passo recomendado

**Prioridade 1 — Commit do trabalho desta sessão** (9 arquivos)
Realizado manualmente por Bruno.

**Prioridade 2 — Integração time chinês**
Fornecer credenciais OPERADOR + URL HOM → time executa smoke test → go/no-go para integração OMS.

**Prioridade 3 — Certificados A1 PRD**
Bloqueador crítico de go-live. Sem certificado real com CNPJ de produção, `tpAmb=1` não é possível.

**Prioridade 4 — CI/CD smoke test**
Automatizar o ciclo build → deploy → healthcheck para garantir que HOM nunca fique desatualizado após um commit.

---

## 8. Ponto exato de retomada

**Branch**: `fix/sefaz-xml-structure`  
**Testes**: 66/66 (borurio-web) + 33/33 + 1 skip (borurio-fiscal)  
**HOM**: UP — Flyway `v024` — porta 8081  
**Arquivos pendentes de commit**: 9 (5 código DANFE + 3 docs + 1 relatório)  
**Fase técnica atual**: Fase 12-B CONCLUÍDA — próxima é integração OMS (time chinês) + PRD prep  
**DANFE**: disponível em `GET /api/fiscal/nfe/{chave}/danfe` — autenticado, retorna `application/pdf`

---

*Relatório gerado em 18/05/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
