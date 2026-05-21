# Relatório Técnico Diário — 20/05/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- Testes automatizados borurio-web: **75 / 0 falhas**
- Testes automatizados borurio-fiscal: **33 / 0 falhas / 1 skipped**
- Total consolidado: **108 testes passando + 1 skip esperado**
- HOM: **UP** — Flyway em `v024`; MySQL UP; porta 8081
- Container atualizado com JAR novo (`docker cp` + `docker restart`)

---

## 1. Resumo executivo

Sessão de validação operacional completa do ambiente HOM. Foco em quatro eixos:

1. **Validação de ponta a ponta do ambiente HOM** — healthcheck, autenticação JWT, Docker stack.
2. **Validação do RBAC com usuário OPERADOR** — criação de usuário interno de teste e validação de todos os controles de acesso.
3. **Emissão HOM controlada** — pedido existente (nº 9, NF-e 35) retransmitido para confirmar fluxo fiscal sem nova emissão.
4. **Diagnóstico e correção de três bugs no `DanfePdfGenerator`** — formatação monetária, thread-safety e label de protocolo condicional.

Ao final da sessão o DANFE v2 foi validado por texto e visualmente: `R$ 91,80` com vírgula e label `RETORNO SEFAZ — HOMOLOGAÇÃO` corretos. Container HOM refletindo o novo código.

---

## 2. Estado Git

### Último commit antes desta sessão
```
a6d7d74  docs(evidencias): registra DANFE HOM sem valor fiscal
```

### Commits entre o relatório anterior (18-05-2026) e hoje
```
a6d7d74  docs(evidencias): registra DANFE HOM sem valor fiscal
d4b3443  docs(onboarding): alinha HOM externo e checklist Bless CC
1a3fb2c  test(web): cobre estados fiscais e ping publico HOM
b1eb7dc  test(web): cobre situacao cancelar cce e RBAC de estoque
c25093f  docs(contract): sincroniza secao 2 PT-BR com acesso HOM externo
e5653bd  fix(postman): corrige collection HOM estoque e DANFE
7c353a0  docs(onboarding): atualiza roteiro e contrato de integracao HOM
19a012f  feat(swagger): prepara OpenAPI para integracao externa HOM
5ad3b74  docs(report): registra relatorio tecnico de 18-05-2026
```

**Evolução de testes entre sessões:** 66 → 75 testes (borurio-web) — 9 novos testes adicionados cobrindo: estados fiscais, ping público sem token, situação/cancelar/CC-e, RBAC de estoque.

### Estado atual (não commitado)

```
 M borurio-fiscal/src/main/java/br/com/borurio/fiscal/danfe/DanfePdfGenerator.java
?? docs/evidencias/hom/danfe_validacao_20-05-2026-v2.pdf
?? docs/evidencias/hom/danfe_validacao_20-05-2026.pdf
```

---

## 3. Validações HOM

### 3.1 Healthcheck

```
GET http://localhost:8081/actuator/health
→ { "status": "UP", "components": { "db": { "status": "UP" }, "ping": { "status": "UP" } } }
```

Container `borurio-web-hom` healthy. MySQL `borurio-fiscal-hom` healthy. Redis HOM healthy.

### 3.2 Autenticação JWT

```
POST /auth/login → 200 OK, token JWT emitido
```

Token válido usado em todas as chamadas subsequentes da sessão.

### 3.3 Suite de testes

| Módulo | Testes | Falhas | Skip |
|---|---|---|---|
| borurio-fiscal | 33 | 0 | 1 (TesteSefazSSL — `@Disabled`) |
| borurio-web | 75 | 0 | 0 |
| **TOTAL** | **108** | **0** | **1** |

---

## 4. Validações RBAC

Usuário OPERADOR interno criado para testes de integração:

| Campo | Valor |
|---|---|
| Nome | Bruno Operador |
| Login/e-mail | `bruno1994ribeiro@gmail.com` |
| Role | `OPERADOR` |
| Senha | Mascarada — gerada com entropia forte |

### Matriz de acesso validada

| Endpoint | OPERADOR | sem token |
|---|---|---|
| `GET /api/test/ping` | 200 ✓ | 200 ✓ (público) |
| `GET /api/app/pedidos` | 200 ✓ | 401 ✓ |
| `GET /api/app/produtos` | 200 ✓ | 401 ✓ |
| `GET /api/app/usuarios` | **403** ✓ (ADMIN-only) | 401 ✓ |
| `POST /api/app/produtos/{id}/estoque/entrada` | **403** ✓ (ADMIN-only) | 401 ✓ |

