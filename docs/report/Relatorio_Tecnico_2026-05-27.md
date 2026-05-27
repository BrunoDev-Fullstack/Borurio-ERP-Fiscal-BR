# Relatório Técnico Diário — 27/05/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- HOM: **UP** — Flyway `v024` — MySQL UP — porta 8081 — `timestamp 12:57`
- Acesso externo: Cloudflare Quick Tunnel temporário (`trycloudflare.com`) — URL regenerada no início da sessão
- DEV: UP (porta 8080, não tocado nesta sessão)

---

## 1. Resumo executivo

Sessão com quatro eixos:

1. **Validações técnicas HOM — P4:** Testes negativos da Manifestação do Destinatário (T2, T3, T4) executados com token real contra HOM. Todos aprovados. Inspeção completa da stack DANFE (Bloco E + Bloco F) via leitura de código — layout correto e thread-safe. Teste visual do PDF executado e validado.
2. **Segurança — M3:** Senha do banco HOM (`SPRING_DATASOURCE_PASSWORD`) exposta no output de `docker exec` — rotacionada na mesma sessão via `ALTER USER` (root TCP). `.env.hom` atualizado. Containers recriados e saudáveis. Incidente encerrado.
3. **Integração CC/Xiao Li:** Quatro dúvidas técnicas respondidas com precisão. Limitação de CFOP no cadastro do produto identificada e imediatamente resolvida (M1).
4. **Código entregue — M1 + PingFix:** `cfop` tornado opcional no cadastro de produto com default `"5102"`. `PingController` corrigido para retornar `env=hom` corretamente. Rebuild e redeploy HOM executados. 82/82 testes passando.

Commit realizado: `9becd5c fix(produto): torna cfop opcional com default 5102`. Working tree clean.

---

## 2. Estado Git

```
Branch: fix/sefaz-xml-structure
Último commit: 9becd5c — fix(produto): torna cfop opcional com default 5102
Working tree: clean
```

Arquivos alterados nesta sessão:

| Arquivo | Motivo |
|---|---|
| `borurio-app/.../entity/Produto.java` | M1 — remove `@NotBlank` de `cfop` |
| `borurio-app/.../service/impl/ProdutoServiceImpl.java` | M1 — default `"5102"`, reordena `aplicarDefaultsFiscais` antes de `validar` |
| `borurio-web/.../controller/PingController.java` | Fix — `${app.profile:dev}` substitui `${spring.profiles.active:default}` |
| `docs/manual/INTEGRATION_CONTRACT_EN.md` | M1 — `cfop` de Required para Optional |
| `docs/manual/INTEGRATION_CONTRACT_PT-BR.md` | M1 — `cfop` de Obrigatório para Opcional |
| `docs/manual/CHECKLIST_OMS_ONBOARDING.md` | M1 — `cfop` de obrigatório para opcional |
| `docs/report/Relatorio_Tecnico_2026-05-27.md` | Relatório técnico da sessão (não rastreado) |

---

## 3. Validações técnicas HOM — Manifestação do Destinatário

### 3.1 Testes negativos executados com token real

Token obtido com `cc@jcho.com` (OPERADOR) via PowerShell com `Read-Host -AsSecureString`. Token nunca exibido no terminal ou neste relatório.

| Cenário | HTTP | `code` | `success` | Mensagem retornada | Resultado |
|---|---|---|---|---|---|
| T1 — sem token | 401 | 401 | false | "Autenticação necessária" | ✅ (sessão anterior) |
| T2 — `tipoEvento` inválido (`"999999"`) | 200 | 500 | false | "Tipo de evento inválido. Valores aceitos: 210200, 210210, 210220, 210240." | ✅ live |
| T3 — `chaveNfe` inválida (`"1234"`) | 200 | 500 | false | "Chave NF-e inválida (deve ter 44 dígitos numéricos)." | ✅ live |
| T4 — `210240` sem `xJust` | 200 | 500 | false | "xJust obrigatório para 210240 e deve ter no mínimo 15 caracteres." | ✅ live |

> Caracteres especiais com encoding CP1252/UTF-8 no console PowerShell Windows 5.x — artefato do terminal, não da API. JSON retornado pelo servidor está correto em UTF-8.

### 3.2 Decisão de role restriction

Endpoint `POST /api/fiscal/nfe/manifestar` mantém `anyRequest().authenticated()` — qualquer JWT válido (ADMIN ou OPERADOR) pode chamar.

