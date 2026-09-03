# Documento de Integração
## Motor Fiscal Borurio BR — API REST
### ERP Logístico × NF-e 4.00 SEFAZ-SP

| Atributo               | Valor                                   |
|------------------------|-----------------------------------------|
| Versão                 | 1.13                                    |
| Status                 | **VIGENTE PARA INTEGRAÇÃO EM HOM** — seção 6.10 é proposta interna, ainda não confirmada pelo CC nem implantada em HOM. Ambiente HOM disponível para testes do CC; go-live depende da validação final dele. |
| Data da revisão documental | 02-09-2026 — adiciona `errorCode: FISCAL_TEXT_INVALID_CHARS` (seção 6.3: validação preventiva de charset/tamanho de texto fiscal, campo `data.reason`) e nota operacional sobre recovery administrativo de ciclo (seção 6.4). Nenhum endpoint novo; payload de criação inalterado. Anterior: 10-08-2026 — `errorCode: LOCAL_PROCESSING_FAILURE`. |
| Ambiente de referência | HOM — acesso externo fornecido somente durante janela controlada de teste. Nenhuma URL fixa deve ser assumida pelo integrador. |
| Plataforma             | Spring Boot 3.3.2 · Java 17 · NF-e 4.00 |
| Validado contra        | Código-fonte + testes automatizados     |

> **Ressalva:** uma emissão real controlada foi validada em HOM em 21-07-2026 (`cStat=100`) com o release atual, confirmando o fluxo completo — autenticação, resolução de certificado por CNPJ, validação de CFOP × destino (seção 6.4), geração/assinatura/transmissão do XML e persistência da autorização. As demais funcionalidades citadas anteriormente nesta ressalva (proteção contra emissão concorrente, contexto fiscal multi-CNPJ nos eventos pós-autorização, baseline seguro de numeração e `indFinal` configurável por empresa) permanecem cobertas por teste automatizado, mas sem smoke test dedicado em HOM. A execução de smoke test detalhado permanece em `CHECKLIST_OMS_ONBOARDING.md`.

---

## Legenda de Marcadores

> `[CONTRATO]` Regra que o integrador deve seguir obrigatoriamente. O não cumprimento resulta em erro ou comportamento indefinido.

> `[EXEMPLO]` Payload ou valor ilustrativo. Os dados reais variam por ambiente e cadastro.

> `[OPERACIONAL]` Comportamento observado em HOM ou nuance de implementação relevante para o integrador.

---

## 1. Objetivo e Escopo

Este documento descreve o contrato de integração entre o ERP logístico externo e o motor fiscal Borurio BR. Cobre exclusivamente os endpoints necessários para o fluxo completo de emissão de NF-e 4.00.

**Fora do escopo deste documento:**
- Gestão de usuários e empresas (endpoints ADMIN internos)
- Inutilização de faixa de numeração
- Auditoria de logs fiscais (disponível em `/api/fiscal/nfe/logs`)
- Endpoints legados sob `/api/fiscal/nfe/` (deprecated)

---

## 2. Ambientes

| Ambiente   | URL base                | Finalidade                                |
|------------|-------------------------|-------------------------------------------|
| DEV        | `http://localhost:8080` | Desenvolvimento local                     |
| HOM        | Fornecida somente durante janela controlada de teste | Homologação SEFAZ-SP       |
| PRD        | Definido por operações  | Produção — não coberto por este documento |

> `[CONTRATO]` Todos os testes de integração devem ser executados em HOM antes de qualquer operação em PRD.

> `[CONTRATO]` O certificado A1 utilizado em HOM é exclusivo de homologação e teste — não deve ser reutilizado em PRD. O certificado de produção é distinto, ainda não disponibilizado, e será fornecido posteriormente pelo responsável da empresa emitente por canal seguro, na preparação formal do ambiente de produção.

> `[CONTRATO]` **(20-07-2026)** Em homologação (`tpAmb=2`), o campo `dest/xNome` da NF-e é substituído automaticamente pelo texto fixo exigido pela SEFAZ (`"NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL"`), independentemente da razão social enviada em `destRazaoSocial`. Em produção (`tpAmb=1`) o nome real do destinatário é preservado sem alteração. O OMS não precisa fazer nada diferente entre os ambientes — a substituição ocorre apenas na montagem do XML, nunca no banco de dados ou na resposta da API.

> `[OPERACIONAL]` O acesso externo ao ambiente HOM é disponibilizado somente durante janelas controladas de teste (HTTPS, TLS 1.3) — não é necessário configurar VPN ou túnel SSH do lado do time de integração. **Nenhuma URL fixa deve ser assumida.** A URL ativa é fornecida por canal seguro antes de cada janela de teste. Verificar o acesso com `GET <url-fornecida>/api/test/ping` antes de iniciar a sequência de smoke test.

---

## 3. Autenticação

### 3.1 Obtenção do Token

> `[CONTRATO]` Toda sessão de integração começa com a obtenção de um token JWT. Sem token válido, todos os endpoints protegidos retornam HTTP 401.

```
POST /auth/login
Content-Type: application/json
```

```json
{
  "username": "usuario@empresa.com",
  "password": "senha"
}
```

> `[CONTRATO]` O campo `username` espera o **e-mail** do usuário cadastrado no sistema, não um nome de usuário livre.

**Resposta de sucesso — HTTP 200:**
```json
{
  "code": 200,
  "message": "Autenticação bem-sucedida",
  "token": "eyJhbGci..."
}
```

> `[CONTRATO]` Esta rota é a única que não usa o envelope `Result<>` padrão da API. A estrutura de resposta é própria: `{ code, message, token }`.

**Resposta de falha — HTTP 401:**
```json
{ "code": 401, "message": "Credenciais inválidas", "token": null }
```

### 3.2 Uso do Token

> `[CONTRATO]` O token deve ser enviado no header `Authorization` em todas as requisições subsequentes:

```
Authorization: Bearer eyJhbGci...
```

> `[CONTRATO]` O token expira após **1 hora** (padrão de `security.jwt.expiration-ms`). Após expiração, todos os endpoints retornam HTTP 401 e um novo login é necessário.

> `[OPERACIONAL]` Implementar renovação de token antes da expiração para fluxos de longa duração (ex: importação em lote).

---

### 3.3 Sessão OMS — Autorização Fiscal por Certificado A1

> `[CONTRATO]` O ERP logístico externo (OMS) **não utiliza** `POST /auth/login` para se autenticar. O OMS abre sessão via **autorização fiscal**, enviando o certificado A1 da empresa emitente e recebendo um token técnico de longa duração.

> `[CONTRATO]` **Não é necessário pré-cadastrar a empresa no Borurio.** O sistema cria automaticamente o registro da empresa a partir dos dados do Subject do certificado X.509 (razão social extraída do campo `CN=`, UF do campo `ST=`). Apenas empresas com cadastro inativo bloqueiam a autorização — nesse caso o OMS recebe `COMPANY_INACTIVE`.

#### Modelo multi-CNPJ

> `[CONTRATO]` Um cliente OMS (`codigoEmpresaOms`) pode autorizar **múltiplos CNPJs** com um único token. O token identifica o cliente OMS, não um CNPJ específico. Cada CNPJ requer uma chamada separada a este endpoint — o token retornado é sempre o mesmo para o mesmo `codigoEmpresaOms`.

> `[CONTRATO]` Na emissão de NF-e para um cliente OMS com múltiplos CNPJs, o campo `cnpjEmitente` do pedido seleciona qual CNPJ (e certificado) será utilizado para assinar e transmitir a NF-e à SEFAZ. O CNPJ informado precisa estar autorizado para o token (certificado ativo) **e** com cadastro fiscal (Inscrição Estadual) aceito pela SEFAZ — pendências cadastrais de uma empresa específica são reportadas pela SEFAZ no retorno da emissão (`cStat`/`xMotivo`), não impedem a autorização do token em si. Ver seção 6.3.

#### Endpoint

```
POST /api/integration/fiscal-authorizations
X-Api-Key: {chave-tecnica-do-integrador}
Content-Type: application/json
```

> `[CONTRATO]` O header `X-Api-Key` é obrigatório. Requisições sem esse header, ou com chave inválida ou revogada, retornam HTTP 401 com `errorCode: INVALID_API_KEY`. A chave é fornecida pelo ADMIN do Borurio.

**Campos do payload:**

| Campo | Tipo | Regra |
|---|---|---|
| `codigoEmpresaOms` | String | Obrigatório · Máx 100 chars · Identificador único do cliente no sistema OMS |
| `cnpj` | String | Obrigatório · Exatamente 14 dígitos numéricos |
| `certBase64` | String | Obrigatório · Arquivo PKCS12 (.pfx / .p12) codificado em Base64 |
| `certSenha` | String | Obrigatório · Senha do arquivo PKCS12 |

> `[CONTRATO]` O `cnpj` enviado deve corresponder ao CNPJ embutido no Subject do certificado X.509. Divergência retorna `CNPJ_CERTIFICATE_MISMATCH`.

> `[EXEMPLO]` Primeira autorização — CNPJ1 de um novo cliente OMS:
```json
{
  "codigoEmpresaOms": "{{codigoEmpresaOms}}",
  "cnpj":             "{{cnpjEmitente}}",
  "certBase64":       "<base64 do arquivo .pfx>",
  "certSenha":        "<senha do certificado>"
}
```

> `[EXEMPLO]` Segunda autorização — CNPJ2 do **mesmo** cliente OMS (retorna o mesmo token):
```json
{
  "codigoEmpresaOms": "{{codigoEmpresaOms}}",
  "cnpj":             "{{cnpjEmitente2}}",
  "certBase64":       "<base64 do .pfx do segundo CNPJ>",
  "certSenha":        "<senha do segundo certificado>"
}
```

**Resposta de sucesso — HTTP 200:**
```json
{
  "token":         "{{tokenOms}}",
  "empresaId":     1,
  "cnpj":          "{{cnpjEmitente}}",
  "razaoSocial":   "{{razaoSocialEmitente}}",
  "tokenExpiraEm": "2026-08-01T13:15:00"
}
```

> `[OPERACIONAL]` Os valores reais utilizados em cada rodada de teste em HOM (CNPJ, razão social, certificado) são fornecidos por canal seguro, fora deste documento. Este contrato é reutilizável para qualquer empresa emitente.

> `[CONTRATO]` Os certificados A1 utilizados nas rodadas atuais são exclusivos do ambiente de homologação e teste. O ambiente de produção utilizará certificado A1 distinto, ainda não disponibilizado, que será fornecido posteriormente pelo responsável da empresa emitente por canal seguro. Certificados de HOM não devem ser reutilizados em PRD.

> `[CONTRATO]` Esta rota **não usa** o envelope `Result<>` padrão da API. A estrutura de resposta é própria: `{ token, empresaId, cnpj, razaoSocial, tokenExpiraEm }`. Leia `response.token` diretamente — **não** existe `response.data.token`.

> `[CONTRATO]` O campo `tokenExpiraEm` reflete o vencimento do certificado A1 enviado. O OMS deve armazenar o token e usá-lo no header `Authorization: Bearer {token}` em todas as operações subsequentes (pedidos, emissão, consulta, cancelamento, CC-e).

> `[CONTRATO]` Para clientes OMS com **múltiplos CNPJs**, o token é o mesmo independente de qual CNPJ foi autorizado por último. O campo `empresaId` na resposta refere-se à empresa do primeiro CNPJ autorizado para aquele `codigoEmpresaOms` (empresa âncora). O `empresaId` não é enviado nos pedidos — não é necessário.

#### Comportamento por cenário de chamada

| Cenário | Condição | Comportamento |
|---|---|---|
| **A — Novo cliente OMS** | `codigoEmpresaOms` nunca visto | Cria slot + insere cert + emite **novo token** |
| **B — Mesmo CNPJ, mesmo cert** | Cert idêntico já ativo | Nenhuma alteração no banco — **retorna token existente** |
| **C — Mesmo CNPJ, cert novo** | Mesmo CNPJ, thumbprint diferente | Desativa cert anterior + insere cert novo + atualiza `tokenExpiraEm` — **retorna token existente (mesmo JTI)** |
| **D — CNPJ novo, cliente existente** | `codigoEmpresaOms` já autorizado, CNPJ inédito | Insere cert para o novo CNPJ — **retorna token existente sem alteração** |

> `[CONTRATO]` Nos cenários B, C e D o **token não muda**. O OMS não precisa atualizar o token armazenado ao adicionar ou renovar CNPJs de um cliente já autorizado. Armazene o token recebido na primeira autorização do cliente.

#### Atualização de certificado expirado ou renovado

> `[CONTRATO]` Para renovar o certificado de um CNPJ específico, enviar este endpoint novamente com o mesmo `codigoEmpresaOms` e `cnpj`, e o novo arquivo `.pfx`. O sistema desativa o certificado anterior para aquele CNPJ e ativa o novo — o token permanece o mesmo (cenário C acima).

> `[CONTRATO]` Não é necessário acionar o ADMIN do Borurio para trocar o certificado — o próprio OMS realiza a renovação diretamente via este endpoint.

#### Revogação e rotação

> `[OPERACIONAL]` Em caso de comprometimento de API Key ou token, acionar o ADMIN do Borurio para revogação ou rotação administrativa. **Desde 22-07-2026, a autorização ativa é validada no banco a cada requisição** — após revogação, o token é recusado imediatamente com `AUTHORIZATION_REVOKED`, sem depender da expiração natural do JWT. Rotação emite um novo token (novo identificador interno), invalidando o anterior, sem interromper o acesso do OMS ao serviço.