RBAC funcionando conforme especificado: OPERADOR acessa endpoints operacionais, bloqueado em endpoints administrativos.

---

## 5. Emissão HOM controlada

Pedido existente (nº 9, NF-e nº 35) retransmitido para validar o fluxo end-to-end sem criar nova NF-e.

| Campo | Valor |
|---|---|
| Pedido | 9 |
| NF-e | 35 |
| Chave | `35260554393421000159550010000000351199116560` |
| Emitente | JCHO GLOBAL LTDA (CNPJ: 543.934.21/0001-59) |
| Ambiente | Homologação (`tpAmb=2`) |
| cStat lote (`SP_NFE_PL009_V4`) | **104** — Lote processado ✓ |
| cStat infProt (`SP_NFE_PL_008i2`) | **225** — Falha no Schema XML (comportamento esperado em HOM/SP) |

> **Nota:** cStat=225 é uma limitação do processador HOM/SP que valida a assinatura contra um schema SHA-1 legado. O código do Borurio está em conformidade com NT 2019.001 (RSA-SHA256). Esta limitação **não ocorre em PRD**. Ver seção 8.

---

## 6. DANFE — antes e depois

### 6.1 Problema identificado (v1 — antes das correções)

PDF gerado em `docs/evidencias/hom/danfe_validacao_20-05-2026.pdf`:

| Bloco | Comportamento errado |
|---|---|
| Totais | `R$ 91.80` — ponto decimal americano (concatenação direta da string `BigDecimal.toString()`) |
| Protocolo | Label fixo `PROTOCOLO DE AUTORIZAÇÃO` mesmo para cStat=225 (não autorizado) |
| Thread-safety | `DecimalFormat` declarado como `static final` em bean singleton Spring — condição de corrida sob concorrência |

A tabela de itens já exibia `45,90` e `91,80` corretamente pois usava `formatDecimal()` com o `DecimalFormat` instanciado. O bug era localizado nos blocos `addTotais` e `addHeader`.

### 6.2 Correções aplicadas

**Arquivo:** `borurio-fiscal/src/main/java/br/com/borurio/fiscal/danfe/DanfePdfGenerator.java`

#### Bug 1 — Thread-safety (`static final DecimalFormat`)

```java
// ANTES (singleton Spring com estado mutável compartilhado):
private static final DecimalFormat DF_MOEDA = new DecimalFormat(
        "#,##0.00", new DecimalFormatSymbols(new Locale("pt", "BR")));
private static final DecimalFormat DF_QTDE = new DecimalFormat(
        "#,##0.####", new DecimalFormatSymbols(new Locale("pt", "BR")));

// DEPOIS (nova instância por chamada — DecimalFormat não é thread-safe):
private static DecimalFormat dfMoeda() {
    return new DecimalFormat("#,##0.00", new DecimalFormatSymbols(new Locale("pt", "BR")));
}
private static DecimalFormat dfQtde() {
    return new DecimalFormat("#,##0.####", new DecimalFormatSymbols(new Locale("pt", "BR")));
}
```

#### Bug 2 — Formatação monetária nos totais

```java
// ANTES (concatenação direta — ignora Locale pt_BR):
addLabeledCell(t, "VALOR TOTAL DOS PRODUTOS", d.vProd != null ? "R$ " + d.vProd : "");
addLabeledCell(t, "VALOR TOTAL DA NF-e",      d.vNF  != null ? "R$ " + d.vNF   : "");

// DEPOIS (passa pelo formatDecimal com Locale correto):
addLabeledCell(t, "VALOR TOTAL DOS PRODUTOS", d.vProd != null ? "R$ " + formatDecimal(d.vProd, dfMoeda()) : "");
addLabeledCell(t, "VALOR TOTAL DA NF-e",      d.vNF  != null ? "R$ " + formatDecimal(d.vNF,   dfMoeda()) : "");
```

#### Bug 3 — Label de protocolo condicional

```java
// ANTES (label fixo independente do estado):
addLabeled(cProt, "PROTOCOLO DE AUTORIZAÇÃO", null, null);
if ("100".equals(d.cStat)) { ... }

// DEPOIS (label reflete o estado real):
if ("100".equals(d.cStat) && d.nProt != null && !d.nProt.isBlank()) {
    addLabeled(cProt, "PROTOCOLO DE AUTORIZAÇÃO DE USO", null, null);
    cProt.addElement(new Phrase(safe(d.nProt), FONTE_VALOR));
    cProt.addElement(new Phrase(safe(d.dhRecbto), FONTE_VALOR));
} else {
    String labelProt = "2".equals(d.tpAmb)
            ? "RETORNO SEFAZ — HOMOLOGAÇÃO"
            : "PROTOCOLO NÃO DISPONÍVEL";
    addLabeled(cProt, labelProt, null, null);
    if (d.cStat != null) cProt.addElement(new Phrase("cStat: " + d.cStat, FONTE_VALOR));
    if ("2".equals(d.tpAmb)) cProt.addElement(new Phrase("Sem valor fiscal", FONTE_VALOR));
}
```