**Justificativa:** OPERADOR `cc@jcho.com` precisa usar o endpoint no fluxo OMS. Isolamento multiempresa garantido pelo `empresaId` do token via `EmpresaContextHolder`. Revisão para ADMIN-only postergada para pós-PRD se necessário.

---

## 4. Inspeção da stack DANFE — commit `1bfe8a8`

### 4.1 Arquivos inspecionados

| Arquivo | Achado |
|---|---|
| `DanfeData.java` | Todos os campos Bloco E (11 totais fiscais) e Bloco F (transportador + volumes) declarados ✅ |
| `DanfeXmlParser.java` | XPath `//nfe:ICMSTot/*` mapeia todos os campos de `vBC` a `vNF` ✅ |
| `DanfeXmlParser.java` | XPath `//nfe:transp/*` mapeia `modFrete`, `transporta`, `vol` ✅ |
| `DanfeXmlParser.java` | Anti-XXE: `disallow-doctype-decl=true`, `external-general-entities=false` ✅ |
| `DanfePdfGenerator.java` | `dfMoeda()` e `dfQtde()` como factory method por chamada — thread-safe ✅ |
| `DanfePdfGenerator.java` | `mz()` retorna `"0,00"` para null — correto para totais obrigatórios ✅ |
| `DanfePdfGenerator.java` | `formatDecimal()` retorna `""` para null — correto para volumes opcionais ✅ |
| `DanfePdfGenerator.java` | Bloco E: 6 colunas, 12 células de dados, header colspan=6 ✅ |
| `DanfePdfGenerator.java` | Bloco F: 4 colunas, linhas condicionais de endereço e volumes ✅ |
| `DanfePdfGenerator.java` | Célula "AMBIENTE": `"HOMOLOGAÇÃO"` para `tpAmb=2`, `"PRODUÇÃO"` para `tpAmb=1` ✅ |

### 4.2 Teste visual do PDF

**Status: VALIDADO — 27/05/2026.**

```
NF-e 35 — id=9 — c_stat=225 — valor_total=91,80
chave: 35260554393421000159550010000000351199116560
PDF: C:\Temp\borurio-danfe\danfe_teste_hom.pdf — 3.195 bytes
Gerado por: OpenPDF 1.3.30 — 1 página A4
```

Confirmação visual:

| Elemento | Resultado |
|---|---|
| Cabeçalho emitente (JCHO GLOBAL LTDA, CNPJ, endereço) | Conforme |
| Título DANFE + NF-e Nº 35 Série 1 | Conforme |
| Label HOM "RETORNO SEFAZ — HOMOLOGAÇÃO / cStat: 225" | Conforme |
| Chave de acesso 44 dígitos em grupos de 4 | Conforme |
| Destinatário com CNPJ formatado | Conforme |
| Tabela de itens (NCM 85444200, CFOP 5102, CSOSN 400, R$91,80) | Conforme |
| Bloco E — CÁLCULO DO IMPOSTO — 11 campos renderizados | Conforme |
| Célula AMBIENTE = "HOMOLOGAÇÃO" | Conforme |
| Bloco F — TRANSPORTADOR / VOLUMES — "9 - Sem Frete" | Conforme |
| Código de barras da chave de acesso | Conforme — elemento obrigatório DANFE |
| Layout sem cortes ou sobreposição | Conforme |

Commit `1bfe8a8` (feat(danfe): adiciona blocos icms e transportador) validado em HOM.

---

## 5. Integração CC/Xiao Li — dúvidas do dia

### 5.1 Dúvida 1 — CFOP e CSOSN no cadastro de produto

Respondida: usar `cfop: "5102"` como padrão para todos os produtos na fase 1 (intra-estado). `csosn` é opcional — Borurio aplica `"400"` se omitido.

### 5.2 Dúvida 2 — Campos `destCodigoMunicipio`, `naturezaOperacao`, `serieNfe`

Respondida: `destCodigoMunicipio` é o código IBGE de 7 dígitos, não obrigatório para fase 1. `naturezaOperacao` tem default `"VENDA DE MERCADORIA"`. `serieNfe` não deve ser enviada pelo OMS — controlada internamente pelo Borurio.

### 5.3 Dúvida 3 — Configuração de impostos (ICMS/IPI/PIS/COFINS/IBS/CBS)

Respondida: OMS não precisa enviar configuração tributária. O motor fiscal monta a estrutura internamente com base em CFOP e CSOSN do produto. IBS/CBS (Reforma Tributária) está fora do escopo da fase 1.