> `[CONTRATO]` A revogação e a rotação são operações administrativas internas do Borurio (`/api/admin/oms-authorizations/**`, fora do contrato público consumido pelo OMS) — o OMS nunca chama esses endpoints diretamente. O OMS apenas recebe o novo token, por canal controlado, quando uma rotação ocorrer.

---

## 4. Contexto Multiempresa (Multitenancy)

> `[CONTRATO]` O `empresaId` **não é enviado no body das requisições.** Ele é extraído automaticamente do token JWT pelo sistema.

**Como funciona internamente — fluxo de usuário:**

1. No login, o `empresaId` do usuário é embutido no token como claim `"eid"`
2. A cada request, o filtro JWT extrai `"eid"` e associa ao contexto da requisição
3. Todos os endpoints de produtos, pedidos e clientes isolam os dados automaticamente por empresa
4. O contexto é limpo após cada request — sem risco de vazamento entre chamadas

> `[CONTRATO]` Um token gerado para a empresa A **só acessa** produtos, pedidos e clientes da empresa A. Tentativas de acessar recursos de outra empresa retornam HTTP 404 (recurso não encontrado para a empresa autenticada).

> `[OPERACIONAL]` Cada empresa parceira deve ter seu próprio usuário com credenciais distintas. Não compartilhar tokens entre empresas.

**Contexto OMS multi-CNPJ:**

> `[CONTRATO]` O token OMS identifica o **cliente OMS** (`codigoEmpresaOms`), não um CNPJ específico. Produtos cadastrados via token OMS pertencem ao cliente OMS. Pedidos e emissões de NF-e são selecionados por CNPJ — o campo `cnpjEmitente` no pedido determina qual certificado é utilizado na assinatura e transmissão à SEFAZ (ver seção 6.3).

> `[OPERACIONAL]` Não é necessário informar `empresaId` em nenhuma requisição do fluxo OMS. O sistema resolve a empresa emitente a partir do `cnpjEmitente` do pedido.

---

## 5. Fluxo Obrigatório de Integração

### 5.1 Sequência de Chamadas

> `[CONTRATO]` A sequência abaixo deve ser respeitada. Etapas não podem ser puladas.

```
Etapa 1 ── POST /auth/login
           └─► Obter token JWT

Etapa 2 ── POST /api/app/produtos          (se produto ainda não existir)
           └─► Registrar produto com dados fiscais completos
               Guardar: data.id = produtoId

Etapa 3 ── POST /api/app/pedidos
           └─► Criar pedido em estado RASCUNHO com itens
               Guardar: data.id = pedidoId

Etapa 4 ── POST /api/app/pedidos/{pedidoId}/emitir
           └─► Transmitir NF-e para a SEFAZ
               Guardar: data.chaveNfe

Etapa 5 ── GET  /api/app/pedidos/{pedidoId}/situacao
           └─► Confirmar status local + consulta live na SEFAZ
               (opcional após status AUTORIZADO, obrigatório após AGUARDANDO)
```

**Operações pós-emissão (quando aplicável):**

```
Etapa 6 ── POST /api/app/pedidos/{pedidoId}/cancelar
           └─► Cancelar NF-e (somente status AUTORIZADO, dentro do prazo legal)

Etapa 7 ── POST /api/app/pedidos/{pedidoId}/cce
           └─► Carta de Correção Eletrônica (somente status AUTORIZADO)
```

### 5.2 Diagrama de Transição de Estados

```
                          ┌─────────────────────────────┐
                          │                             │
              POST /emitir│          SEFAZ              │
RASCUNHO ────────────────►│   cStat=100  → AUTORIZADO   │
                          │   cStat=104  → AGUARDANDO   │
                          │   cStat≥200  → REJEITADO    │
                          │   exceção    → ERRO          │
                          └─────────────────────────────┘

AUTORIZADO ──► POST /cancelar ──► CANCELADO   (imutável)
AUTORIZADO ──► POST /cce      ──► AUTORIZADO  (status não muda)
AGUARDANDO ──► GET  /situacao ──► (verificar cStat atual)
REJEITADO  ──► POST /emitir   ──► nova tentativa no MESMO pedido (não cria pedido novo)
ERRO       ──► POST /emitir   ──► nova tentativa no MESMO pedido (não cria pedido novo)
```

> `[CONTRATO]` Desde a v1.9, `REJEITADO` e `ERRO` **não são mais estados terminais**. `POST /emitir` pode ser chamado novamente no mesmo `pedidoId` — não é necessário criar um pedido novo. Cada nova tentativa gera um número de NF-e (`nNF`) e uma `chaveNfe` novos, sem risco de duplicidade na SEFAZ. Apenas `AUTORIZADO` e `CANCELADO` permanecem terminais.

---

## 6. Endpoints por Etapa

### 6.1 Verificação de Disponibilidade

> `[CONTRATO]` Não requer autenticação.

```
GET /api/test/ping
```

**Resposta — HTTP 200:**
```json
{
  "status":      "UP",
  "code":        200,
  "message":     "API Borurio ERP Fiscal BR está operacional.",
  "environment": "dev",
  "timestamp":   "2026-05-12T10:00:00.000-03:00"
}
```

> `[CONTRATO]` Esta rota não usa o envelope `Result<>` padrão. A estrutura de resposta é própria.

> `[OPERACIONAL]` O campo `"environment"` reflete o perfil Spring ativo (`spring.profiles.active`). Use-o apenas como indicador de diagnóstico, não como discriminador de roteamento.

---

### 6.2 Cadastro de Produto (Etapa 2)

> `[CONTRATO]` O produto deve ser cadastrado antes de criar qualquer pedido que o referencie. Os campos fiscais do produto são **congelados no item do pedido** no momento da criação — alterações posteriores no produto não retroagem.

```
POST /api/app/produtos
Authorization: Bearer {token}
Content-Type: application/json
```

**Campos e regras:**

| Campo | Tipo | Regra |
|---|---|---|
| `codigo` | String | Obrigatório · Máx 60 chars · Único por empresa |
| `descricao` | String | Obrigatório · Máx 120 chars |
| `ncm` | String | Obrigatório · Exatamente 8 dígitos numéricos |
| `cfop` | String | Opcional · 4 dígitos numéricos · default `"5102"` (intra-estado) se omitido |
| `unidade` | String | Obrigatório · Ex: `UN`, `KG`, `PC`, `CX` |
| `preco` | Decimal | Obrigatório · Valor > 0.01 |
| `origem` | Inteiro | Obrigatório · `0`=Nacional · `1` a `8`=Importada |
| `csosn` | String | Opcional · Se omitido, snapshot do item usará `"400"` |

> `[CONTRATO]` CFOP de referência: operação interna (mesmo estado): `5102` · operação interestadual: `6102`. O CFOP continua sendo responsabilidade do OMS — o Borurio valida a coerência entre o CFOP informado e o destino calculado (`idDest`), mas não o corrige nem o infere.

> `[CONTRATO]` **Modalidade de frete (`modFrete`) não é enviada pelo OMS nesta versão do contrato — não existe campo para isso no payload.** O Borurio define internamente `modFrete=2` ("Contratação do Frete por conta de Terceiros") para toda emissão do fluxo OMS/marketplace atual, refletindo que a própria plataforma contrata o transporte (confirmado pelo integrador chinês). Essa é uma decisão fiscal interna do Borurio, não um parâmetro configurável pelo OMS nesta versão.

> `[CONTRATO]` CSOSN de referência para Simples Nacional: `102`=sem ST sem crédito · `103`=isento por faixa de receita · `300`=imune · `400`=não contribuinte · `500`=ICMS cobrado anteriormente (ST) · `900`=outros. Os códigos `201`, `202` e `203` (com ST) não estão suportados nesta versão do motor.

> `[EXEMPLO]` Payload mínimo válido:
```json
{
  "codigo":    "PROD-001",
  "descricao": "Produto de Teste Integração",
  "ncm":       "84715011",
  "cfop":      "5102",
  "unidade":   "UN",
  "preco":     100.00,
  "origem":    0,
  "csosn":     "102"
}
```

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "id":           7,
    "empresaId":    1,
    "codigo":       "PROD-001",
    "descricao":    "Produto de Teste Integração",
    "ncm":          "84715011",
    "cfop":         "5102",
    "unidade":      "UN",
    "preco":        100.00,
    "origem":       0,
    "csosn":        "102",
    "estado":       null,
    "estoque":      null,
    "criadoEm":     "2026-05-12T10:00:00",
    "atualizadoEm": null
  }
}
```

> `[CONTRATO]` Guardar `data.id` como `produtoId` para uso nos itens do pedido.

> `[OPERACIONAL]` Produto com `estado=0` é tratado como inativo. Tentar referenciar um produto inativo em um pedido retorna HTTP 400: `"Produto inativo não pode ser adicionado ao pedido"`.

---

### 6.2b Busca de Produto por Código Interno (SKU)

> `[OPERACIONAL]` Quando a OMS conhece o código interno (SKU) do produto mas não seu `id` no Borurio, utilize este endpoint para resolver o identificador antes de consultar o estoque.

```
GET /api/app/produtos/codigo/{codigo}
Authorization: Bearer {token}
```

| Parâmetro | Regra |
|---|---|
| `codigo` | Código interno do produto (path variable) — mesmo valor cadastrado em `POST /api/app/produtos` |

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "id":        7,
    "empresaId": 1,
    "codigo":    "PROD-001",
    "descricao": "Produto de Teste Integração",
    "ncm":       "84715011",
    "cfop":      "5102",
    "unidade":   "UN",
    "preco":     100.00,
    "origem":    0,
    "csosn":     "102",
    "estado":    1
  }
}
```

> `[CONTRATO]` Guardar `data.id` para uso no fluxo de consulta de estoque.

> `[OPERACIONAL]` Fluxo em dois passos validado pelo time de integração:
> ```
> Passo 1 — GET /api/app/produtos/codigo/{sku}  → recupera produto + id
> Passo 2 — GET /api/app/produtos/{id}/estoque  → consulta saldo com o id retornado
> ```

**Resposta — HTTP 404:**
```json
{ "code": 404, "message": "Recurso não encontrado", "data": null }
```

---

### 6.2c Gestão de Estoque

> `[CONTRATO]` O motor fiscal controla o estoque de produtos com reservas atômicas vinculadas ao ciclo de emissão de NF-e. A OMS deve verificar o estoque disponível antes de submeter um pedido para emissão.

#### Consulta de Saldo de Estoque