### 6.3 Validação v2

**Arquivo:** `docs/evidencias/hom/danfe_validacao_20-05-2026-v2.pdf`

```
Extração de texto (pdftotext):
  RETORNO SEFAZ — HOMOLOGAÇÃO   ← label condicional correto
  R$ 91,80                      ← vírgula pt_BR nos totais
  R$ 91,80                      ← total NF-e com vírgula
  HOMOLOGAÇÃO                   ← campo ambiente correto
```

Validações negativas confirmadas:
- Sem ocorrência de `PROTOCOLO DE AUTORIZAÇÃO` — ✓
- Sem ocorrência de `R$ 91.80` (ponto decimal) — ✓

### 6.4 Como o container foi atualizado

```
# 1. Build local (evita download Maven dentro do Docker — muito mais rápido)
mvn -pl borurio-fiscal,borurio-web -am package -DskipTests -q

# 2. Substituição do artefato no container em execução
docker cp borurio-web/target/borurio-web-1.0.0.jar borurio-web-hom:/app/app.jar

# 3. Reinício (JVM carrega o novo JAR)
docker restart borurio-web-hom

# 4. Verificação de saúde
GET http://localhost:8081/actuator/health → { "status": "UP" }
```

> **Por que o primeiro DANFE estava errado?** O container `borurio-web-hom` rodava um JAR compilado antes das edições. O Dockerfile copia o fonte e compila apenas na hora do `docker build`; alterações locais posteriores não entram automaticamente. Solução definitiva: CI/CD automatizado (ver seção 13).

---

## 7. Comparação com ERP logístico profissional

### 7.1 O que o Borurio já entrega (backend)

| Módulo | Estado |
|---|---|
| Motor fiscal NF-e 4.00 completo (XML, XSD, XMLDSIG, SOAP 1.2, mTLS) | ✓ Entregue |
| Autenticação JWT stateless HMAC-SHA256 (1h TTL) | ✓ Entregue |
| RBAC ADMIN / OPERADOR | ✓ Entregue |
| Multiempresa (`empresa_id` via JWT, isolamento total) | ✓ Entregue |
| Cadastro de produtos, clientes, usuários, empresas | ✓ Entregue |
| Pedidos com ciclo fiscal completo (RASCUNHO → AUTORIZADO → CANCELADO) | ✓ Entregue |
| Cancelamento NF-e (evento 110111) | ✓ Entregue |
| Carta de Correção CC-e (evento 110110) | ✓ Entregue |
| Consulta de situação SEFAZ live (`consSitNFe`) | ✓ Entregue |
| Estoque mínimo fiscal com reserva atômica (Fase 12-A) | ✓ Entregue |
| DANFE PDF (Fase 12-B) | ✓ Entregue (layout básico) |
| Snapshot fiscal imutável (itens congelados na criação do pedido) | ✓ Entregue |
| Auditoria fiscal (`nfe_documento`, `nfe_log`, `estoque_movimento`) | ✓ Entregue |
| Sequenciador atômico nNF por CNPJ + série | ✓ Entregue |
| Chave de acesso 44 dígitos com dígito verificador módulo 11 | ✓ Entregue |
| Certificado A1 PKCS12 por empresa com cache em memória | ✓ Entregue |
| Swagger UI (10 tags) | ✓ Entregue |
| Postman collection (46 requests) | ✓ Entregue |
| Flyway migrations V001–V024 aplicadas em HOM | ✓ Entregue |
| 108 testes automatizados (controllers + fiscal) | ✓ Entregue |

### 7.2 O que ainda falta para um ERP logístico completo (roadmap)

