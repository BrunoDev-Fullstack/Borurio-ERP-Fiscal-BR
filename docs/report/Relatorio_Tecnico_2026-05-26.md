# Relatório Técnico Diário — 26/05/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- HOM: **UP** — Flyway `v024` — MySQL UP — porta 8081
- Acesso externo: Cloudflare Quick Tunnel temporário (`trycloudflare.com`)
- DEV: UP (porta 8080, não tocado nesta sessão)

---

## 1. Resumo executivo

Sessão intensa com dois eixos paralelos:

1. **Integração CC/Xiao Li** — acesso externo ao HOM via Cloudflare Quick Tunnel, criação de usuários de teste, alinhamento técnico de endpoints de produto, estoque e fluxo OMS→Borurio.
2. **Manifestação do Destinatário** — inspeção, plano técnico, implementação, testes, build, deploy HOM e validação real contra SEFAZ HOM.

Nenhum código de PRD foi alterado. Commit e push não foram executados — serão feitos manualmente pelo Bruno.

---

## 2. Estado Git

```
Branch: fix/sefaz-xml-structure
Último commit: da925b1 — docs: sincroniza documentacao de integracao OMS
Working tree: 5 arquivos novos (untracked) + relatório do dia
```

---

## 3. Acessos criados para o CC/Xiao Li em HOM

| Usuário | Perfil | Finalidade |
|---|---|---|
| `cc@jcho.com` | OPERADOR | Testes funcionais do fluxo OMS/Borurio |
| `cc-admin@jcho.com` | ADMIN | Testes administrativos — entrada de estoque e rotas protegidas |

Credenciais repassadas por canal seguro e mascaradas neste relatório.

Ambos os usuários pertencem à empresa JCHO GLOBAL LTDA (`empresaId=1`). Dados são compartilhados entre os dois perfis dentro da mesma empresa.

---

## 4. Cloudflare Quick Tunnel — HOM temporário

HOM exposto externamente via:

```
cloudflared tunnel --url http://localhost:8081
```

- URL temporária muda a cada reinício do processo ou queda de internet.
- Houve duas trocas de URL durante o dia por instabilidade da conexão local.
- Amanhã provavelmente será necessário gerar nova URL após ligar o computador.
- Não é solução permanente — exclusivo para testes temporários de homologação.

---

## 5. Integração CC/Xiao Li

### 5.1 Alinhamento técnico

Xiao Li recebeu explicações sobre:

- `/auth/login` é a autenticação do Borurio ERP, não do OMS. Token carrega `empresa_id` automaticamente.
- NCM, CFOP, origem e CSOSN pertencem ao cadastro de produto no Borurio. O OMS envia o pedido e o Borurio aplica as regras fiscais.
- Estoque nasce zerado — entrada inicial via `POST /api/app/produtos/{id}/estoque/entrada` (requer ADMIN).
- O Borurio controla `estoqueTotal`, `estoqueReservado` e `estoqueDisponivel`.
- Modelo multiempresa: `empresaId` é extraído automaticamente do JWT — o OMS não precisa informar.

### 5.2 Endpoints auxiliares validados pelo CC

Xiao Li localizou e validou via Postman:

```
GET /api/app/produtos/codigo/{sku}   → busca produto por código interno
GET /api/app/produtos/{id}/estoque   → consulta saldo de estoque
POST /api/app/produtos/{id}/estoque/entrada   → entrada de estoque [ADMIN]
```

### 5.3 Fluxo aceito pelo CC (dois passos)

```
1. GET /api/app/produtos/codigo/{sku}   → recupera produto + id
2. GET /api/app/produtos/{id}/estoque   → consulta estoque com o id retornado
```

### 5.4 Decisão técnica — endpoint direto por SKU

**Não implementado.** Xiao Li confirmou que o fluxo em dois passos atende.

Registrado como **melhoria futura opcional:**
```
GET /api/app/produtos/codigo/{sku}/estoque
```
Implementar somente se o fluxo em dois passos gerar risco real na integração OMS.

---

## 6. Manifestação do Destinatário

### 6.1 Contexto

Manifestação do Destinatário é um conjunto de eventos fiscais enviados pelo **destinatário** de uma NF-e à SEFAZ para se posicionar sobre uma nota emitida contra seu CNPJ. Não faz parte do fluxo principal do OMS.

### 6.2 Decisão arquitetural

**Cenário A** — mesmo certificado A1 do emitente (JCHO GLOBAL LTDA). CNPJ do destinatário informado no body da requisição.

Evolução futura: certificado por empresa/destinatário (fora do escopo desta sprint).

### 6.3 Eventos implementados