```
GET /api/app/produtos/{id}/estoque
Authorization: Bearer {token}
```

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "data": {
    "produtoId":         1,
    "estoqueTotal":      "100.0000",
    "estoqueReservado":  "10.0000",
    "estoqueDisponivel": "90.0000"
  }
}
```

> `[CONTRATO]` Usar `estoqueDisponivel` para decidir se prossegue com o pedido. Se a quantidade dos itens exceder `estoqueDisponivel`, a chamada `/emitir` retorna HTTP 422 e o pedido permanece em `RASCUNHO`.

#### Entrada Manual de Estoque

```
POST /api/app/produtos/{id}/estoque/entrada
Authorization: Bearer {token}    ← requer role ADMIN
Content-Type: application/json
```

```json
{ "quantidade": 50.00, "observacao": "Entrada inicial OMS" }
```

> `[CONTRATO]` Este endpoint requer role ADMIN. Token de OPERADOR recebe HTTP 403.

#### Ciclo de Estoque Durante a Emissão de NF-e

| Evento                        | Efeito no estoque                                                      |
|-------------------------------|------------------------------------------------------------------------|
| `POST /emitir` chamado        | Reserva atômica — `estoqueDisponivel -= qtd` por item                  |
| HTTP 422 retornado            | Estoque insuficiente — sem reserva; pedido permanece em `RASCUNHO`     |
| Status → `AUTORIZADO`         | Baixa definitiva — `estoqueTotal -= qtd`, reserva liberada             |
| Status → `REJEITADO` / `ERRO` | Reserva desfeita — `estoqueDisponivel += qtd` por item                 |
| Status → `AGUARDANDO`         | Reserva mantida — `estoqueDisponivel` permanece bloqueado              |
| Status → `CANCELADO`          | Estorno — `estoqueTotal += qtd`                                        |

---

### 6.2d Upsert em Lote de Produtos — Batch

> `[OPERACIONAL]` Use este endpoint para sincronização de catálogo OMS quando precisar importar múltiplos produtos de uma vez. Para cadastro individual, use `POST /api/app/produtos` (seção 6.2).

```
POST /api/app/produtos/batch
Authorization: Bearer {token}
Content-Type: application/json
```

**Comportamento por item:**
- Produto novo (código + empresa não encontrado): status **CRIADO**
- Produto existente (mesmo código na mesma empresa): status **ATUALIZADO** — apenas campos de catálogo são atualizados; `estoque`, `estoque_reservado` e `estado` são preservados
- Item com dados inválidos: status **REJEITADO** — o processamento continua para os próximos itens
- Código duplicado no mesmo payload: **REJEITADO** com `DUPLICATE_CODIGO_IN_BATCH` — a segunda ocorrência é rejeitada; a primeira é processada normalmente

> `[CONTRATO]` A resposta é sempre **HTTP 207 Multi-Status** — mesmo que todos os itens tenham sucesso ou todos falhem. Nunca espere HTTP 200 nem HTTP 422 por resultado individual de item.

> `[CONTRATO]` Máximo de **200 produtos por requisição**. Exceder esse limite retorna HTTP 422 com `errorCode: "BATCH_LIMIT_EXCEEDED"` antes de qualquer item ser processado.

> `[CONTRATO]` Uma lista `produtos` vazia retorna HTTP 400.

**Campos por item (`produtos[]`):**

| Campo       | Tipo    | Regra                                                          |
|-------------|---------|----------------------------------------------------------------|
| `codigo`    | String  | Obrigatório · Chave de upsert por empresa                     |
| `descricao` | String  | Obrigatório                                                   |
| `ncm`       | String  | Obrigatório · Exatamente 8 dígitos numéricos                  |
| `cfop`      | String  | Opcional · 4 dígitos numéricos · default `"5102"` se omitido  |
| `unidade`   | String  | Obrigatório                                                   |
| `preco`     | Decimal | Obrigatório · Valor > 0                                       |
| `origem`    | Inteiro | Opcional · default `0` (nacional) se omitido                  |
| `csosn`     | String  | Opcional · default `"400"` se omitido                         |

**Campos NÃO atualizados no upsert (produto existente):** `codigo`, `estoque`, `estoque_reservado`, `estado`

> `[EXEMPLO]` Requisição com 3 itens — 1 novo, 1 existente, 1 inválido:
```json
{
  "produtos": [
    { "codigo": "SKU-001", "descricao": "Produto A", "ncm": "84715011", "unidade": "UN", "preco": 100.00 },
    { "codigo": "SKU-002", "descricao": "Produto B", "ncm": "84715011", "unidade": "UN", "preco": 50.00 },
    { "codigo": "SKU-BAD", "descricao": "Invalido",  "ncm": "123",      "unidade": "UN", "preco": 10.00 }
  ]
}
```

**Resposta — HTTP 207 Multi-Status:**
```json
{
  "total":       3,
  "criados":     1,
  "atualizados": 1,
  "rejeitados":  1,
  "resultados": [
    { "codigo": "SKU-001", "status": "CRIADO",     "produtoId": 101 },
    { "codigo": "SKU-002", "status": "ATUALIZADO", "produtoId": 87  },
    { "codigo": "SKU-BAD", "status": "REJEITADO",  "produtoId": null, "errorCode": "VALIDATION_ERROR", "message": "NCM deve ter exatamente 8 dígitos numéricos." }
  ]
}
```

> `[CONTRATO]` Cada entrada em `resultados` corresponde 1:1 ao `produtos[]` de entrada por posição e `codigo`. Um item `REJEITADO` nunca bloqueia o processamento dos itens seguintes.

> `[CONTRATO]` Use `resultados[].status` (`CRIADO` / `ATUALIZADO` / `REJEITADO`) para o resultado por item. Use `resultados[].errorCode` (quando presente) para tratamento programático de itens rejeitados.

> `[CONTRATO]` `resultados[].produtoId` contém o ID do banco para itens `CRIADO` e `ATUALIZADO`, e é `null` para `REJEITADO`.

---

### 6.3 Criação de Pedido — RASCUNHO (Etapa 3)

> `[CONTRATO]` O pedido recém-criado inicia obrigatoriamente em estado `RASCUNHO`. Campos como `status`, `chaveNfe` e `numero` são preenchidos automaticamente — **não enviar no body**.

```
POST /api/app/pedidos
Authorization: Bearer {token}
Content-Type: application/json
```

**Campos do cabeçalho:**

| Campo                 | Tipo                | Regra                                                                 |
|-----------------------|---------------------|-----------------------------------------------------------------------|
| `destCnpjCpf`         | String              | Obrigatório · CNPJ (14 dígitos) ou CPF (11 dígitos) · somente números |
| `destRazaoSocial`     | String              | Obrigatório                                                           |
| `destUf`              | String              | Recomendado · Sigla do estado: `SP`, `RJ`, `MG`...                    |
| `destLogradouro`      | String              | Recomendado · Melhora aprovação SEFAZ                                 |
| `destNumero`          | String              | Recomendado                                                           |
| `destBairro`          | String              | Recomendado                                                           |
| `destCodigoMunicipio` | String              | Recomendado · Código IBGE 7 dígitos                                   |
| `destMunicipio`       | String              | Recomendado                                                           |
| `destCep`             | String              | Recomendado                                                           |
| `naturezaOperacao`    | String              | Opcional · Default server-side: `"VENDA DE MERCADORIA"`               |
| `cnpjEmitente`        | String              | **Obrigatório para OMS multi-CNPJ** · 14 dígitos numéricos · CNPJ que deve assinar e emitir a NF-e · Se omitido no fluxo OMS, usa o CNPJ do primeiro certificado autorizado para o cliente |

> `[OPERACIONAL]` Quando o cliente OMS tem mais de um CNPJ vinculado ao mesmo token, **não depender do CNPJ padrão** (primeiro certificado autorizado) — informar `cnpjEmitente` explicitamente em cada pedido. Um certificado histórico/desativado vinculado ao mesmo token pode ser resolvido por padrão e gerar rejeição da SEFAZ por pendência cadastral (`cStat=209`, IE do emitente inválida) mesmo com outro CNPJ do mesmo cliente OMS ativo e apto para emissão.
| `externalOrderId`     | String              | Recomendado · Máx 100 chars · Único por empresa · Habilita retry idempotente |
| `emitLogradouro`      | String              | Opcional · Endereço do emitente (ver nota abaixo) |
| `emitNumero`          | String              | Opcional |
| `emitBairro`          | String              | Opcional |
| `emitCodigoMunicipio` | String              | Opcional · Código IBGE 7 dígitos |
| `emitMunicipio`       | String              | Opcional |
| `emitCep`             | String              | Opcional |

> `[CONTRATO]` **Endereço do emitente (v1.9).** Quando uma empresa é autorizada pela primeira vez (`POST /api/integration/fiscal-authorizations`), ela é criada automaticamente apenas com CNPJ, razão social e UF — dados extraídos do certificado A1, que não carrega endereço. Se o cadastro da empresa emitente estiver incompleto, `POST /emitir` retorna `EMITTER_ADDRESS_INCOMPLETE` sem chamar a SEFAZ (ver seção 8.2a). Para resolver, envie os 6 campos `emit*` acima em `POST /api/app/pedidos` — o sistema completa automaticamente **apenas os campos ausentes** do cadastro da empresa (nunca sobrescreve um endereço já cadastrado). Não é necessário reenviar em todo pedido: uma vez completo, o cadastro permanece completo.

> `[CONTRATO]` **Idempotência:** se `externalOrderId` for informado e já existir um pedido com esse identificador para a mesma empresa, o sistema retorna o pedido existente sem criar duplicata. Protege contra emissão dupla de NF-e por timeout ou retry do OMS. Se omitido, cada chamada cria um novo pedido.

> `[CONTRATO]` O escopo do `externalOrderId` é por empresa: o mesmo valor usado pela empresa A não conflita com a empresa B.

**Campos de cada item (`itens[]`):**

> `[CONTRATO]` Todos os campos fiscais abaixo são **obrigatórios** e devem ser enviados pelo OMS em cada item. O sistema **não** copia dados fiscais do produto cadastrado — o que o OMS enviar é exatamente o que vai para o XML da NF-e. Se algum campo fiscal obrigatório estiver ausente, o pedido é rejeitado com HTTP 400.

| Campo            | Tipo    | Regra                                                              |
|------------------|---------|--------------------------------------------------------------------|
| `produtoId`      | Long    | Obrigatório · Produto deve existir e estar ativo                   |
| `quantidade`     | Decimal | Obrigatório · Valor > 0                                            |
| `valorUnitario`  | Decimal | Obrigatório · Valor > 0                                            |
| `codigoProduto`  | String  | Obrigatório · Código do produto na NF-e (`cProd`)                  |
| `descricao`      | String  | Obrigatório · Descrição do produto na NF-e (`xProd`)               |
| `ncm`            | String  | Obrigatório · Exatamente 8 dígitos numéricos                       |
| `cfop`           | String  | Obrigatório · 4 dígitos (ex: `"5102"` dentro do estado, `"6102"` interestadual) |
| `unidade`        | String  | Obrigatório · Ex: `UN`, `KG`, `PC`, `CX`                          |
| `origem`         | Integer | Obrigatório · `0`=Nacional · `1`–`8`=Importada                    |
| `csosn`          | String  | Obrigatório · Simples Nacional: `"102"`, `"103"`, `"300"`, `"400"`, `"500"`, `"900"` (não enviar `201`/`202`/`203`) |

> `[CONTRATO]` `valorTotal` de cada item é calculado automaticamente como `quantidade × valorUnitario`. O total do pedido é a soma dos itens. Não enviar esses campos.

> `[CONTRATO]` **(20-07-2026) CFOP é snapshot do pedido, nunca sobrescrito silenciosamente.** O CFOP enviado pelo OMS em cada item é gravado exatamente como recebido — o Borurio não calcula nem corrige o CFOP a partir do cadastro do produto ou de qualquer outra fonte. Na emissão (`POST /emitir`, seção 6.4), o CFOP de cada item é **validado** (não corrigido) contra o tipo de operação: se a UF do emitente e a UF do destinatário resultam em operação interna, todo CFOP deve iniciar com `5`; se resultam em operação interestadual, todo CFOP deve iniciar com `6`. Divergência bloqueia a emissão antes da reserva fiscal e antes de qualquer chamada à SEFAZ — ver `CFOP_DESTINATION_MISMATCH` na seção 6.4/8.2a.

> `[CONTRATO]` **`indFinal` e `indIntermed` não fazem parte deste payload.** `indFinal` (indicador de consumidor final) é resolvido internamente pelo cadastro da empresa emitente — não é inferido do CPF/CNPJ do destinatário. Não há override por pedido nesta versão do contrato. `indIntermed` (indicador de intermediador/marketplace) é gerado internamente pelo Borurio; no escopo atual de venda direta, o valor utilizado é `"0"` — isso não representa regra universal para toda NF-e. O OMS não envia nenhum dos dois campos. Um cenário de venda via marketplace/plataforma de terceiro permanece evolução futura, condicionada a definição de negócio, e poderá exigir origem da venda por pedido, `indIntermed="1"`, grupo `infIntermed` com CNPJ do intermediador e identificador da operação — nenhum desses elementos está no escopo desta versão.

> `[EXEMPLO]` Payload válido com campos fiscais por item:
```json
{
  "externalOrderId":     "OMS-20260601-0001",
  "destCnpjCpf":         "12345678000195",
  "destRazaoSocial":     "Empresa Destinatária Ltda",
  "destUf":              "SP",
  "destCodigoMunicipio": "3550308",
  "destMunicipio":       "São Paulo",
  "itens": [
    {
      "produtoId":     7,
      "quantidade":    2,
      "valorUnitario": 100.00,
      "codigoProduto": "SKU-OMS-001",
      "descricao":     "Produto Teste Integração",
      "ncm":           "84715011",
      "cfop":          "6102",
      "unidade":       "UN",
      "origem":        0,
      "csosn":         "102"
    }
  ]
}
```

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "id":              42,
    "empresaId":       1,
    "numero":          "PED-00000042",
    "externalOrderId": "OMS-20260601-0001",
    "cnpjEmitente":    "{{cnpjEmitente}}",
    "destCnpjCpf":     "12345678000195",
    "destRazaoSocial": "Empresa Destinatária Ltda",
    "destUf":          "SP",
    "naturezaOperacao":"VENDA DE MERCADORIA",
    "serieNfe":        null,
    "status":          "RASCUNHO",
    "chaveNfe":        null,
    "valorTotal":      200.00,
    "dataPedido":      "2026-05-12T10:05:00",
    "dataAtualizacao": null,
    "itens": [
      {
        "id":            88,
        "pedidoId":      42,
        "produtoId":     7,
        "quantidade":    2.00,
        "valorUnitario": 100.00,
        "valorTotal":    200.00,
        "codigoProduto": "PROD-001",
        "descricao":     "Produto de Teste Integração",
        "ncm":           "84715011",
        "cfop":          "5102",
        "unidade":       "UN",
        "origem":        0,
        "csosn":         "102"
      }
    ]
  }
}
```

> `[CONTRATO]` Guardar `data.id` como `pedidoId` para as etapas seguintes.

**Resposta — HTTP 422 (texto fiscal incompatível com o schema NF-e — o pedido NÃO é criado):**
```json
{
  "code": 422,
  "message": "A descrição do item 1 contém caractere não aceito pela NF-e — use apenas letras, números e pontuação padrão (sem caracteres de outros alfabetos ou emoji).",
  "data": { "field": "itens[0].descricao", "itemIndex": 0, "reason": "CARACTERE_NAO_PERMITIDO" },
  "errorCode": "FISCAL_TEXT_INVALID_CHARS",
  "retryable": false
}
```

> `[CONTRATO]` **`FISCAL_TEXT_INVALID_CHARS` (02-09-2026).** Validação preventiva de charset e tamanho dos campos de texto que vão para elementos NF-e `TString`: `naturezaOperacao`, `destRazaoSocial`, `destLogradouro`, `destNumero`, `destBairro`, `destMunicipio`, `observacao` e a `descricao` de cada item. Regra: só caracteres `U+0020`–`U+00FF` (latino-1 — acentos do português são aceitos; ideogramas, emoji e pontuação *full-width* não), primeiro e último caractere ≠ espaço, e o `maxLength` oficial do campo. O texto **nunca é sanitizado nem corrigido** pelo Borurio — corrija na origem e reenvie. `data.field` aponta o campo; `data.itemIndex` (quando é item) o índice 0-based; `data.reason` é um código estável para tratamento programático: `CARACTERE_NAO_PERMITIDO`, `ESPACO_NA_BORDA` ou `ACIMA_DO_MAX_LENGTH`. A mesma validação roda de novo em `/emitir` (defesa em profundidade) — um pedido legado com texto incompatível é bloqueado ali, antes de reservar número fiscal.