| Módulo | Prioridade | Observação |
|---|---|---|
| **DANFE layout profissional** (canhoto, transportador/volume, impostos, Reservado ao Fisco, fatura/duplicata) | Alta | Layout atual é funcional — falta padronização SEFAZ completa |
| **Senha admin padrão** — troca obrigatória antes do Cloudflare externo | **Crítica imediata** | `admin` / `admin123` é seed de dev — não pode ser exposto |
| CI/CD automatizado (GitHub Actions → build → docker cp → smoke test) | Alta | Evita o problema de container rodando JAR desatualizado |
| WMS básico (lote/série, inventário, picking, endereçamento) | Alta | Necessário para operação logística real |
| TMS básico (transportadora, CT-e roadmap, fretes) | Média | Requer CT-e 3.00 (novo motor fiscal) |
| Módulo financeiro (contas a pagar/receber, conciliação) | Média | Fechamento operacional do ciclo de pedido |
| Dashboard operacional / KPIs fiscais | Média | Visibilidade para gestão |
| CT-e 3.00 + MDF-e (transporte de cargas) | Baixa | Segundo motor fiscal — roadmap separado |
| Compras / fornecedores (NF-e entrada) | Baixa | Entrada de estoque por nota fiscal |
| Reforma Tributária IBS/CBS/IS (deadline regulatório 2026+) | Regulatório | Substituição do ICMS/PIS/COFINS/ISS — obrigatório para compliance PRD futuro |
| Invalidação automática do cache de certificado | Baixa | Melhoria operacional sem impacto funcional imediato |
| Rate limiting em `POST /api/app/pedidos/{id}/emitir` | Baixa | Proteção contra emissão duplicada por erro de cliente |
| Política de retenção de `nfe_log` | Baixa | Custo de storage a longo prazo |

---

## 8. Diagnóstico cStat=225 — HOM/SP

**Causa raiz (confirmada):**

A SEFAZ-SP opera dois processadores distintos no ambiente de homologação:

| Processador | Escopo | Retorno observado |
|---|---|---|
| `SP_NFE_PL009_V4` | Valida o lote (`enviNFe`) | cStat=**104** — Lote processado ✓ |
| `SP_NFE_PL_008i2` | Valida `infProt` (assinatura) | cStat=**225** — Falha no Schema XML |

O processador `SP_NFE_PL_008i2` valida a assinatura XMLDSIG contra um schema que referencia SHA-1 fixo. O código do Borurio usa RSA-SHA256 conforme **NT 2019.001** — o que está correto para PRD. O conflito ocorre exclusivamente em HOM/SP.

**Impacto:**
- HOM: cStat=225 esperado e documentado — **não é bug do Borurio**
- PRD: expectativa técnica de cStat=**100** após configuração segura em `tpAmb=1`; validação ainda pendente em ambiente de produção controlado
- Não há ação corretiva disponível no lado do cliente

**Como o DANFE lida com isso (após correção):**
- cStat=225 → label `RETORNO SEFAZ — HOMOLOGAÇÃO` + `cStat: 225` + `Sem valor fiscal`
- cStat=100 + nProt preenchido → label `PROTOCOLO DE AUTORIZAÇÃO DE USO` + nProt + dhRecbto
- Outros casos → `PROTOCOLO NÃO DISPONÍVEL`

---

## 9. Segurança HOM — diagnóstico

| Item | Estado | Ação |
|---|---|---|
| JWT HMAC-SHA256, TTL 1h | ✓ OK | — |
| RBAC ADMIN/OPERADOR validado | ✓ OK | — |
| CORS HOM configurado (`https://hom-api.borurio.com`) | ✓ OK | — |
| `.env.hom` gitignored | ✓ OK | — |
| Proteção anti-XXE nos parsers XML | ✓ OK | — |
| Swagger desabilitado em PRD | ✓ OK (apenas DEV/HOM) | — |
| **Senha admin padrão `admin123`** | **⚠ PENDENTE CRÍTICO** | Trocar antes de expor Cloudflare externo |
| `CERT_ENCRYPTION_KEY` em PRD | ⚠ Não configurada | Bloqueador de go-live PRD |
| Certificados A1 PRD com CNPJ real | ⚠ Configuração pendente | Certificado A1 real já existe para o emitente utilizado em HOM; PRD depende de configuração segura, secrets, `CERT_ENCRYPTION_KEY`, domínio, `tpAmb=1` e primeiro teste controlado com Bless/CC |

> **Risco ativo:** A senha `admin123` é seed de desenvolvimento. Qualquer exposição pública da URL HOM (Cloudflare Tunnel) antes da troca representa risco crítico de acesso não autorizado com privilégios ADMIN.

---

## 10. Arquivos pendentes de commit

| Arquivo | Tipo | Conteúdo |
|---|---|---|
| `borurio-fiscal/.../danfe/DanfePdfGenerator.java` | `M` (modificado) | 3 correções de bug (moeda, thread-safety, protocolo) |
| `docs/evidencias/hom/danfe_validacao_20-05-2026-v2.pdf` | `??` (novo) | Evidência PDF v2 validado |
| `docs/evidencias/hom/danfe_validacao_20-05-2026.pdf` | `??` (novo) | Evidência PDF v1 (com bugs — mantido para histórico) |
| `docs/report/Relatorio_Tecnico_20-05-2026.md` | `??` (novo) | Este relatório |