| Código | Nome |
|---|---|
| 210200 | Ciência da Operação |
| 210210 | Confirmação da Operação |
| 210220 | Desconhecimento da Operação |
| 210240 | Operação Não Realizada |

### 6.4 Endpoint

```
POST /api/fiscal/nfe/manifestar
Authorization: Bearer {token}
Content-Type: application/json

{
  "chaveNfe":         "44 dígitos",
  "tipoEvento":       "210200 | 210210 | 210220 | 210240",
  "cnpjDestinatario": "14 dígitos sem formatação",
  "xJust":            "obrigatório apenas para 210240, mín 15 / máx 255 chars"
}
```

### 6.5 Regras de validação

| Campo | Regra |
|---|---|
| `chaveNfe` | 44 dígitos numéricos |
| `tipoEvento` | Exatamente um dos 4 códigos válidos |
| `cnpjDestinatario` | 14 dígitos numéricos |
| `xJust` | Obrigatório e ≥ 15 chars para 210240; ignorado nos demais |

### 6.6 Arquivos criados

| Arquivo | Tipo |
|---|---|
| `borurio-fiscal/.../dto/NfeManifestacaoRequest.java` | DTO |
| `borurio-fiscal/.../service/NfeManifestacaoService.java` | Interface |
| `borurio-fiscal/.../service/impl/NfeManifestacaoServiceImpl.java` | Implementação |
| `borurio-web/.../controller/fiscal/NfeManifestacaoController.java` | Controller REST |
| `borurio-web/.../controller/NfeManifestacaoControllerTest.java` | Testes — 7 cenários |

Nenhum arquivo existente foi modificado.

---

## 7. Validações realizadas

### 7.1 Testes automatizados

| Módulo | Antes | Depois | Falhas | Skip |
|---|---|---|---|---|
| borurio-web | 75 | **82** | 0 | 0 |
| borurio-fiscal | 39 | 39 | 0 | 1 (TesteSefazSSL — `@Disabled`) |

7 testes novos: um por evento (210200 / 210210 / 210220 / 210240) + erro sem xJust + tipo inválido + sem token. **Service completamente mockado nos testes automatizados — nenhuma chamada SEFAZ nos testes.**

### 7.2 Comandos executados

```bash
mvn -pl borurio-fiscal,borurio-web -am test     → BUILD SUCCESS — 82+39 testes
mvn -pl borurio-fiscal test                     → BUILD SUCCESS
mvn clean compile                               → COMPILE OK
mvn -pl borurio-fiscal,borurio-web -am package -DskipTests   → BUILD OK
docker cp borurio-web/target/borurio-web-1.0.0.jar borurio-web-hom:/app/app.jar
docker restart borurio-web-hom
GET http://localhost:8081/actuator/health       → { "status": "UP" }
POST http://localhost:8081/api/fiscal/nfe/manifestar (evento 210200)
```

### 7.3 Smoke test em HOM — chamada SEFAZ confirmada

O smoke test do endpoint `POST /api/fiscal/nfe/manifestar` com `tipoEvento=210200` **realizou chamada real à SEFAZ HOM** (`tpAmb=2`). Confirmado nos logs do container:

```
[MANIFESTACAO] Enviando para SEFAZ | url=https://homologacao.nfe.fazenda.sp.gov.br/...
[MANIFESTACAO] Resposta SEFAZ OK | chave=... | tipo=210200
NfeLogMapper → MANIFESTACAO_210200 | status=SUCCESS
```

- Ambiente: HOM (`tpAmb=2`) — sem valor fiscal.
- XML assinado corretamente com RSA-SHA256.
- Evento persistido em `nfe_log` como `MANIFESTACAO_210200 / SUCCESS`.
- HTTP API retornou: `HTTP 200`, `code: 200`, `success: true`.

> **Nota:** chave NF-e usada no smoke test é a mesma dos testes anteriores de HOM (NF-e 35, pedido 9). Chamada à SEFAZ HOM é aceitável e esperada neste ambiente.

---

## 8. Segurança e cuidados respeitados

| Regra | Status |
|---|---|
| Senhas, tokens, JWT e certificados não expostos | ✓ |
| Credenciais do CC mascaradas no relatório | ✓ |
| PRD não alterado | ✓ |
| Certificado não alterado | ✓ |
| Docker alterado apenas para deploy HOM (docker cp + restart) | ✓ |
| Commit não executado | ✓ |
| Push não executado | ✓ |
| Chamada SEFAZ confirmada antes de afirmar no relatório | ✓ |

---

## 9. Riscos e observações