> `[CONTRATO]` **Idempotência × validação.** Um reenvio de `POST /pedidos` com o mesmo `externalOrderId` (e mesma empresa) de um pedido que já existe devolve o pedido existente com **HTTP 200** — nunca `FISCAL_TEXT_INVALID_CHARS`. A validação de texto só roda quando a requisição vai realmente criar um pedido novo.

> `[OPERACIONAL]` Os campos fiscais do item (`codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`) são copiados do produto no momento da criação. Essa cópia é imutável — alterações posteriores no cadastro do produto não afetam pedidos existentes.

> `[CONTRATO]` **(20-07-2026)** `serieNfe` vem sempre `null` na criação — deixou de ser resolvida neste momento. A série é resolvida junto com o número, atomicamente, só no início de cada tentativa de emissão (seção 6.4), a partir da configuração vigente da empresa emitente naquele instante — não da configuração vigente quando o pedido foi criado. Se `serieNfe` for enviado no payload do POST, o valor é ignorado silenciosamente (sem erro), assim como `chaveNfe`.

---

### 6.4 Emissão de NF-e (Etapa 4)

> `[CONTRATO]` Sem body. O motor constrói o XML NF-e 4.00 internamente a partir do snapshot fiscal dos itens.

> `[CONTRATO]` Pré-condição (v1.9): pedido deve estar em `RASCUNHO`, `REJEITADO` ou `ERRO`. Qualquer outro estado (`AUTORIZADO`, `AGUARDANDO`, `CANCELADO`) retorna HTTP 422 com `errorCode: INVALID_ORDER_STATUS`. Chamar `/emitir` num pedido `REJEITADO` ou `ERRO` **reutiliza o mesmo pedido** — não crie um pedido novo para tentar de novo.

```
POST /api/app/pedidos/{pedidoId}/emitir
Authorization: Bearer {token}
```

**Resposta — HTTP 200 (SEFAZ autorizou ou ainda está processando):**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "chaveNfe":     "35260512000000000000550010000000421000000424",
    "soapRetorno":  "<nfeProc ...>...</nfeProc>",

    "serie":        "1",
    "numeroNFe":    5,
    "estadoFiscal": "AUTORIZADO",
    "cStat":        100,
    "xMotivo":      "Autorizado o uso da NF-e",
    "nProt":        "135260512345678"
  }
}
```

> `[CONTRATO]` Desde a v1.9, HTTP 200 só ocorre quando a NF-e foi **autorizada** (`AUTORIZADO`) ou está **aguardando** confirmação (`AGUARDANDO`). Uma rejeição da SEFAZ **não** retorna HTTP 200 — ver `SEFAZ_REJECTED` abaixo. O status real ainda pode ser confirmado via `GET /api/app/pedidos/{id}` ou `/situacao`.

> `[CONTRATO]` O campo `data.chaveNfe` será uma string vazia `""` (não `null`) quando a SEFAZ não retornar chave de acesso.

> `[CONTRATO]` **(11-08-2026, Gate de contrato OMS)** `serie`, `numeroNFe`, `estadoFiscal`, `cStat`, `xMotivo` e `nProt` são **aditivos** — `chaveNfe`/`soapRetorno` continuam com o mesmo nome e comportamento de sempre. Fonte única: `nfe_emissao` (o ciclo operacional/fiscal do número), nunca `nfe_documento`. Tipos: `serie` é `string`, `numeroNFe` é `integer` (nunca muda depois que o ciclo abre), `cStat` é `integer` **nullable** aqui em `/emitir` (distinto do `/situacao`, ver 6.5), `xMotivo`/`nProt` são `string` nullable. Em `AUTORIZADO` (`cStat` 100/150) todos os seis campos vêm preenchidos. Em `PENDENTE_CONFIRMACAO` (a NF-e ainda não tem resultado definitivo — ex.: timeout de rede sem resposta da SEFAZ), `estadoFiscal` vem preenchido mas `cStat`/`xMotivo`/`nProt` podem vir `null` — use `estadoFiscal` para distinguir os casos, nunca infira estado a partir de campos nulos.

**Resposta — HTTP 422 (pré-condição de estado violada):**
```json
{ "code": 422, "message": "Pedido não pode ser emitido no status atual: AUTORIZADO. Permitido apenas para RASCUNHO, REJEITADO ou ERRO.", "data": null, "errorCode": "INVALID_ORDER_STATUS", "retryable": false }
```

**Resposta — HTTP 422 (SEFAZ rejeitou a NF-e):**
```json
{
  "code": 422,
  "message": "NF-e rejeitada pela SEFAZ: Rejeição: Falha no Schema XML do lote de NFe",
  "data": {
    "cStat": 225, "xMotivo": "Rejeição: Falha no Schema XML do lote de NFe",
    "serie": "1", "numeroNFe": 6, "estadoFiscal": "AGUARDANDO_CORRECAO"
  },
  "errorCode": "SEFAZ_REJECTED",
  "retryable": false
}
```

> `[CONTRATO]` `SEFAZ_REJECTED` expõe o `cStat`/`xMotivo` reais em `data` — não é preciso fazer parse do `soapRetorno` bruto para saber o motivo. `retryable: false` porque a causa geralmente é um dado incorreto (NCM, CFOP, CSOSN, endereço) que vai se repetir num reenvio sem correção. Corrija a causa e chame `/emitir` de novo no mesmo `pedidoId`.

> `[CONTRATO]` **(11-08-2026)** `data.serie`/`data.numeroNFe` identificam qual número fiscal ficou pendente de correção — o número **não** é consumido nem liberado em `AGUARDANDO_CORRECAO` (continua reservado para este `pedidoId` até corrigir e reemitir).

**Resposta — HTTP 409 (número fiscal ocupado por outra identidade fiscal na SEFAZ):**
```json
{
  "code": 409,
  "message": "O número fiscal do pedido 42 está ocupado por outra identidade fiscal na SEFAZ e não pôde ser autorizado. Uma nova tentativa de emissão usará o próximo número.",
  "data": {
    "cStat": null, "xMotivo": "NF-e já denegada",
    "serie": "1", "numeroNFe": 6, "estadoFiscal": "NUMERO_OCUPADO"
  },
  "errorCode": "NUMERO_FISCAL_OCUPADO",
  "retryable": true
}
```

> `[CONTRATO]` **(11-08-2026)** `NUMERO_FISCAL_OCUPADO` (Gate 3, reconciliação) — o número identificado em `data.numeroNFe` foi **consumido e queimado**, nunca reaproveitado; uma nova chamada a `/emitir` no mesmo `pedidoId` já abre um ciclo novo com o número seguinte. `data.cStat` pode vir `null` quando a reconciliação resolveu localmente (via `nfe_documento`) sem um cStat de consulta explícito — use `data.estadoFiscal` para a semântica, nunca um valor sintético.

> `[OPERACIONAL]` **(02-09-2026)** Um ciclo fiscal pode, excepcionalmente, ser encerrado por um **recovery administrativo interno** do Borurio (`ABANDONADO` para uma rejeição de schema incorrigível pelo fluxo normal; `TRANSPORTE_NAO_ENTREGUE` para uma tentativa comprovadamente não entregue ao autorizador da SEFAZ). O OMS não aciona nem observa esses estados diretamente: o `Pedido` volta a um status emissível (`REJEITADO` ou `ERRO`) e o comportamento de integração é o mesmo de sempre — reenviar `/emitir` no mesmo `pedidoId` abre um ciclo novo com o **número seguinte**. O `nNF` do ciclo encerrado **não é reutilizado** (mesma garantia de `NUMERO_FISCAL_OCUPADO`).

**Resposta — HTTP 422 (cadastro do emitente incompleto — não chega a chamar a SEFAZ):**
```json
{ "code": 422, "message": "Cadastro do emitente incompleto.", "data": null, "errorCode": "EMITTER_ADDRESS_INCOMPLETE", "retryable": false }
```

**Resposta — HTTP 422 (CFOP incompatível com o destino da operação — não chega a chamar a SEFAZ):**
```json
{
  "code": 422,
  "message": "CFOP 6102 incompatível com operação interna. Para idDest=1, o CFOP de saída deve iniciar com 5.",
  "data": null,
  "errorCode": "CFOP_DESTINATION_MISMATCH",
  "retryable": false
}
```

> `[CONTRATO]` **(20-07-2026)** `CFOP_DESTINATION_MISMATCH` ocorre quando o CFOP de algum item não é compatível com o tipo de operação calculado a partir da UF do emitente e da UF do destinatário (`idDest`): operação interna (mesma UF) exige CFOP iniciado por `5`; operação interestadual exige CFOP iniciado por `6`. A validação ocorre **antes** de qualquer reserva de estoque, reserva de numeração fiscal ou chamada à SEFAZ — nenhum `nNF` é consumido. O CFOP nunca é corrigido automaticamente pelo Borurio; corrija o valor enviado pelo OMS e chame `/emitir` de novo no mesmo `pedidoId`.

**Resposta — HTTP 409 (outra requisição já assumiu a emissão deste pedido):**
```json
{ "code": 409, "message": "Já existe uma emissão em andamento para este pedido.", "data": null, "errorCode": "EMISSAO_EM_ANDAMENTO", "retryable": true }
```

> `[CONTRATO]` `EMISSAO_EM_ANDAMENTO` ocorre quando duas chamadas a `/emitir` para o mesmo `pedidoId` colidem — apenas uma prossegue. A chamada que perde a corrida não deve criar um pedido novo. Aguarde um intervalo curto, consulte `GET /api/app/pedidos/{id}/situacao` e repita `/emitir` somente se o estado ainda permitir. `retryable: true` — trate a resposta de forma idempotente.

**Resposta — HTTP 422 (configuração fiscal inválida da empresa emitente):**
```json
{ "code": 422, "message": "Empresa possui indFinalPadrao inválido.", "data": null, "errorCode": "IND_FINAL_PADRAO_INVALIDO", "retryable": false }
```

> `[CONTRATO]` `IND_FINAL_PADRAO_INVALIDO` indica configuração fiscal inválida no cadastro da empresa emitente, interna ao Borurio — não é corrigível pelo payload do pedido e não deve ser reenviado automaticamente. Acionar a operação responsável pelo cadastro fiscal da empresa.

> `[CONTRATO]` **Numeração de NF-e.** O número (`nNF`) é isolado por CNPJ emitente e série, atribuído de forma atômica pelo motor fiscal a cada `/emitir`. Chamadas concorrentes para o mesmo pedido não geram números duplicados nem NF-e duplicadas (ver `EMISSAO_EM_ANDAMENTO` acima). A inicialização do contador de numeração para uma nova empresa/série é procedimento administrativo interno do Borurio, não uma chamada que o OMS realiza por emissão — não há endpoint de integração para isso nesta versão do contrato.

**Resposta — HTTP 503 (falha de rede na chamada à SEFAZ):**
```json
{ "code": 503, "message": "SEFAZ temporariamente indisponível.", "data": null, "errorCode": "SEFAZ_UNAVAILABLE", "retryable": true }
```

> `[CONTRATO]` `SEFAZ_TIMEOUT`/`SEFAZ_UNAVAILABLE` são as únicas falhas de `/emitir` com `retryable: true` — reenviar sem alterar nada é seguro nesses casos.

**Resposta — HTTP 422 (falha comprovadamente local, antes de qualquer possibilidade de transmissão à SEFAZ):**
```json
{ "code": 422, "message": "Falha local antes da transmissão à SEFAZ: <detalhe>", "data": null, "errorCode": "LOCAL_PROCESSING_FAILURE", "retryable": false }
```

> `[CONTRATO]` **(10-08-2026)** `LOCAL_PROCESSING_FAILURE` ocorre quando a falha aconteceu comprovadamente antes de qualquer I/O de rede com a SEFAZ (ex.: falha de assinatura digital, colisão interna de chave) — não representa rejeição SEFAZ, timeout, nem resultado fiscal incerto. `retryable: false`: a causa não se resolve sozinha com reenvio automático — exige correção de configuração/certificado ou investigação. Corrija a causa e chame `/emitir` de novo no mesmo `pedidoId`.

> `[OPERACIONAL]` Desde 10-08-2026, uma exceção não classificada só retorna HTTP 500 genérico se não houver nenhuma evidência de qual fase falhou; toda falha comprovadamente local retorna `LOCAL_PROCESSING_FAILURE` estruturado, e toda falha após início possível da transmissão retorna `SEFAZ_UNAVAILABLE` (503, retryable) em vez de HTTP 500. Verificar o estado do pedido via `GET /api/app/pedidos/{id}` antes de decidir se vale reenviar.

---

### 6.5 Consulta de Situação (Etapa 5)

> `[CONTRATO]` Pré-condição: o pedido ou seu ciclo fiscal (`nfe_emissao`) deve ter uma `chaveNfe` definida (ter passado por `/emitir`). Chamar antes de qualquer tentativa de emissão retorna HTTP 422.

> `[CONTRATO]` **(11-08-2026, Gate de contrato OMS) — este endpoint é a fonte recomendada para polling do OMS e NUNCA chama a SEFAZ ao vivo.** É leitura pura do estado persistido em `nfe_emissao` — nunca dispara a consulta SOAP `consSitNFe` a cada `GET`. Uma consulta ao vivo por requisição contornaria exatamente o claim atômico e o backoff que o Gate 3 (reconciliação) construiu, com risco real de consumo indevido/`cStat 656` sob polling repetido. Se o ciclo estiver `TRANSMITIDO`/`PENDENTE_CONFIRMACAO`, a reconciliação ativa contra a SEFAZ só acontece pelo mecanismo protegido: chamar `POST /emitir` de novo no mesmo `pedidoId` (delega para `NfeReconciliacaoService`, com claim+backoff).

```
GET /api/app/pedidos/{pedidoId}/situacao
Authorization: Bearer {token}
```

**Resposta — HTTP 200 (com ciclo fiscal em `nfe_emissao`):**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "pedidoId":      42,
    "numero":        "PED-00000042",
    "status":        "AUTORIZADO",
    "chaveNfe":      "35260512000000000000550010000000421000000424",

    "serie":         "1",
    "numeroNFe":     5,
    "estadoFiscal":  "AUTORIZADO",
    "cStat":         "100",
    "xMotivo":       "Autorizado o uso da NF-e",
    "nProt":         "135260512345678",
    "dhRecbto":      "2026-05-12T10:10:00",

    "consultaSefaz": null
  }
}
```