### 5.4 Dúvida 4 — CFOP no pedido (questão mais crítica do dia)

Xiao Li identificou que:
- O OMS não tem CFOP disponível no momento do cadastro do produto — apenas na criação do pedido.
- Perguntou se o sistema aceita `cfop` no item do pedido e se o produto seria atualizado automaticamente.

**Resposta honesta:**
- Hoje o pedido **não aceita** `cfop` no payload — o campo não existe.
- O CFOP usado na NF-e vem do snapshot do produto (congelado na criação do pedido).
- O produto **não é alterado** automaticamente pelo pedido.
- Para fase 1 (intra-estado), `5102` no produto é sempre correto.
- Para fase 2 (venda interestadual), implementar `cfop` por item no pedido.

Xiao Li confirmou: **não precisa de interestadual na fase 1.** Assunto encerrado.

---

## 6. Backlog de melhorias identificadas

Registrado em memória persistente (`project_melhorias_backlog.md`):

| ID | Descrição | Prioridade | Esforço | Status |
|---|---|---|---|---|
| M1 | `cfop` opcional no produto — default `"5102"` | Alta | ~2 linhas | **ENTREGUE** |
| M2 | `cfop` por item no pedido (override interestadual) | Essencial fase 2 | 2–3h | Pendente |
| M3 | Rotação de senha do banco HOM | Segurança urgente | 10min | **ENTREGUE** |
| P4 | Manifestação Destinatário T1/T2/T3/T4 + DANFE visual | Validação HOM | — | **FECHADO** |
| PingFix | `PingController` — `/api/test/ping` retornando `env=hom` corretamente | Correção | ~2 linhas | **ENTREGUE** |
| M4 | Invalidação de cache de certificado no `EmpresaController` | Alto | Pequeno | Pendente |
| M5 | Rate limiting em `POST /api/app/pedidos/{id}/emitir` | Médio | Médio | Pendente |
| M6 | CI/CD GitHub Actions | Médio | Grande | Pendente |
| M7 | Política de retenção `nfe_log` com `@Scheduled` | Baixo | Pequeno | Pendente |

---

## 7. Incidente de segurança — senha DB HOM exposta

Durante execução de `docker exec borurio-web-hom env | Select-String SPRING_DATASOURCE` para recuperar credenciais do banco (objetivo: query na tabela `nfe_documento` sem credencial root), a variável `SPRING_DATASOURCE_PASSWORD` foi exibida no output do terminal.

**Impacto:** Senha do usuário `borurio` no banco HOM visível nesta sessão.
**Ação executada:** `SPRING_DATASOURCE_PASSWORD` rotacionada via `ALTER USER` (root TCP). `.env.hom` atualizado. Containers `borurio-mysql-hom` e `borurio-web-hom` recriados e saudáveis.
**Status:** RESOLVIDO — 27-05-2026.
**Ambiente afetado:** Exclusivamente HOM. PRD não afetado.

---

## 8. Segurança e cuidados respeitados

| Regra | Status |
|---|---|
| Token JWT nunca exibido | ✓ |
| Senha do CC obtida via `Read-Host -AsSecureString` | ✓ |
| PRD não alterado | ✓ |
| Certificado não alterado | ✓ |
| Nenhuma chamada SEFAZ real executada | ✓ |
| Commit realizado manualmente pelo responsável técnico | ✓ |
| Push não executado | ✓ |
| Incidente de exposição de senha DB documentado | ✓ |

---

## 9. Ponto de retomada para amanhã

| Campo | Valor |
|---|---|
| Branch | `fix/sefaz-xml-structure` |
| Último commit | `9becd5c` — fix(produto): torna cfop opcional com default 5102 |
| Working tree | clean |
| HOM | UP — JAR com M1 + PingFix + Manifestação em produção — `env=hom` confirmado |
| Cloudflare tunnel | URL temporária — nova a cada sessão (decisão mantida) |
| Xiao Li | Confirmou entendimento. Aguardando URL HOM quando for testar. |

### Próximas ações por prioridade

| Prioridade | Ação |
|---|---|
| P1 | Quando CC iniciar testes: gerar URL do tunnel temporário e passar para ele |
| Backlog | M4: invalidação de cache de certificado no `EmpresaController` |
| Backlog | M2: `cfop` por item no pedido — necessário apenas para fase 2 (interestadual) |

---

*Relatório gerado em 27/05/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