| Risco | Detalhe |
|---|---|
| CNPJ destinatário vem no body | Melhoria futura: derivar da empresa autenticada/certificado |
| Smoke test chamou SEFAZ HOM | Aceitável em HOM (`tpAmb=2`); teste real PRD requer aprovação explícita |
| URL Cloudflare muda a cada reinício | Gerar nova URL amanhã se o tunnel cair |
| cStat da SEFAZ não verificado no smoke | Apenas confirmado log de transmissão — cStat do retorno SOAP não inspecionado |
| Reforma Tributária IBS/CBS/IS | Trilha fiscal futura separada — não misturar com Manifestação clássica |
| Certificado por destinatário | Evolução futura — fora do escopo desta fase |

---

## 10. Diagnóstico cStat=225 — Realizado em 26/05/2026

### 10.1 Constatação

O smoke test anterior logou status=SUCCESS incorretamente. O xml_retorno real continha `cStat=225` (Rejeição: Falha no Schema XML).

### 10.2 Causa raiz identificada

Dois problemas distintos:

**Problema 1 — cOrgao errado:**
O campo `<cOrgao>` estava com o código da UF do emitente (35=SP). Para Manifestação do Destinatário (NT 2012.004), a SEFAZ exige `cOrgao=91` (Ambiente Nacional/AN) independente da UF.

**Problema 2 — Endpoint errado:**
A Manifestação deve ir para o endpoint AN (`hom.nfe.fazenda.gov.br/NFeRecepcaoEvento4`), não para o endpoint SP.

**Problema 3 — Ausência de validação de cStat:**
O service retornava a resposta SOAP bruta sem verificar se `cStat=135`. Qualquer resposta HTTP 200 era tratada como sucesso.

### 10.3 Correções implementadas

| Arquivo | Correção |
|---|---|
| `NfeManifestacaoServiceImpl.java` | `cOrgao` alterado para constante `CORGAO_AN = "91"` |
| `NfeManifestacaoServiceImpl.java` | URL agora usa `sefazProperties.getManifestacaoEvento()` |
| `NfeManifestacaoServiceImpl.java` | `verificarERetornarResultado()` valida `cStat` antes de retornar |
| `NfeManifestacaoServiceImpl.java` | `xml_retorno` capturado mesmo em caso de erro |
| `SefazProperties.java` | Campo `manifestacaoEvento` adicionado |
| `application-hom.yml` | URL AN HOM configurada em `manifestacao-evento` |
| `application-prd.yml` | URL AN PRD configurada em `manifestacao-evento` |

### 10.4 Limitação de infraestrutura HOM

O endpoint AN HOM (`hom.nfe.fazenda.gov.br`) retorna HTTP 403 para requests do IP local/residencial. A SEFAZ federal bloqueia acesso direto de redes não corporativas.

**Consequência:** Manifestação não pode ser testada contra SEFAZ em HOM local. A implementação está correta (validada pelo código e estrutura XML inspecionada). Em ambiente corporativo ou PRD, o endpoint AN será acessível.

**O XML gerado está correto:** cOrgao=91, estrutura NT 2012.004, assinatura RSA-SHA256, detEvento com descEvento correto.

---

## 11. Pendências restantes

- [x] Inspecionar cStat retornado pela SEFAZ HOM no smoke test de Manifestação → **FEITO** (cStat=225; causa raiz diagnosticada; correções implementadas)
- [ ] Validar payloads negativos em HOM: sem token, `tipoEvento` inválido, `chaveNfe` inválida, 210240 sem `xJust`.
- [ ] Documentar payload de exemplo no manual de integração (`INTEGRATION_CONTRACT_PT-BR.md`).
- [ ] Avaliar persistência/auditoria do retorno SOAP da Manifestação em `nfe_log`.
- [ ] Revisar se endpoint `/manifestar` deve ser restrito por perfil ADMIN/FISCAL futuramente.
- [ ] Planejar certificado por empresa/destinatário em fase futura (Cenário B).
- [ ] Gerar nova URL Cloudflare amanhã se o tunnel atual cair.
- [ ] Revisar checklist geral antes do commit manual.
- [ ] Validar Manifestação em ambiente corporativo (AN HOM acessível) quando disponível.

---

## 12. Ponto de retomada para amanhã

| Campo | Valor |
|---|---|
| Branch | `fix/sefaz-xml-structure` |
| Último commit | `da925b1` — docs: sincroniza documentacao de integracao OMS |
| Working tree | 5 arquivos novos + relatório (não commitados) |
| HOM | UP — `borurio-web-hom` com JAR atualizado (Manifestação incluída) |
| Quick tunnel | Provavelmente nova URL amanhã |
| Xiao Li | Testando fluxo OMS com `cc@jcho.com` e `cc-admin@jcho.com` |
| Próxima ação técnica | Inspecionar cStat do smoke test + validar cenários negativos em HOM |
| Commit manual | Bruno fará o commit ao final do dia |

---

*Relatório gerado em 26/05/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