> `[CONTRATO]` **(11-08-2026)** `serie`, `numeroNFe`, `estadoFiscal`, `cStat`, `xMotivo`, `nProt` e `chaveNfe` vêm de `nfe_emissao` (o ciclo operacional/fiscal do número) sempre que existe um ciclo para o pedido — nunca de `nfe_documento` nesse caso. Diferente de `/emitir`, aqui **`cStat` é `string`** (ex.: `"100"`), preservando o mesmo tipo que o contrato usava antes desta versão — nunca alterna para `integer` conforme a origem interna do dado. `estadoFiscal` distingue explicitamente `AUTORIZADO`, `AGUARDANDO_CORRECAO`, `PENDENTE_CONFIRMACAO`, `NUMERO_OCUPADO` e demais estados do ciclo — use este campo em vez de inferir a partir de `cStat`/HTTP/campos nulos. Em `PENDENTE_CONFIRMACAO` sem resposta SEFAZ ainda, `cStat`/`xMotivo`/`nProt` vêm `null`, mas `estadoFiscal`, `serie` e `numeroNFe` continuam preenchidos.

> `[CONTRATO]` **(11-08-2026) `consultaSefaz` está DEPRECATED.** Mantido no payload por retrocompatibilidade (o contrato anterior o documentava como sempre presente) — sempre `null` a partir desta versão. Nunca mais executa a consulta live `consSitNFe`. Clientes que hoje fazem parse desse XML devem migrar para os campos estruturados acima (`cStat`/`xMotivo`/`nProt`/`estadoFiscal`).

> `[CONTRATO]` **Fallback legado sem `nfe_emissao`** (pedidos emitidos antes do Gate 1, 07-08-2026): `serie`/`numeroNFe`/`estadoFiscal` não aparecem na resposta; `chaveNfe`/`cStat`/`xMotivo`/`nProt`/`dhRecbto` vêm de `nfe_documento` como antes. `nfe_emissao` tem **precedência absoluta** sempre que existe — este fallback é exceção, nunca o caminho normal.

> `[CONTRATO]` **Chave fiscal quando `Pedido.chaveNfe` está `null`:** é possível `nfe_emissao.chaveNfe` estar congelada (após montagem do XML, antes da chamada à SEFAZ) enquanto `Pedido.chaveNfe` ainda está `null` — ex.: primeira tentativa de emissão que falhou por timeout de rede antes de qualquer resposta. Nesse cenário `/situacao` funciona normalmente, usando a chave de `nfe_emissao` — a pré-condição de chave nunca bloqueia exatamente o caso em que o polling da OMS é mais necessário.

| Campo                                                          | Presença                        | Origem                                                    |
|------------------------------------------------------------------|----------------------------------|------------------------------------------------------------|
| `pedidoId`, `numero`, `status`                                  | Sempre                          | Banco de dados local (`pedido`)                             |
| `chaveNfe`, `cStat`, `xMotivo`, `nProt`                          | Presentes sempre que há ciclo   | `nfe_emissao`; fallback `nfe_documento` só sem ciclo         |
| `serie`, `numeroNFe`, `estadoFiscal`                             | Presentes somente com ciclo     | `nfe_emissao` — ausentes no fallback legado                 |
| `dhRecbto`                                                       | Condicional                     | `nfe_documento` (se existir)                                 |
| `consultaSefaz`                                                  | Sempre presente, sempre `null`  | Campo legado/deprecated — nunca mais consulta a SEFAZ        |

---

### 6.6 Cancelamento de NF-e (Etapa 6)

> `[CONTRATO]` Pré-condições obrigatórias:
> 1. `status == "AUTORIZADO"` — única condição aceita
> 2. Protocolo de autorização (`nProt`) deve estar disponível no documento fiscal interno

```
POST /api/app/pedidos/{pedidoId}/cancelar
Authorization: Bearer {token}
Content-Type: application/json

{ "justificativa": "Motivo com no mínimo 15 caracteres" }
```

| Campo           | Regra                |
|-----------------|----------------------|
| `justificativa` | Mínimo 15 caracteres |

> `[EXEMPLO]` Justificativa válida: `"Erro no pedido — cliente solicitou cancelamento"`

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": "<retEvento>...</retEvento>"
}
```

> `[CONTRATO]` `data` é o XML bruto retornado pela SEFAZ. Após cancelamento bem-sucedido, o status do pedido é atualizado para `"CANCELADO"` (imutável).

**Resposta — HTTP 422 (pré-condição violada):**
```json
{ "code": 422, "message": "Cancelamento só é permitido para pedidos com status AUTORIZADO. Status atual: AGUARDANDO", "data": null }
```

---

### 6.7 Carta de Correção Eletrônica — CC-e (Etapa 7)

> `[CONTRATO]` Pré-condição: `status == "AUTORIZADO"`. A CC-e **não altera o status do pedido**.

```
POST /api/app/pedidos/{pedidoId}/cce
Authorization: Bearer {token}
Content-Type: application/json

{ "correcao": "Texto de correção com no mínimo 15 caracteres" }
```

| Campo      | Regra                                                         |
|------------|---------------------------------------------------------------|
| `correcao` | Mínimo 15 caracteres · Máximo 20 CC-e por NF-e (limite SEFAZ) |

**Resposta — HTTP 200:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": "<retEvento>...</retEvento>"
}
```

**Resposta — HTTP 422 (CNPJ do documento divergente do contexto fiscal resolvido):**
```json
{ "code": 422, "message": "O CNPJ da chave de acesso não corresponde ao CNPJ da empresa resolvida para esta operação.", "data": null, "errorCode": "DOCUMENTO_CNPJ_DIVERGENTE", "retryable": false }
```

> `[CONTRATO]` **Contexto fiscal multi-CNPJ em consulta, cancelamento e CC-e.** Empresa, certificado e UF são resolvidos pelo CNPJ real da operação — nunca por configuração global. O CNPJ embutido na chave de acesso do documento é validado contra esse contexto antes de prosseguir; não existe fallback silencioso para uma empresa global. Se o CNPJ da chave não corresponder à empresa resolvida, a operação falha explicitamente com `DOCUMENTO_CNPJ_DIVERGENTE` — não repetir automaticamente; revisar pedido, chave, empresa e autorização fiscal, e acionar a operação responsável pelo Borurio se a causa não estiver clara.

---

### 6.8 DANFE — Documento Auxiliar da Nota Fiscal Eletrônica

> `[CONTRATO]` O DANFE é o documento PDF complementar da NF-e emitida. É gerado a partir do XML assinado armazenado em `nfe_documento`. Requer autenticação JWT (qualquer role).

```
GET /api/fiscal/nfe/{chaveNfe}/danfe
Authorization: Bearer {token}
```

| Parâmetro  | Regra                                        |
|------------|----------------------------------------------|
| `chaveNfe` | Exatamente 44 dígitos numéricos (path var)   |

**Resposta de sucesso — HTTP 200:**
- `Content-Type: application/pdf`
- `Content-Disposition: attachment; filename="danfe-{chaveNfe}.pdf"`
- Body: bytes do PDF

**Respostas de erro:**

| Código | Causa                                                |
|--------|------------------------------------------------------|
| 400    | Chave de acesso não tem 44 dígitos                   |
| 401    | Token ausente ou inválido                            |
| 404    | NF-e não encontrada na tabela `nfe_documento`        |
| 500    | Erro interno durante a geração do PDF                |

> `[OPERACIONAL]` Em HOM (homologação, `tpAmb=2`), o DANFE exibe marca d'água diagonal "SEM VALOR FISCAL" em cinza claro. A marca d'água é suprimida em PRD (`tpAmb=1`).

> `[OPERACIONAL]` O rótulo de protocolo no DANFE é condicional: `PROTOCOLO DE AUTORIZAÇÃO DE USO` quando autorizada (cStat=100 + nProt presente); `RETORNO SEFAZ — HOMOLOGAÇÃO` em homologação quando ainda não autorizada (tpAmb=2, cStat≠100).

---

### 6.9 Manifestação do Destinatário

> `[OPERACIONAL]` A Manifestação do Destinatário é um conjunto de eventos fiscais enviados pelo **destinatário** de uma NF-e à SEFAZ para registrar sua posição sobre a nota recebida. Não faz parte do fluxo principal de emissão OMS → NF-e.

```
POST /api/fiscal/nfe/manifestar
Authorization: Bearer {token}
Content-Type: application/json
```

**Payload:**

| Campo | Tipo | Regra |
|---|---|---|
| `chaveNfe` | String | Obrigatório · Exatamente 44 dígitos numéricos |
| `tipoEvento` | String | Obrigatório · Um dos 4 valores válidos abaixo |
| `cnpjDestinatario` | String | Obrigatório · 14 dígitos numéricos (sem formatação) |
| `xJust` | String | Condicional · Obrigatório somente para `210240` · Mínimo 15 / máximo 255 caracteres |

**Tipos de evento suportados:**

| Código | Descrição |
|---|---|
| `210200` | Ciência da Operação |
| `210210` | Confirmação da Operação |
| `210220` | Desconhecimento da Operação |
| `210240` | Operação Não Realizada |

> `[EXEMPLO]` Payload para Ciência da Operação:
```json
{
  "chaveNfe":         "{{chaveNfe}}",
  "tipoEvento":       "210200",
  "cnpjDestinatario": "{{cnpjDestinatario}}"
}
```

> `[EXEMPLO]` Payload para Operação Não Realizada (xJust obrigatório):
```json
{
  "chaveNfe":         "{{chaveNfe}}",
  "tipoEvento":       "210240",
  "cnpjDestinatario": "{{cnpjDestinatario}}",
  "xJust":            "Mercadoria não recebida pelo destinatário conforme acordado"
}
```

**Resposta de sucesso — HTTP 200:**
```json
{
  "code":    200,
  "success": true,
  "data":    "135 - Evento registrado e vinculado a NF-e"
}
```

**Resposta de erro (dados inválidos ou rejeição SEFAZ) — HTTP 200:**
```json
{
  "code":    500,
  "success": false,
  "message": "Falha ao registrar manifestação: <detalhe do erro>"
}
```

> `[CONTRATO]` O campo `success: false` com `code: 500` indica rejeição pela SEFAZ ou dado inválido (chave malformada, tipo de evento incorreto, xJust ausente para 210240). O status HTTP sempre retorna 200; o código semântico está no envelope.

> `[CONTRATO]` Autenticação obrigatória. Request sem token retorna HTTP 401.

> `[OPERACIONAL]` **Limitação HOM:** o endpoint AN (Ambiente Nacional) da SEFAZ HOM pode retornar HTTP 403 a partir de redes residenciais/locais. Esse comportamento é de infraestrutura da SEFAZ federal, não um erro da API. O endpoint `/manifestar` funcionará normalmente em ambiente corporativo e PRD.

---

### 6.10 Sincronização de Série e Numeração NF-e (OMS → Borurio)

> `[PROPOSTA — AGUARDANDO CONFIRMAÇÃO FINAL DO CC]` Implementado internamente em 20/07/2026. O formato de `errorCode` abaixo ainda não foi confirmado como tratável pela OMS — não considerar este contrato definitivamente fechado até essa confirmação chegar. Não implantado em HOM.

> `[CONTRATO]` Substitui o modelo de "baseline único no onboarding": a OMS chama este endpoint toda vez que o cliente altera série ou numeração no próprio sistema deles — não apenas uma vez.

```
PUT /api/integration/fiscal-numbering/{cnpj}
Authorization: Bearer {token}   (mesmo token de /api/integration/fiscal-authorizations)
Content-Type: application/json
```

**Payload:**

| Campo | Tipo | Regra |
|---|---|---|
| `serie` | String | Obrigatório · até 3 caracteres |
| `proximoNumero` | Integer | Obrigatório · maior ou igual a 1 · próximo `nNF` que o Borurio deve usar |

> `[CONTRATO]` `cnpj` (path), identidade do cliente OMS e `requestId` **nunca** são lidos do body — sempre resolvidos pela autorização autenticada (mesmo token de `/pedidos`) e pelo header `X-Request-Id`/MDC.

**Resposta de sucesso — HTTP 200:**
```json
{
  "cnpjEmitente": "12000000000195",
  "serieAnterior": "1",
  "serieAtual": "1",
  "proximoNumeroAnterior": 100,
  "proximoNumeroAtual": 101,
  "aplicado": true,
  "atualizadoEm": "2026-07-23T10:00:00"
}
```