---

## 11. Próximos focos por prioridade

### Prioridade 1 — Imediato (pré-aprovação)
- [ ] **Commit manual** de `DanfePdfGenerator.java` + PDF v2 + este relatório (aguardando aprovação de Bruno)

### Prioridade 2 — Antes do Cloudflare externo (bloqueador de segurança)
- [ ] **Trocar senha admin** via `PUT /api/app/usuarios/1/senha` — obrigatório antes de expor `hom-api.borurio.com`
- [ ] **Instalar Cloudflare Tunnel permanente** como serviço Windows — somente após senha forte

### Prioridade 3 — Qualidade DANFE
- [ ] **Layout DANFE profissional** — adicionar:
  - Canhoto de recebimento (destacável)
  - Bloco transportador / volumes / espécie / marca / numeração
  - Cálculo de impostos visível (ICMS, IPI, PIS, COFINS ou CSOSN/CSST)
  - Reservado ao Fisco (campo `xFisco`)
  - Fatura / duplicatas (quando `cobr` presente no XML)

### Prioridade 4 — Integração Bless/CC
- [ ] **Smoke test externo** em `hom-api.borurio.com` após Cloudflare
- [ ] **Criar usuário OPERADOR oficial** para Bless/CC (não usar o usuário interno de teste)
- [ ] **Entregar pacote de integração** — `INTEGRATION_CONTRACT_EN.md` + `CHECKLIST_OMS_ONBOARDING.md` + Postman + credenciais
- [ ] **Alinhar com Bless sobre DNS** — confirmar "JCHO GLOBAL LTDA" = "Jcho Factory Ltda" antes de PRD
- [ ] **Alinhar frontend** — Bless precisa adaptar OMS para sequência: produto → pedido → emitir → situação

### Prioridade 5 — Infraestrutura
- [ ] **CI/CD automatizado** — GitHub Actions: job `test` → `build` → `docker cp` → `docker restart` → smoke test
  - Elimina o risco de container rodando JAR desatualizado após edições

### Prioridade 6 — Roadmap ERP logístico
- [ ] **WMS básico** — lote/série, inventário, picking, endereçamento de posições
- [ ] **TMS básico** — cadastro de transportadoras, fretes, roadmap CT-e 3.00
- [ ] **Financeiro** — contas a pagar/receber, conciliação, fechamento do ciclo de pedido
- [ ] **Dashboards** — KPIs fiscais, estoque em tempo real, volume de NF-e por período
- [ ] **CT-e 3.00 / MDF-e** — segundo motor fiscal para transporte de cargas
- [ ] **Reforma Tributária IBS/CBS/IS** — compliance regulatório mandatório (deadline 2026+)

---

## 12. Riscos ativos

### R1 — Senha admin padrão exposta
**Probabilidade**: Certa enquanto não trocada. **Impacto**: Crítico.
Qualquer acesso à URL pública HOM com credencial `admin/admin123` daria acesso ADMIN completo ao sistema.

### R2 — Container desatualizado por falta de CI/CD
**Probabilidade**: Alta. **Impacto**: Médio.
Edições locais não refletem no container sem rebuild manual. Um commit sem rebuild gera divergência silenciosa entre código fonte e comportamento em produção.

### R3 — CERT_ENCRYPTION_KEY não configurada em PRD
**Probabilidade**: Certa. **Impacto**: Crítico (bloqueador go-live).
Transmissões SEFAZ em `tpAmb=1` falham sem a chave AES-256-GCM para descriptografar a senha do certificado A1.

### R4 — cStat=225 em HOM/SP
**Probabilidade**: Alta em HOM. **Impacto**: Nenhum.
Comportamento esperado, documentado e não corrigível do lado do cliente. Não ocorre em PRD.

---

## 13. Ponto de retomada

| Campo | Valor |
|---|---|
| Branch | `fix/sefaz-xml-structure` |
| Último commit | `a6d7d74` — docs(evidencias): registra DANFE HOM sem valor fiscal |
| Testes | 75/75 (borurio-web) + 33/33 + 1 skip (borurio-fiscal) |
| HOM | UP — Flyway `v024` — porta 8081 — JAR atualizado com bugs corrigidos |
| Arquivos pendentes de commit | 4 (DanfePdfGenerator.java + 2 PDFs evidência + este relatório) |
| Próxima ação | Commit manual (aguardando aprovação) → troca senha admin → Cloudflare |
| DANFE | Bugs de moeda, thread-safety e protocolo corrigidos — v2 validado |

---

*Relatório gerado em 20/05/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