> `[CONTRATO]` `aplicado: false` indica chamada idempotente (o valor enviado já era exatamente o vigente) — não é erro, é sucesso sem mudança de estado. `serieAnterior`/`serieAtual` referem-se à série padrão da empresa; `proximoNumeroAnterior`/`proximoNumeroAtual` referem-se sempre à sequência da série de destino (`serieAtual`) — nunca uma mistura entre a numeração da série antiga e da nova.

> `[CONTRATO]` Regressão de numeração é sempre rejeitada — não existe modo de forçar um `proximoNumero` menor que o já registrado por este endpoint.

> `[CONTRATO]` Uma emissão que já reservou série+número antes desta chamada nunca é alterada retroativamente — a sincronização só afeta a próxima reserva (ver 6.4).

**Cenários negativos — `errorCode`:**

| `errorCode` | HTTP | Situação |
|---|---|---|
| `NUMERACAO_INFERIOR_A_ATUAL` | 422 | `proximoNumero` menor que o já registrado |
| `SERIE_INVALIDA` | 422 | `serie` ausente, vazia ou maior que 3 caracteres |
| `NUMERACAO_INVALIDA` | 422 | `proximoNumero` ausente, zero ou negativo |
| `CNPJ_NOT_AUTHORIZED` | 403 | CNPJ não autorizado para o token |

---

## 7. Máquina de Estados do Pedido

> `[CONTRATO]` Tabela de estados válidos e operações permitidas por estado:

| Estado       | Significado                             | Operações permitidas                  |
|--------------|-----------------------------------------|---------------------------------------|
| `RASCUNHO`   | Pedido criado, não transmitido          | `/emitir`                             |
| `AUTORIZADO` | NF-e aprovada pela SEFAZ (cStat=100)    | `/situacao`, `/cancelar`, `/cce`      |
| `AGUARDANDO` | Transmitido; confirmação SEFAZ pendente | `/situacao`                           |
| `REJEITADO`  | SEFAZ recusou (cStat ≥ 200)             | `/emitir` (reemissão no mesmo pedido, v1.9) |
| `CANCELADO`  | NF-e cancelada com protocolo            | Nenhuma — imutável                    |
| `ERRO`       | Falha técnica durante transmissão       | `/emitir` (reemissão no mesmo pedido, v1.9) |

> `[CONTRATO]` As transições de estado são gerenciadas exclusivamente pelo motor fiscal. O ERP logístico não deve assumir ou forçar transições.

> `[CONTRATO]` Desde a v1.9, `REJEITADO` e `ERRO` não são mais terminais via API — `POST /emitir` pode ser chamado novamente no mesmo `pedidoId` a qualquer momento após corrigir a causa (ver seção 6.4). Apenas `AUTORIZADO` e `CANCELADO` são terminais.

---

## 8. Padrão de Resposta da API

### 8.1 Envelope Padrão de Sucesso

> `[CONTRATO]` Todos os endpoints `/api/**` retornam o envelope `Result<>`, **exceto** `/auth/login`, `/api/test/ping` e `/api/integration/fiscal-authorizations`, que têm estrutura própria (documentadas nas seções 3.1, 3.3 e 6.1).

```json
{ "code": 200, "message": "Sucesso", "data": { ... } }
```

### 8.2 Respostas de Erro

> `[CONTRATO]` Erros HTTP 401 e 403 têm estrutura diferente dos demais — campo `"success": false` em vez de `"data"`:

```json
{ "code": 401, "message": "Autenticação necessária", "success": false }
{ "code": 403, "message": "Acesso negado",           "success": false }
```

> `[CONTRATO]` Todos os outros erros seguem o envelope padrão com `"data": null` e, desde a v1.9, o campo `"retryable"`:

```json
{ "code": 400, "message": "Descrição do erro",        "data": null, "retryable": false }
{ "code": 404, "message": "Recurso não encontrado",   "data": null, "retryable": false }
{ "code": 422, "message": "Descrição do erro",        "data": null, "retryable": false }
{ "code": 500, "message": "Erro interno do servidor", "data": null, "retryable": false }
```

> `[CONTRATO]` Erros de validação de campo (`@Valid`) retornam HTTP 422 com o **mapa de erros em `data`**:

```json
{
  "code": 422,
  "message": "Dados inválidos",
  "data": {
    "destCnpjCpf": "CNPJ/CPF do destinatário é obrigatório"
  }
}
```

**Tabela de referência:**

| HTTP       | Trigger                                                 | Estrutura de `data`                   |
|------------|---------------------------------------------------------|---------------------------------------|
| 400        | JSON malformado ou parâmetro inválido de negócio        | `null`                                |
| 401        | Token ausente ou expirado                               | Campo `"success": false` (sem `data`) |
| 403        | Role insuficiente                                       | Campo `"success": false` (sem `data`) |
| 404        | ID não encontrado na empresa autenticada                | `null`                                |
| 422 @Valid | Campo inválido na entidade                              | `{ "campo": "mensagem" }`             |
| 422 estado | Operação não permitida no estado atual                  | `null`                                |
| 500        | Falha não tratada (incluindo erro de transmissão SEFAZ) | `null`                                |

### 8.2a Códigos de Erro de Negócio (`errorCode`)

> `[CONTRATO]` Erros de negócio incluem o campo `errorCode` além dos campos padrão `code` e `message`. Use `errorCode` como identificador estável para tratamento programático — **não faça parse do campo `message`**, que está em português e pode mudar.

**Resposta de erro com `errorCode`:**
```json
{
  "code":      422,
  "message":   "Estoque insuficiente para \"Produto A\" (disponível: 0.0000, solicitado: 5.00)",
  "data":      null,
  "errorCode": "INSUFFICIENT_STOCK"
}
```

> `[CONTRATO]` O campo `errorCode` está presente **somente em respostas de erro de negócio**. Respostas de sucesso (`code: 200`) não o incluem.

> `[CONTRATO]` Desde a v1.9, toda resposta de erro (não só as de negócio) inclui o campo booleano `retryable`. `retryable: true` significa que reenviar a mesma requisição sem alterar nada é seguro (falha transitória — timeout, indisponibilidade). `retryable: false` significa que é preciso corrigir o dado/causa antes de tentar de novo — reenviar sem corrigir repete o mesmo erro. **Não infira retry a partir do `message`** (texto livre em português) — use sempre o campo `retryable`.

**Códigos de erro definidos:**

| `errorCode`            | HTTP | `retryable` | Gatilho                                                                                        |
|------------------------|------|-------------|--------------------------------------------------------------------------------------------------|
| `INVALID_ORDER_STATUS` | 422  | false | Pedido não está no estado esperado para a operação (ex: não é `RASCUNHO`/`REJEITADO`/`ERRO` para `/emitir`; não é `AUTORIZADO` para `/cancelar` ou `/cce`) |
| `EMISSAO_EM_ANDAMENTO` | 409  | **true** | Outra requisição já assumiu a emissão do mesmo pedido — não criar pedido novo; aguardar, consultar `/situacao` e repetir `/emitir` somente se o estado permitir |
| `IND_FINAL_PADRAO_INVALIDO` | 422 | false | Configuração fiscal inválida (`indFinal`) no cadastro da empresa emitente — interna ao Borurio, não corrigível pelo payload do pedido |
| `DOCUMENTO_CNPJ_DIVERGENTE` | 422 | false | O CNPJ da chave de acesso do documento não corresponde ao CNPJ da empresa fiscal resolvida para a operação (consulta, cancelamento, CC-e) |
| `INSUFFICIENT_STOCK`   | 422  | false | Estoque disponível (`estoqueDisponivel`) é inferior à quantidade solicitada para o item — não ocorre para empresas com controle de estoque desativado (ver nota abaixo) |
| `PRODUCT_NOT_FOUND`    | 422  | false | Um item referencia `produtoId` que não existe para a empresa autenticada                      |
| `PRODUCT_INACTIVE`          | 422                          | false | Um item referencia produto com `estado = 0` (inativo)                                                                               |
| `VALIDATION_ERROR`          | 207 `resultados[].errorCode` | false | Item do batch falhou na validação de campos — NCM inválido, campo obrigatório vazio ou preço ≤ 0                                     |
| `BATCH_LIMIT_EXCEEDED`      | 422                          | false | A requisição batch contém mais de 200 produtos — retornado antes de qualquer item ser processado                                     |
| `DUPLICATE_CODIGO_IN_BATCH` | 207 `resultados[].errorCode` | false | O mesmo `codigo` aparece mais de uma vez no mesmo payload batch — a segunda ocorrência é rejeitada; a primeira é processada normalmente |
| `INVALID_API_KEY`           | 401                          | false | Header `X-Api-Key` ausente, inválido, expirado ou revogado — necessário para `POST /api/integration/fiscal-authorizations` |
| `COMPANY_INACTIVE`          | 422                          | false | Empresa com o CNPJ informado existe no Borurio, mas está marcada como inativa — acionar o ADMIN para reativação |
| `INVALID_CERTIFICATE`       | 422                          | false | Certificado A1 inválido — base64 malformado, PKCS12 corrompido, senha incorreta ou nenhum certificado X.509 encontrado no arquivo |
| `CNPJ_CERTIFICATE_MISMATCH` | 422                          | false | CNPJ enviado no payload não corresponde ao CNPJ embutido no Subject do certificado X.509 |
| `CERTIFICATE_EXPIRED`       | 422                          | false | Certificado A1 está expirado — substituir pelo certificado renovado e reautorizar |
| `AUTHORIZATION_REVOKED`     | 401                          | false | Token OMS foi revogado administrativamente — reautorizar com certificado válido ou aguardar resolução pelo ADMIN |
| `CNPJ_NOT_AUTHORIZED`       | 403                          | false | CNPJ informado em `cnpjEmitente` não possui autorização ativa para este cliente OMS — realizar `POST /api/integration/fiscal-authorizations` com o certificado desse CNPJ antes de emitir |
| `CERT_NOT_FOUND_FOR_CNPJ`   | 422                          | false | Nenhum certificado ativo encontrado para o CNPJ emitente — verificar se a autorização fiscal foi realizada para esse CNPJ |
| `EMITTER_ADDRESS_INCOMPLETE` | 422 | false | **(v1.9)** Cadastro da empresa emitente sem endereço completo (`logradouro`/`numero`/`bairro`/`codigoMunicipio`/`municipio`/`cep`) — bloqueado antes de chamar a SEFAZ. Enviar os campos `emit*` em `POST /api/app/pedidos` (seção 6.3) e reemitir. |
| `CFOP_DESTINATION_MISMATCH` | 422 | false | **(20-07-2026)** CFOP de algum item incompatível com o tipo de operação (`idDest`, calculado pela UF do emitente × UF do destinatário) — operação interna exige CFOP iniciado por `5`, interestadual por `6`. Bloqueado antes da reserva fiscal e da SEFAZ; CFOP nunca é corrigido automaticamente. Corrigir o valor enviado e chamar `/emitir` de novo no mesmo pedido. |
| `SEFAZ_REJECTED`            | 422 | false | **(v1.9)** SEFAZ processou a chamada e rejeitou a NF-e (cStat ≥ 200). `data.cStat`/`data.xMotivo` trazem o motivo real. Geralmente é dado incorreto — corrija e chame `/emitir` de novo no mesmo pedido. |
| `SEFAZ_TIMEOUT`             | 503 | **true** | **(v1.9)** Tempo limite excedido na chamada à SEFAZ — falha de rede transitória. |
| `SEFAZ_UNAVAILABLE`         | 503 | **true** | **(v1.9)** SEFAZ inacessível (conexão recusada/DNS) — falha de rede transitória. |
| `XML_SCHEMA_INVALID`        | 422 | false | **(v1.9)** XML gerado não passou na validação de schema local antes de ser assinado/transmitido — problema de dado, não de rede. |
| `LOCAL_PROCESSING_FAILURE`  | 422 | false | **(10-08-2026)** Falha comprovadamente local, ocorrida antes de qualquer possibilidade de transmissão à SEFAZ (ex.: assinatura digital, colisão interna de chave) — nunca representa timeout, rejeição SEFAZ ou resultado incerto. Corrigir a causa e chamar `/emitir` de novo no mesmo pedido; não é retry automático. |
| `NUMERACAO_INFERIOR_A_ATUAL` | 422 | false | **(proposta 20-07-2026, aguardando confirmação do CC)** `PUT /api/integration/fiscal-numbering/{cnpj}` — `proximoNumero` menor que o já registrado. Ver seção 6.10. |
| `SERIE_INVALIDA`            | 422 | false | **(proposta 20-07-2026)** `PUT /api/integration/fiscal-numbering/{cnpj}` — `serie` ausente, vazia ou inválida. Ver seção 6.10. |
| `NUMERACAO_INVALIDA`        | 422 | false | **(proposta 20-07-2026)** `PUT /api/integration/fiscal-numbering/{cnpj}` — `proximoNumero` ausente, zero ou negativo. Ver seção 6.10. |

> `[OPERACIONAL]` O OMS deve usar `errorCode` para toda lógica condicional. O campo `message` é destinado a logs legíveis por humanos. O HTTP status isolado não é suficiente para distinguir `INSUFFICIENT_STOCK`, `PRODUCT_NOT_FOUND` e `PRODUCT_INACTIVE`, que todos retornam HTTP 422.

> `[CONTRATO]` Controle de estoque é opcional por empresa. Por padrão, toda empresa valida, reserva e baixa estoque normalmente em `/emitir` — comportamento inalterado. Para clientes OMS que não trabalham com controle de estoque, o Borurio pode desativar essa validação por empresa (configuração interna, não exposta via API de integração). Quando desativado, `/emitir` nunca retorna `INSUFFICIENT_STOCK` e o saldo do produto não é alterado em nenhuma etapa (emissão, rejeição ou cancelamento). O OMS não decide o estado dessa configuração por pedido — é definida por empresa, do lado do Borurio.

---

### 8.3 Referência de Mensagens da API

> `[OPERACIONAL]` O campo `message` em todas as respostas da API é sempre em português. A tabela abaixo documenta cada mensagem fixa do sistema, seu significado e quando ela ocorre.

| Mensagem (campo `message`)                      | Significado                                   | Quando ocorre                                                         |
|-------------------------------------------------|-----------------------------------------------|-----------------------------------------------------------------------|
| `Autenticação bem-sucedida`                     | Login realizado com sucesso                   | Credenciais válidas no `POST /auth/login`                            |
| `Credenciais inválidas`                         | E-mail ou senha incorretos                    | Credenciais erradas no login                                          |
| `Erro interno de autenticação`                  | Falha interna no servidor durante login       | Exceção não tratada no fluxo de autenticação                          |
| `Autenticação necessária`                       | Token ausente ou expirado                     | Request sem `Authorization: Bearer` válido                            |
| `Acesso negado`                                 | Role insuficiente para o endpoint             | Role `OPERADOR` acessando rota exclusiva de `ADMIN`                   |
| `Sucesso`                                       | Operação concluída com sucesso                | Qualquer resposta `code: 200` da API                                  |
| `Registro não encontrado`                       | ID não existe para a empresa autenticada      | `GET /{id}` sem correspondência no banco                              |
| `Recurso não encontrado`                        | Resultado vazio para o parâmetro solicitado   | PathVariable ou chave sem registro correspondente                     |
| `Erro interno do servidor`                      | Exceção não tratada (incluindo erros SEFAZ)   | Falha técnica ou erro de transmissão fiscal                           |
| `Dados inválidos`                               | Falha em validação `@Valid` de campo          | Body com campos fora das regras — retorna mapa de erros em `data`     |
| `Falha na operação`                             | Violação de regra de negócio                  | Operação não permitida no estado atual do pedido                      |
| `Requisição inválida`                           | Body malformado ou parâmetro inesperado       | JSON inválido ou tipo de dado incorreto                               |
| `CNPJ/CPF do destinatário é obrigatório`        | Campo `destCnpjCpf` ausente ou vazio          | Criação de pedido sem CNPJ/CPF do destinatário (HTTP 422)             |
| `Razão social do destinatário é obrigatória`    | Campo `destRazaoSocial` ausente               | Criação de pedido sem razão social (HTTP 422)                         |
| `UF do destinatário é obrigatória`              | Campo `destUf` ausente                        | Criação de pedido sem UF do destinatário (HTTP 422)                   |

> `[OPERACIONAL]` Mensagens de validação (HTTP 422) são retornadas em `data` como mapa chave→mensagem, onde a chave é o nome do campo JSON que falhou.

---

### 8.4 Paginação

> `[CONTRATO]` Endpoints de listagem aceitam `page` (0-based) e `size` (máx 100):

```
GET /api/app/pedidos?page=0&size=20
GET /api/app/produtos?page=0&size=20
```

**Resposta:**
```json
{
  "code": 200,
  "message": "Sucesso",
  "data": {
    "content":       [ ... ],
    "page":          0,
    "size":          20,
    "totalElements": 150,
    "totalPages":    8,
    "first":         true,
    "last":          false
  }
}
```

> `[OPERACIONAL]` O endpoint `GET /api/app/pedidos` (lista paginada) **não inclui os itens de cada pedido**. Para obter os itens, usar `GET /api/app/pedidos/{id}`.

---

### 8.5 Rastreamento de Requisições — `X-Request-Id`

> `[OPERACIONAL]` Toda resposta da API inclui um header `X-Request-Id` com UUID que identifica unicamente a requisição nos logs do servidor.

**Comportamento:**
- Se a OMS enviar um header `X-Request-Id` na requisição, o sistema usa esse valor e o retorna no header de resposta
- Se o header estiver ausente ou em branco, o sistema gera um UUID aleatório automaticamente
- O valor aparece em todas as linhas de log do servidor para aquela requisição — habilitando correlação ponta a ponta

> `[OPERACIONAL]` Para correlacionar logs da OMS com logs do servidor da API, inclua um ID de correlação na requisição:
```
POST /api/app/produtos/batch
X-Request-Id: oms-batch-20260601-001
```
O header de resposta conterá: `X-Request-Id: oms-batch-20260601-001`

> `[OPERACIONAL]` Respostas de erro de negócio também incluem `requestId` no corpo JSON:
```json
{
  "code":      422,
  "message":   "O lote excede o limite máximo de 200 produtos por requisição.",
  "data":      null,
  "errorCode": "BATCH_LIMIT_EXCEEDED",
  "requestId": "oms-batch-20260601-001"
}
```

> `[OPERACIONAL]` Respostas HTTP 401 incluem `X-Request-Id` no header de resposta mesmo antes da autenticação ser resolvida — permitindo à OMS correlacionar tentativas de autenticação falhas com os logs do servidor.

---

## 9. Smoke Test — Ambiente HOM

> `[CONTRATO]` A sequência abaixo deve ser executada e validada em HOM antes da integração em PRD.

### 9.1 Sequência de Validação

| # | Request                                              | Critério de PASS                               |
|---|------------------------------------------------------|------------------------------------------------|
| 1 | `GET /api/test/ping`                                 | HTTP 200 · `status = "UP"`                     |
| 2 | `POST /auth/login`                                   | HTTP 200 · `token` não nulo                    |
| 3 | `POST /api/app/produtos`                             | HTTP 200 · `data.id` retornado                 |
| 4 | `GET /api/app/produtos?page=0&size=5`                | HTTP 200 · `data.totalElements ≥ 1`            |
| 5 | `POST /api/app/pedidos` (com `produtoId` do passo 3) | HTTP 200 · `data.status = "RASCUNHO"`          |
| 6 | `POST /api/app/pedidos/{id}/emitir`                  | HTTP 200 · `data.soapRetorno` não vazio        |
| 7 | `GET /api/app/pedidos/{id}/situacao`                 | HTTP 200 · `data.chaveNfe` preenchida          |
| 8  | `GET /api/app/pedidos/{id}`                                            | HTTP 200 · `data.itens` com campos de snapshot                       |
| 9  | `POST /api/app/produtos/batch` (1 produto novo, `codigo: "BATCH-001"`) | HTTP 207 · `criados=1` · `resultados[0].status = "CRIADO"`          |
| 10 | `POST /api/app/produtos/batch` (mesmo produto novamente)               | HTTP 207 · `atualizados=1` · `resultados[0].status = "ATUALIZADO"`  |
| 11 | Inspecionar headers de resposta de qualquer requisição                 | Header `X-Request-Id` presente · formato UUID                        |

### 9.1b Smoke Test OMS Multi-CNPJ

> `[OPERACIONAL]` Executar esta sequência complementar para validar o fluxo multi-CNPJ quando o cliente OMS possuir mais de um CNPJ autorizado.

| # | Request | Critério de PASS |
|---|---|---|
| M1 | `POST /api/integration/fiscal-authorizations` — CNPJ1 do cliente OMS | HTTP 200 · `token` retornado · empresa auto-criada |
| M2 | `POST /api/integration/fiscal-authorizations` — CNPJ2 do **mesmo** `codigoEmpresaOms` | HTTP 200 · **mesmo `token`** da chamada M1 |
| M3 | `POST /api/integration/fiscal-authorizations` — CNPJ1, mesmo cert reenviado (cenário B) | HTTP 200 · mesmo token · nenhuma alteração no banco |
| M4 | `POST /api/app/pedidos` com `cnpjEmitente: "{CNPJ2}"` usando o token OMS | HTTP 200 · `data.cnpjEmitente` = CNPJ2 · `data.status = "RASCUNHO"` |
| M5 | `POST /api/app/pedidos/{id}/emitir` do pedido M4 | HTTP 200 · NF-e assinada com cert do CNPJ2 |
| M6 | `POST /api/app/pedidos` com `cnpjEmitente: "{CNPJ3-nao-autorizado}"` | HTTP 403 · `errorCode: "CNPJ_NOT_AUTHORIZED"` |

> `[CONTRATO]` O teste M2 é o critério de aceitação mais importante do fluxo multi-CNPJ: confirma que o token não muda ao adicionar um segundo CNPJ ao mesmo cliente OMS.

### 9.1c Smoke Test — Endereço do Emitente e Reemissão (v1.9)

> `[OPERACIONAL]` Executar esta sequência ao autorizar uma empresa nova pela primeira vez, ou para validar o fluxo de reemissão após uma rejeição.

| # | Request | Critério de PASS |
|---|---|---|
| R1 | `POST /api/app/pedidos/{id}/emitir` numa empresa nova (endereço ainda incompleto) | HTTP 422 · `errorCode: "EMITTER_ADDRESS_INCOMPLETE"` · `retryable: false` · SEFAZ não é chamada |
| R2 | `POST /api/app/pedidos` com campos `emit*` preenchidos, mesma empresa | HTTP 200 · cadastro da empresa completado (verificar via `GET /api/app/empresas/{id}` se tiver acesso ADMIN) |
| R3 | `POST /api/app/pedidos/{id}/emitir` do pedido criado em R2 | HTTP 200 (`AUTORIZADO`/`AGUARDANDO`) ou HTTP 422 `SEFAZ_REJECTED` — mas **não** mais `EMITTER_ADDRESS_INCOMPLETE` |
| R4 | Se R3 retornou `SEFAZ_REJECTED` ou o pedido ficou em `ERRO`: `POST /api/app/pedidos/{id}/emitir` **no mesmo `pedidoId`** | HTTP 200 ou novo `SEFAZ_REJECTED` — `chaveNfe` diferente da tentativa anterior · sem precisar criar pedido novo |
| R5 | `POST /api/app/pedidos/{id}/emitir` num pedido `AUTORIZADO` | HTTP 422 · `errorCode: "INVALID_ORDER_STATUS"` · `retryable: false` |

> `[CONTRATO]` O teste R4 é o critério de aceitação do fluxo de reemissão: confirma que `REJEITADO`/`ERRO` não são terminais e que o mesmo `pedidoId` pode ser reutilizado.

### 9.2 Verificações de Segurança

| Request                                                         | Resultado esperado                                        |
|-----------------------------------------------------------------|-----------------------------------------------------------|
| `GET /api/app/pedidos` sem `Authorization`                      | HTTP 401 · `"Autenticação necessária"` · `success: false` |
| `GET /api/app/usuarios` com token de role `OPERADOR`            | HTTP 403 · `"Acesso negado"` · `success: false`           |
| `GET /api/app/pedidos` com token da empresa B                   | HTTP 200 · `data.content = []` (isolamento tenant)        |
| `POST /api/app/pedidos/9999/emitir`                             | HTTP 404 · `data: null`                                   |
| `POST /api/app/pedidos/{id}/cancelar` com `justificativa` vazia | HTTP 400 · mensagem de mínimo 15 caracteres               |

### 9.3 Comportamento Esperado em HOM-SP

> `[OPERACIONAL]` **Correção 14-07-2026:** o `cStat=225` observado em 14-07-2026 foi causado por XML incompatível com o schema da NF-e, relacionado à estrutura e aos algoritmos declarados na assinatura XMLDSig. Após o alinhamento ao schema oficial, o fluxo deixou de retornar essa rejeição. Com a correção, o resultado esperado em HOM-SP para uma empresa com cadastro fiscal aceito pela SEFAZ na emissão de homologação é `cStat=100` (`AUTORIZADO`) — confirmado em teste interno e em teste do integrador chinês via OMS, com verificação cruzada no portal público da SEFAZ. `cStat=225` deixou de ser o comportamento padrão; se ocorrer, trate como rejeição real e inspecione `data.xMotivo`. Desde a v1.9, `cStat≥200` resulta em `REJEITADO` e `POST /emitir` retorna HTTP 422 com `errorCode: SEFAZ_REJECTED` — não HTTP 200 (ver seção 6.4). Problemas cadastrais do emitente, incluindo Inscrição Estadual inapta ou cassada, devem ser tratados conforme o `cStat` e o `xMotivo` efetivamente retornados pela SEFAZ na tentativa — não são associados a `225`.

> `[OPERACIONAL]` O `verAplic` no retorno da SEFAZ indica qual processador respondeu (ex.: `SP_NFE_PL_008i2`, `SP_NFE_PL009_V4`) — pode variar entre o nível de lote (`retEnviNFe`) e o nível de protocolo individual (`protNFe/infProt`) na mesma resposta. Isso não é, por si só, indicativo de erro — o `xMotivo` retornado em `data.xMotivo` é a fonte confiável do motivo real de uma eventual rejeição.

Resultado esperado de `POST /api/app/pedidos/{id}/emitir` em HOM-SP para uma empresa com cadastro fiscal aceito pela SEFAZ na emissão de homologação:

```
HTTP 200
data.chaveNfe: <44 dígitos>
pedido.status: AUTORIZADO
data.cStat:    100
data.xMotivo:  "Autorizado o uso da NF-e"
```

Se a NF-e for rejeitada (dado incorreto, IE inválida/cassada, etc.), o resultado é:

```
HTTP 422
errorCode: SEFAZ_REJECTED
data.cStat:    <código retornado pela SEFAZ>
data.xMotivo:  <motivo retornado pela SEFAZ>
pedido.status: REJEITADO → pode ser reemitido no mesmo pedido após corrigir a causa (v1.9)
```

> `[CONTRATO]` Para validar o fluxo completo em HOM, verificar `data.soapRetorno` bruto do `/emitir` (quando HTTP 200) ou `data.cStat`/`data.xMotivo` (quando `SEFAZ_REJECTED`), e `data.consultaSefaz` da situação — esses campos contêm a resposta real da SEFAZ independente do status final do pedido.

### 9.4 Matriz de Comportamento Contratual

> `[CONTRATO]` Cenários formais de comportamento da API relevantes para a integração. O roteiro de execução detalhado, passo a passo, permanece em `CHECKLIST_OMS_ONBOARDING.md`.

| Cenário | Resposta esperada | Ação do OMS |
|---|---|---|
| Duas chamadas simultâneas a `/emitir` para o mesmo pedido | Uma prossegue normalmente; a outra recebe `HTTP 409` `EMISSAO_EM_ANDAMENTO` | Não criar pedido novo na chamada perdedora; aguardar, consultar `/situacao`, repetir se aplicável |
| Empresa emitente não autorizada para o CNPJ do pedido | `HTTP 403` `CNPJ_NOT_AUTHORIZED` | Executar `POST /api/integration/fiscal-authorizations` para o CNPJ antes de tentar novamente |
| `indFinal` configurado com valor inválido na empresa | `HTTP 422` `IND_FINAL_PADRAO_INVALIDO` | Não corrigir pelo payload do pedido; acionar a operação responsável pelo cadastro fiscal da empresa |
| CNPJ do documento/chave divergente do contexto fiscal resolvido | `HTTP 422` `DOCUMENTO_CNPJ_DIVERGENTE` | Não repetir automaticamente; revisar pedido, chave, empresa e autorização fiscal |
| Evento fiscal (consulta/cancelamento/CC-e) em empresa multi-CNPJ | Empresa, certificado e UF resolvidos pelo CNPJ real da operação, sem fallback para configuração global | Nenhuma ação adicional — comportamento transparente ao OMS quando o CNPJ está correto |
| Reemissão após `REJEITADO` ou `ERRO` | `POST /emitir` reutiliza o mesmo `pedidoId`; nova `chaveNfe` a cada tentativa | Corrigir a causa indicada em `data.cStat`/`data.xMotivo` e chamar `/emitir` de novo no mesmo pedido |
| Controle de estoque desativado para a empresa | `/emitir` nunca retorna `INSUFFICIENT_STOCK`; saldo não é alterado em nenhuma etapa | Nenhuma ação — configuração é definida pelo Borurio, não pelo OMS |
| Timeout ou indisponibilidade da SEFAZ | `HTTP 503` `SEFAZ_TIMEOUT`/`SEFAZ_UNAVAILABLE`, `retryable: true` | Seguro reenviar sem alterar dados |

---

## 10. Observações de Homologação

| # | Observação                                                 | Impacto                                                 |
|---|------------------------------------------------------------|---------------------------------------------------------|
| 1 | `cStat=225` — causado por XML incompatível com o schema da NF-e (estrutura e algoritmos da assinatura XMLDSig); corrigido em 14-07-2026 após alinhamento ao schema oficial; resultado esperado agora é `cStat=100` para empresa com cadastro fiscal aceito pela SEFAZ na emissão de homologação | Se `cStat=225` ainda ocorrer, tratar como rejeição real e verificar `data.xMotivo`. Problemas cadastrais (ex.: IE cassada) têm `cStat`/`xMotivo` próprios — não são `225` |
| 2 | O campo `"environment"` no `/ping` reflete o perfil Spring ativo       | Usar apenas como indicador de diagnóstico               |
| 3 | Token expira em 1 hora                                     | Implementar renovação em fluxos longos                  |
| 4 | Lista paginada de pedidos não inclui itens                 | Sempre usar `GET /{id}` para obter itens                |
| 5 | CC-e e cancelamento exigem `nProt` disponível              | Consultar `/situacao` antes de cancelar após AGUARDANDO |
| 6 | Produto com `estado=0` é rejeitado no pedido               | Verificar `estado` antes de referenciar produto         |
| 7 | Estados `REJEITADO` e `ERRO` não são mais terminais (v1.9)  | Reemitir no mesmo pedido via `/emitir` após corrigir a causa — não criar pedido novo |
| 8  | Endpoint AN HOM pode retornar HTTP 403 em redes locais      | Limitação da SEFAZ federal — não afeta PRD nem fluxo OMS principal          |
| 9  | `POST /batch` sempre retorna HTTP 207 — mesmo com todos os itens com sucesso | Não tratar HTTP 207 como erro — inspecionar `resultados[].status` por item |
| 10 | Toda resposta inclui `X-Request-Id` no header de resposta   | Usar para correlacionar requisições OMS com logs do servidor para diagnóstico |
| 11 | Token OMS não muda ao adicionar novo CNPJ (cenários B, C, D) | O OMS não precisa atualizar o token ao expandir a cobertura de CNPJs de um cliente |
| 12 | `cnpjEmitente` omitido no pedido OMS usa o CNPJ âncora (primeiro autorizado) | Clientes OMS com único CNPJ podem omitir o campo sem impacto |
| 13 | Empresa auto-criada na autorização OMS não tem endereço (só vem do certificado) | Enviar campos `emit*` em `POST /api/app/pedidos` (seção 6.3) para completar o cadastro |
| 14 | Todo erro agora inclui o campo `retryable` (v1.9) | Usar esse campo para decidir reenvio automático — não fazer parse de `message` |

---

## 11. Changelog

| Versão | Data       | Alteração                                                                                      |
|--------|------------|-----------------------------------------------------------------------------------------------|
| 1.11   | 22-07-2026 | **Sem mudança de payload de API.** Confirmado e documentado: `modFrete` não é enviado pelo OMS nesta versão — o Borurio define internamente `modFrete=2` (Terceiros) para o fluxo OMS/marketplace atual, refletindo que a plataforma contrata o transporte (confirmado pelo integrador chinês). Revogação/rotação de token OMS passa de descrição operacional para mecanismo implementado e validado em HOM: autorização ativa checada a cada requisição (revogação com efeito imediato, sem depender de expiração do JWT); endpoints administrativos (`/api/admin/oms-authorizations/**`) confirmados como fora do contrato público do OMS. Nova autorização real obtida em HOM (`cStat=100`) com `modFrete=2` confirmado no XML transmitido. |
| 1.10   | 20-07-2026 | **PROPOSTA — aguardando confirmação do CC, não implantado em HOM.** Novo `PUT /api/integration/fiscal-numbering/{cnpj}` (seção 6.10): substitui o modelo de baseline único no onboarding por sincronização recorrente de série/numeração, disparada pela OMS a cada mudança do lado deles. Novos `errorCode`: `NUMERACAO_INFERIOR_A_ATUAL`, `SERIE_INVALIDA`, `NUMERACAO_INVALIDA`. **Mudança de comportamento em `POST /api/app/pedidos` (seção 6.3):** `serieNfe` deixou de ser resolvida na criação do pedido — vem sempre `null` na resposta até a primeira tentativa de emissão; resolvida junto com o número, atomicamente, só no início da emissão. Corrige gap em que um pedido criado antes de uma sincronização de série continuaria emitindo com a série antiga. `serieNfe` e `chaveNfe` enviados no payload de criação passam a ser sempre ignorados silenciosamente (endpoint deixou de aceitar bind direto da entidade). |
| 1.9.2  | 16-07-2026 | Correção de documentação (sem mudança de payload de API): novos `errorCode` formalizados no catálogo — `EMISSAO_EM_ANDAMENTO` (proteção contra emissão concorrente duplicada, HTTP 409, retryable), `IND_FINAL_PADRAO_INVALIDO` (configuração fiscal inválida de `indFinal` por empresa) e `DOCUMENTO_CNPJ_DIVERGENTE` (contexto fiscal multi-CNPJ em consulta/cancelamento/CC-e). Nota contratual adicionada sobre `indFinal`/`indIntermed` não fazerem parte do payload. Exemplos de payload passam a usar placeholders genéricos em vez de dados de empresas específicas. Formulação da causa do `cStat=225` precisada (falha de schema/assinatura, não reduzida a um único algoritmo) e desacoplada de problemas cadastrais. Status do documento passa de "Aprovado para integração" para "Vigente para integração em HOM", com ressalva sobre funcionalidades recentes ainda aguardando validação integrada pós-deploy. |
| 1.9.1  | 14-07-2026 | Correção de documentação (sem mudança de contrato de API): causa raiz do `cStat=225` identificada e corrigida no motor fiscal (algoritmo de assinatura RSA-SHA1/SHA-1, conforme schema XMLDSig oficial vigente da SEFAZ, em vez de RSA-SHA256). Resultado esperado em HOM/SP passa a ser `cStat=100` para empresa com cadastro fiscal aceito pela SEFAZ na emissão de homologação — confirmado em teste interno e em teste do integrador chinês via OMS. Seções 9.3 e 10 corrigidas — `cStat=225` deixou de ser descrito como comportamento esperado/limitação de ambiente. Detalhes completos em `MTF-001_motor-fiscal-nfe.md` seção 13. |
| 1.9    | 10-07-2026 | **Homologação com CC — 3 melhorias na integração OMS:** (1) `POST /emitir` aceita reemissão no mesmo pedido para `REJEITADO`/`ERRO` (não só `RASCUNHO`) — cada tentativa gera `chaveNfe` nova; (2) `POST /api/app/pedidos` aceita endereço do emitente opcional (`emitLogradouro`/`emitNumero`/`emitBairro`/`emitCodigoMunicipio`/`emitMunicipio`/`emitCep`) para completar automaticamente o cadastro da empresa quando incompleto (empresa auto-criada via certificado não tem endereço); (3) todo erro passa a incluir `retryable` (booleano) e novos `errorCode`: `EMITTER_ADDRESS_INCOMPLETE`, `SEFAZ_REJECTED`, `SEFAZ_TIMEOUT`, `SEFAZ_UNAVAILABLE`, `XML_SCHEMA_INVALID`. `POST /emitir` não retorna mais HTTP 200 quando a SEFAZ rejeita a NF-e — retorna HTTP 422 `SEFAZ_REJECTED` com `data.cStat`/`data.xMotivo`. Seção 9.3 corrigida: `cStat=225` não é mais descrito como "limitação do HOM apenas" — é rejeição real que pode indicar dado incorreto (achado em homologação real com cliente OMS: cadastro do emitente sem endereço). |
| 1.8    | 10-07-2026 | Controle de estoque passa a ser opcional por empresa (`controleEstoqueAtivo`, configuração interna, default ativo). Empresas com a flag desativada nunca recebem `INSUFFICIENT_STOCK` em `/emitir` e não têm saldo alterado em nenhuma etapa (reserva, baixa, estorno ou cancelamento). Nenhuma mudança de comportamento para empresas existentes. |
| 1.7    | 22-06-2026 | **OMS Multi-CNPJ (V028):** um cliente OMS (`codigoEmpresaOms`) pode autorizar múltiplos CNPJs com um único token. Empresa auto-criada a partir do Subject X.509 (sem pré-cadastro ADMIN). Campo `cnpjEmitente` adicionado ao pedido para seleção do certificado na emissão. Comportamento por cenário (A/B/C/D) documentado — token nunca muda nos cenários B, C, D. Novos `errorCode`: `COMPANY_INACTIVE`, `CNPJ_NOT_AUTHORIZED`, `CERT_NOT_FOUND_FOR_CNPJ`. Removido: `COMPANY_NOT_FOUND` (empresa agora auto-criada). Smoke test OMS multi-CNPJ adicionado (seção 9.1b). |
| 1.6.1  | 18-06-2026 | Correção de documentação: resposta de `POST /api/integration/fiscal-authorizations` **não usa** o envelope `Result<>` — DTO retornado diretamente na raiz (campos `token`, `empresaId`, `cnpj`, `razaoSocial`, `tokenExpiraEm`). Seções 3.3 e 8.1 corrigidas. |
| 1.6    | 17-06-2026 | Sessão OMS por Certificado A1 — `POST /api/integration/fiscal-authorizations` com header `X-Api-Key`; sem login de usuário para o OMS; token técnico por empresa; reautorização (troca de certificado) e revogação documentadas. Novos `errorCode`: `INVALID_API_KEY`, `COMPANY_NOT_FOUND`, `INVALID_CERTIFICATE`, `CNPJ_CERTIFICATE_MISMATCH`, `CERTIFICATE_EXPIRED`, `AUTHORIZATION_REVOKED`. |
| 1.5    | 10-06-2026 | `POST /api/app/pedidos` — campos fiscais (`codigoProduto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn`) passam a ser **obrigatórios** em cada item e devem ser enviados pelo OMS. O sistema não copia mais dados fiscais do produto cadastrado. Campo ausente retorna HTTP 400. |
| 1.4    | 01-06-2026 | Header de rastreabilidade `X-Request-Id`; idempotência via `externalOrderId`; batch upsert (`POST /api/app/produtos/batch`); endpoints de Manifestação do Destinatário. |
| 1.3    | 27-05-2026 | `cfop` opcional no produto (`POST /api/app/produtos`); rotação de senha de certificado (M3); esclarecimentos sobre geração de DANFE. |
| 1.2    | 18-05-2026 | Controle de estoque com reserva atômica; Fase 12-B DANFE; isolamento multi-tenant confirmado em HOM. |
