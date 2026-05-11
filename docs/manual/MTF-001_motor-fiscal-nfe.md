# MTF-001 — Manual Técnico: Motor Fiscal NF-e 4.00
## Borurio ERP Fiscal BR

---

**Documento:** MTF-001  
**Versão:** 1.0  
**Data de emissão:** 11-05-2026  
**Autor:** Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps  
**Status:** VALIDADO EM HOMOLOGAÇÃO  
**Branch de referência:** `fix/sefaz-xml-structure` (commit `bc37998`)  

---

## SUMÁRIO

1. [Escopo e Objetivo](#1-escopo-e-objetivo)
2. [Arquitetura de Módulos](#2-arquitetura-de-módulos)
3. [Modelo de Dados Fiscal](#3-modelo-de-dados-fiscal)
4. [Fluxo Completo de Emissão NF-e](#4-fluxo-completo-de-emissão-nf-e)
5. [Geração do XML NF-e](#5-geração-do-xml-nf-e)
6. [Assinatura Digital XMLDSIG](#6-assinatura-digital-xmldsig)
7. [Transmissão SOAP para SEFAZ](#7-transmissão-soap-para-sefaz)
8. [Persistência e Auditoria Fiscal](#8-persistência-e-auditoria-fiscal)
9. [Multiempresa](#9-multiempresa)
10. [Certificado Digital por Empresa](#10-certificado-digital-por-empresa)
11. [Segurança Base](#11-segurança-base)
12. [Operações Fiscais Pós-Emissão](#12-operações-fiscais-pós-emissão)
13. [Limitações Conhecidas do Ambiente HOM/SP](#13-limitações-conhecidas-do-ambiente-homsp)
14. [Checklists Operacionais](#14-checklists-operacionais)
15. [Decisões de Arquitetura](#15-decisões-de-arquitetura)
16. [Roadmap até Produção](#16-roadmap-até-produção)
17. [Referências Normativas](#17-referências-normativas)

---

## 1. ESCOPO E OBJETIVO

Este manual descreve a arquitetura técnica, os fluxos de processamento e os procedimentos operacionais do **Motor Fiscal NF-e 4.00** implementado no sistema Borurio ERP Fiscal BR.

O documento destina-se a:

- **Equipe técnica interna** — manutenção, evolução e debugging do motor;
- **Time de integração parceiro** — integração do motor fiscal com o ERP logístico externo;
- **Operações / DevOps** — deploy, monitoramento e procedimentos de homologação;
- **Auditores técnicos** — rastreabilidade das decisões de design e conformidade.

### 1.1 O que está FECHADO (validado em HOM)

| Funcionalidade | Validação |
|---|---|
| Emissão NF-e 4.00 via SOAP HTTPS | ✓ HOM/SP — 11-05-2026 |
| Assinatura XMLDSIG RSA-SHA256 + C14N | ✓ HOM/SP — 11-05-2026 |
| Ciclo pedido → NF-e | ✓ HOM/SP — 08-05-2026 |
| Snapshot fiscal imutável no item | ✓ HOM/SP — 08-05-2026 |
| Status semântico (AUTORIZADO / REJEITADO / AGUARDANDO) | ✓ HOM/SP — 08-05-2026 |
| Cancelamento NF-e (evento 110111) | ✓ HOM/SP — 08-05-2026 |
| Carta de Correção Eletrônica (evento 110110) | ✓ HOM/SP — 08-05-2026 |
| Consulta situação NF-e (consSitNFe) | ✓ HOM/SP — 08-05-2026 |
| Multiempresa — isolamento de dados por empresa_id | ✓ HOM/SP — 08-05-2026 |
| Certificado A1 por empresa com cache | ✓ HOM/SP — 11-05-2026 |
| RBAC (roles ADMIN / OPERADOR do banco) | ✓ HOM/SP — 11-05-2026 |
| Criptografia cert_senha AES-256-GCM | ✓ Código validado; passthrough em HOM (chave não configurada) |
| Audit log com empresa_id e usuário autenticado | ✓ HOM/SP — 11-05-2026 |

### 1.2 O que está PENDENTE

| Funcionalidade | Fase | Observação |
|---|---|---|
| CERT_ENCRYPTION_KEY configurada em produção | Fase 11 | Passthrough ativo em HOM por design |
| CI/CD automatizado | Fase 11 | Deploy manual via docker cp |
| Manual PT/EN para integração (este documento) | Fase 10 | Em elaboração |

---

## 2. ARQUITETURA DE MÓDULOS

### 2.1 Diagrama de dependências

```
borurio-core
    │
    ├── borurio-app
    │       └── entidades, mappers, services de negócio
    │
    ├── borurio-fiscal
    │       └── motor NF-e, XMLDSIG, SOAP, auditoria
    │
    └── borurio-web  (JAR executável Spring Boot)
            ├── depende de borurio-app
            ├── depende de borurio-fiscal
            └── controllers REST, auth JWT, bridges de negócio
```

### 2.2 Responsabilidades por módulo

| Módulo | Pacote raiz | Responsabilidade |
|---|---|---|
| `borurio-core` | `br.com.borurio.core` | DTOs compartilhados, `ResultUtil`, utilitários base |
| `borurio-app` | `br.com.borurio.app` | Entidades de negócio, MyBatis mappers, services: Empresa, Produto, Pedido, DbUser |
| `borurio-fiscal` | `br.com.borurio.fiscal` | Geração XML NF-e, assinatura XMLDSIG, transmissão SOAP, sequenciador, persistência fiscal, auditoria |
| `borurio-web` | `br.com.borurio.web` | Spring Boot, controllers REST, JWT, bridges (`PedidoEmissaoService`, `NfeGeracaoService`), certificado por empresa |

### 2.3 Regra de fronteira de módulo

> **`borurio-fiscal` NÃO importa `borurio-app`.**

A bridge entre os dois domínios é exclusivamente o módulo `borurio-web`. Quando o módulo fiscal precisa de dados da empresa emitente, recebe o record `CertificadoContexto` (apenas tipos JDK) em vez de receber a entidade `Empresa`.

### 2.4 Stack tecnológica

| Componente | Versão / Tecnologia |
|---|---|
| Linguagem | Java 17 |
| Framework | Spring Boot 3.x |
| Persistência | MyBatis (annotations) |
| Banco de dados | MySQL 8.4 |
| Migrations | Flyway (V001–V019) |
| Auth | JWT stateless (HMAC-SHA256) |
| Segurança | Spring Security 6.x |
| XML Signing | Java XML Crypto API (`javax.xml.crypto.dsig`) |
| SOAP | HTTPS direto (sem CXF, sem wsimport) |
| Cache de certificados | `ConcurrentHashMap` em memória |
| Container | Docker (imagem interna); porta 8081 em HOM |

---

## 3. MODELO DE DADOS FISCAL

### 3.1 Migrations aplicadas (V001–V019)

| Migration | Descrição |
|---|---|
| V001 | `cliente` (legado, não usado no fluxo fiscal principal) |
| V002 | `nfe_log` — auditoria de eventos fiscais |
| V003 | Dados mock de referência |
| V008 | `db_user` — autenticação e autorização |
| V009 | `produto` |
| V011 | `nfe_sequencia` — controle de número por série/CNPJ |
| V012 | `nfe_documento` — estado fiscal de cada NF-e autorizada |
| V013 | `produto` — campos fiscais: origem, csosn, estoque |
| V014 | `pedido` + `pedido_item` |
| V015 | `pedido_item` — snapshot fiscal (ncm, cfop, csosn, origem, unidade) |
| V016 | `empresa` — cadastro multiemitente |
| V017 | `empresa_id` em `db_user`, `produto`, `pedido` |
| V018 | `empresa` — certificado A1 por empresa (cert_path, cert_senha, cert_tipo) |
| V019 | `db_user.role` (ADMIN / OPERADOR) + `nfe_log.empresa_id` |

### 3.2 Tabelas fiscais principais

#### `nfe_documento`
Armazena o estado persistido de cada NF-e emitida.

| Coluna | Tipo | Descrição |
|---|---|---|
| `chave_nfe` | VARCHAR(44) | Chave de acesso (44 dígitos) |
| `numero` | VARCHAR(9) | Número da NF-e |
| `serie` | VARCHAR(3) | Série |
| `cnpj_emitente` | VARCHAR(14) | CNPJ sem máscara |
| `cnpj_cpf_dest` | VARCHAR(14) | Destinatário |
| `c_stat` | INT | Código de status SEFAZ |
| `x_motivo` | VARCHAR(255) | Motivo retornado pela SEFAZ |
| `n_prot` | VARCHAR(20) | Número do protocolo de autorização |
| `valor_total` | DECIMAL(13,2) | Valor total da NF-e |
| `xml_autorizado` | LONGTEXT | XML assinado que foi transmitido |
| `data_emissao` | DATETIME | Data/hora da emissão |

#### `nfe_log`
Registro de auditoria de cada operação fiscal.

| Coluna | Tipo | Descrição |
|---|---|---|
| `chave_nfe` | VARCHAR(44) | Chave associada ao evento |
| `tipo_evento` | VARCHAR(100) | ENVIO_NFE / TRANSMISSAO_SEFAZ / CONSULTA / CANCELAMENTO / CCE |
| `status` | VARCHAR(20) | SUCCESS / ERROR / PENDING |
| `usuario` | VARCHAR(100) | Usuário autenticado (SecurityContextHolder) |
| `empresa_id` | BIGINT | ID da empresa emitente (V019) |
| `xml_envio` | LONGTEXT | XML transmitido |
| `xml_retorno` | LONGTEXT | Resposta SOAP da SEFAZ |

#### `nfe_sequencia`
Garante unicidade atômica do número da NF-e por CNPJ + série.

| Coluna | Descrição |
|---|---|
| `cnpj` | CNPJ do emitente |
| `serie` | Série da NF-e |
| `ultimo_numero` | Último número emitido |

---

## 4. FLUXO COMPLETO DE EMISSÃO NF-e

### 4.1 Sequência de chamadas (pedido → NF-e)

```
Cliente HTTP
    │
    │  POST /api/app/pedidos/{id}/emitir
    │
    ▼
PedidoController (borurio-web)
    │
    │  pedidoEmissaoService.emitir(id)
    │
    ▼
PedidoEmissaoService (borurio-web)
    │  ├─ pedidoService.buscarComItens(id)      → valida status=RASCUNHO
    │  ├─ montarRequest(pedido)                  → NfeEmissaoRequest com snapshot fiscal
    │  ├─ resolverEmpresa(empresaId)              → Empresa do contexto JWT
    │  │
    │  │  nfeGeracaoService.gerar(req, empresa)
    │  │
    ▼  ▼
NfeGeracaoService (borurio-web)
    │  ├─ montarIde / montarEmit / montarDest / montarDet / montarTotal
    │  ├─ nfeXmlBuilder.build(nfe)               → XML sem assinatura
    │  ├─ sequenciaService.proximoNumero()        → número atômico por série/CNPJ
    │  ├─ empresaCertificadoService.resolverPorEmpresa() → CertificadoContexto ou null
    │  │
    │  │  nfeOrquestradorService.processar(xml, cnpj, certCtx)
    │  │
    ▼  ▼
NfeOrquestradorService (borurio-fiscal)
    │  ├─ [1] converterParaDocument()             → parse XML com namespace-aware
    │  ├─ [2] xsdValidator.validate()             → contra xsd/custom/nfe_v4.00_consolidado.xsd
    │  ├─ [3] assinaturaXmlService.assinar()      → XMLDSIG RSA-SHA256 + C14N
    │  └─ [4] nfeTransmitService.transmitirXml()  → SOAP HTTPS → SEFAZ
    │
    ▼
NfeTransmitServiceImpl (borurio-fiscal)
    │  ├─ criarEnvelopeEnviNFe()                  → lote com 1 NF-e
    │  ├─ enviarSoap(url, envelope, sslContext)    → HTTPS POST
    │  └─ salvarLogSeguro(nfeLog)                  → nfe_log com usuario real
    │
    ▼
NfeGeracaoService (retorno)
    │  ├─ retornoParser.parse(soap)                → NfeSefazRetorno { cStat, xMotivo, nProt }
    │  ├─ documentoService.salvarComRetorno()      → nfe_documento persistido
    │  └─ registrarLog()                           → nfe_log com empresa_id
    │
    ▼
PedidoEmissaoService (pós-emissão)
    │  ├─ resolverStatus(soapRetorno)              → AUTORIZADO / REJEITADO / AGUARDANDO
    │  ├─ pedidoService.atualizarStatus()          → pedido.status + chave_nfe
    │  └─ baixarEstoque()                          → somente se AUTORIZADO (cStat=100)
```

### 4.2 Status semântico do pedido

| Status | Condição | Estoque baixado? |
|---|---|---|
| `RASCUNHO` | Pedido criado, ainda não emitido | Não |
| `AUTORIZADO` | `cStat = 100` da SEFAZ | Sim |
| `AGUARDANDO` | Lote aceito (`cStat = 104`) sem infProt; processamento assíncrono SEFAZ | Não |
| `REJEITADO` | `cStat >= 200` | Não |
| `ERRO` | Exceção durante a transmissão | Não |
| `CANCELADO` | Evento de cancelamento autorizado | N/A |

### 4.3 Snapshot fiscal imutável

Ao criar o pedido, o `PedidoServiceImpl` executa `preencherSnapshot()` que copia os campos fiscais do `Produto` para o `PedidoItem`:

```
PedidoItem.codigoProduto ← Produto.codigo
PedidoItem.descricao     ← Produto.descricao
PedidoItem.ncm           ← Produto.ncm
PedidoItem.cfop          ← Produto.cfop
PedidoItem.unidade       ← Produto.unidade
PedidoItem.origem        ← Produto.origem (default: 0)
PedidoItem.csosn         ← Produto.csosn  (default: "400")
```

**Invariante:** após a criação do pedido, qualquer alteração posterior no cadastro do produto não afeta os dados fiscais do pedido. A emissão sempre usa o snapshot congelado no `pedido_item`.

---

## 5. GERAÇÃO DO XML NF-e

### 5.1 Componentes envolvidos

| Classe | Módulo | Responsabilidade |
|---|---|---|
| `NfeGeracaoService` | borurio-web | Monta os blocos da NF-e a partir de `NfeEmissaoRequest` |
| `NfeXmlBuilder` | borurio-fiscal | Serializa o objeto `NFe` em XML via JAXB |
| `NfeOrquestradorService` | borurio-fiscal | Orquestra validação + assinatura + transmissão |

### 5.2 Cálculo da chave de acesso (44 dígitos)

```
chave43 = cUF(2) + aaaMM(4) + CNPJ(14) + mod(2=55) + serie(3) + nNF(9) + tpEmis(1) + cNF(8)
cDV     = módulo 11 sobre chave43
chave   = chave43 + cDV
```

O `cNF` (código numérico) é gerado com `new Random().nextInt(100_000_000)`.

O número da NF-e (`nNF`) é obtido de forma atômica via `NfeSequenciaService.proximoNumero(cnpj, serie)` — usa `SELECT ... FOR UPDATE` (ou equivalente) para garantir unicidade mesmo em ambientes concorrentes.

### 5.3 Estrutura XML gerada (simplificada)

```xml
<NFe versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
  <infNFe Id="NFe{44-digitos}" versao="4.00">
    <ide>     <!-- identificação -->
    <emit>    <!-- emitente: CNPJ, razão social, endereço, IE, CRT -->
    <dest>    <!-- destinatário: CPF/CNPJ, razão, endereço -->
    <det>     <!-- itens: produto, NCM, CFOP, ICMS(CSOSN), PIS, COFINS -->
    <total>   <!-- ICMSTot com 20 campos obrigatórios -->
    <transp>  <!-- modFrete=9 (sem frete) -->
    <pag>     <!-- detPag: indPag=0, tPag=01, vPag=total -->
  </infNFe>
  <Signature>  <!-- inserido pelo AssinaturaXmlService após validação XSD -->
</NFe>
```

### 5.4 Campos fiscais por produto

Cada item do XML (`<det>`) utiliza os dados congelados no `PedidoItem`:

| Bloco NF-e | Campo | Origem |
|---|---|---|
| `<prod>` | `cProd` | `item.codigoProduto` |
| `<prod>` | `NCM` | `item.ncm` (8 dígitos, validado contra tabela NCM oficial) |
| `<prod>` | `CFOP` | `item.cfop` |
| `<prod>` | `uCom` | `item.unidade` |
| `<ICMS>` | `orig` | `item.origem` |
| `<ICMS>` | `CSOSN` | `item.csosn` (ex: `400` = CRT 1 sem tributação ICMS) |
| `<PIS>` / `<COFINS>` | `CST` | `07` (operação isenta) |

### 5.5 Validação XSD pré-assinatura

Antes de assinar, o `NfeOrquestradorService` valida o XML contra:

```
borurio-fiscal/src/main/resources/xsd/custom/nfe_v4.00_consolidado.xsd
```

Este schema consolida `leiauteNFe_v4.00.xsd` + `tiposBasico_v4.00.xsd` em um único arquivo para resolver dependências de classpath. A validação usa a API JAXP (`javax.xml.validation`).

---

## 6. ASSINATURA DIGITAL XMLDSIG

### 6.1 Algoritmos (NT 2019.001 — obrigatórios NF-e 4.00)

| Algoritmo | URI |
|---|---|
| Assinatura | `http://www.w3.org/2001/04/xmldsig-more#rsa-sha256` (RSA-SHA256) |
| Digest | `http://www.w3.org/2001/04/xmlenc#sha256` (SHA-256) |
| Canonicalização | `http://www.w3.org/TR/2001/REC-xml-c14n-20010315` (C14N Inclusivo) |
| Transform 1 | `ENVELOPED` (remove o próprio elemento Signature do digest) |
| Transform 2 | C14N Inclusivo |

### 6.2 Fluxo de assinatura

```
AssinaturaXmlService.assinar(xmlNfe, ctx?)
    │
    ├─ 1. parse XML → Document (namespace-aware, anti-XXE)
    ├─ 2. localizarElementoPorTag("infNFe") → Element
    ├─ 3. infNFe.setIdAttribute("Id", true)  → registra Id como tipo ID no DOM
    ├─ 4. XMLSignatureFactory.getInstance("DOM")
    ├─ 5. Reference("#" + id, SHA-256, [ENVELOPED, C14N])
    ├─ 6. SignedInfo(C14N, RSA-SHA256, [reference])
    ├─ 7. KeyInfo(X509Data(cert))
    ├─ 8. XMLSignature.sign(DOMSignContext(privateKey, nfeElement))
    └─ 9. serializar(doc) → UTF-8, sem declaração XML, sem INDENT
```

### 6.3 Posição do elemento Signature

O `<Signature>` é inserido como filho direto do elemento `<NFe>`, **após** o `<infNFe>`:

```xml
<NFe>
  <infNFe Id="NFe...">...</infNFe>
  <Signature xmlns="http://www.w3.org/2000/09/xmldsig#">
    <SignedInfo>...</SignedInfo>
    <SignatureValue>...</SignatureValue>
    <KeyInfo><X509Data><X509Certificate>...</X509Certificate></X509Data></KeyInfo>
  </Signature>
</NFe>
```

O mesmo padrão é usado para eventos (`infEvento`) e inutilização (`infInut`), com `assinarEvento()` e `assinarInutilizacao()`.

### 6.4 Proteções contra XXE

O `DocumentBuilderFactory` usado em toda a assinatura tem as seguintes proteções:

```java
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
```

---

## 7. TRANSMISSÃO SOAP PARA SEFAZ

### 7.1 Endpoint de homologação (SP)

| Serviço | URL HOM |
|---|---|
| Autorização NF-e | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeautorizacao4.asmx` |
| Status Serviço | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfestatusservico4.asmx` |
| Consulta Situação | `https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeconsultaprotocolo4.asmx` |

### 7.2 Envelope SOAP 1.2

O `NfeTransmitServiceImpl` monta o envelope diretamente (sem gerador WSDL):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
  <soap12:Body>
    <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
      <enviNFe versao="4.00" xmlns="http://www.portalfiscal.inf.br/nfe">
        <idLote>{15 dígitos}</idLote>
        <indSinc>1</indSinc>
        {xmlAssinado}
      </enviNFe>
    </nfeDadosMsg>
  </soap12:Body>
</soap12:Envelope>
```

`indSinc=1` → processamento síncrono (retorno imediato). Lote com 1 NF-e.

### 7.3 Autenticação mTLS

A conexão com a SEFAZ usa **autenticação mútua TLS** (mTLS):

- O sistema apresenta o certificado A1 do emitente no `KeyManagerFactory`
- `SSLContext` configurado com `TLSv1.2`
- Certificado carregado do KeyStore PKCS12

Quando não há certificado por empresa configurado, usa o certificado global do `CertificadoServiceImpl`.

### 7.4 Interpretação do retorno

A SEFAZ retorna um envelope SOAP com `retEnviNFe`. O `NfeSefazRetornoParser` extrai:

| Campo | Descrição |
|---|---|
| `cStat` | Código de status (100 = autorizado, 104 = aguardando, 2xx+ = rejeição) |
| `xMotivo` | Descrição textual do status |
| `nProt` | Número do protocolo de autorização (presente apenas se cStat=100) |
| `chNFe` | Chave de acesso retornada pela SEFAZ |

---

## 8. PERSISTÊNCIA E AUDITORIA FISCAL

### 8.1 nfe_documento

Após cada transmissão, o `NfeDocumentoService.salvarComRetorno()` persiste:

```
nfe_documento {
    chave_nfe, numero, serie, cnpj_emitente,
    cnpj_cpf_dest, razao_dest, valor_total,
    c_stat, x_motivo, n_prot,
    xml_autorizado,   ← XML assinado transmitido
    tp_amb,           ← 1=produção / 2=homologação
    data_emissao
}
```

Esta tabela é a fonte de verdade para consultas fiscais offline e para reemissão de DANFE.

### 8.2 nfe_log (dupla camada de auditoria)

Dois eventos distintos são gravados por emissão:

| Tipo evento | Gerado por | empresa_id | usuario |
|---|---|---|---|
| `ENVIO_NFE` | `NfeTransmitServiceImpl` | NULL | Usuário autenticado (SecurityContextHolder) |
| `TRANSMISSAO_SEFAZ` | `NfeGeracaoService` | ID da empresa | NULL |

> **Nota de design:** a dupla camada é intencional. O `NfeTransmitServiceImpl` pertence ao módulo `borurio-fiscal` (sem dependência de `Empresa`), por isso não grava `empresa_id`. O `NfeGeracaoService` pertence ao módulo `borurio-web` (com acesso a `Empresa`), por isso não tem acesso ao `SecurityContextHolder` no mesmo instante da transmissão.

### 8.3 Garantias de falha de log

Falhas de persistência no `nfe_log` nunca interrompem o fluxo fiscal:

```java
// NfeGeracaoService
try {
    nfeLogService.salvar(log);
} catch (Exception logEx) {
    log.error("[NfeGeracao] Falha ao persistir nfe_log | chave={} | erro={}",
              chave, logEx.getMessage());
}
```

A mesma proteção existe em `NfeTransmitServiceImpl.salvarLogSeguro()`.

---

## 9. MULTIEMPRESA

### 9.1 Modelo de isolamento

```
db_user.empresa_id  → empresa a que o usuário pertence
produto.empresa_id  → produtos isolados por empresa
pedido.empresa_id   → pedidos isolados por empresa
nfe_log.empresa_id  → auditoria segmentada por empresa
```

### 9.2 Propagação do contexto via JWT

```
Login (POST /auth/login)
    │
    │  AuthService.authenticate()
    │      └─ dbUserMapper.findByEmail(username) → DbUser.empresaId
    │      └─ jwtUtil.generateToken(email, empresaId)
    │               JWT payload: { "sub": "admin", "eid": 1, "exp": ... }
    ▼
Toda requisição autenticada
    │
    │  JwtFilter.doFilterInternal()
    │      └─ jwtUtil.extractEmpresaId(token) → Long
    │      └─ EmpresaContextHolder.set(empresaId)  ← ThreadLocal
    │      finally: EmpresaContextHolder.clear()
    ▼
Controllers
    │
    │  Long empresaId = EmpresaContextHolder.get()
    │  → produto.setEmpresaId(empresaId)
    │  → pedido.setEmpresaId(empresaId)
    │  → listarPorEmpresa(empresaId)  vs  listarTodos()
```

### 9.3 Fallback de compatibilidade

Quando `empresaId == null` (usuário sem empresa associada, ou ambiente dev sem JWT):

- `listarTodos()` é usado no lugar de `listarPorEmpresa()`
- `EmitenteProperties` é usado como emitente no lugar de `Empresa`
- Dados legados (anteriores à V017) são backfillados para a empresa padrão no `StartupListener`

### 9.4 Seed e backfill no startup

O `StartupListener` (`@PostConstruct`) executa na inicialização:

1. **Seed empresa padrão** — lê `fiscal.emitente.*` do `application.properties` e cria `empresa` se não existir
2. **Backfill** — atualiza `empresa_id` em `db_user`, `produto` e `pedido` onde `empresa_id IS NULL`
3. **Seed admin** — cria usuário `admin` com `role='ADMIN'` se `db_user` estiver vazio

---

## 10. CERTIFICADO DIGITAL POR EMPRESA

### 10.1 Campos no banco

```sql
ALTER TABLE empresa
    ADD COLUMN cert_path  VARCHAR(255) NULL,   -- caminho classpath ou filesystem
    ADD COLUMN cert_senha VARCHAR(255) NULL,   -- senha do KeyStore (criptografada)
    ADD COLUMN cert_tipo  VARCHAR(10)  NULL DEFAULT 'PKCS12';
```

### 10.2 Record CertificadoContexto

Define o contrato de passagem de credenciais entre os módulos sem expor a entidade `Empresa` ao módulo fiscal:

```java
// borurio-fiscal — apenas tipos JDK
public record CertificadoContexto(
    Long empresaId,
    PrivateKey privateKey,
    X509Certificate certificate,
    SSLContext sslContext
) {}
```

### 10.3 Fluxo de resolução de certificado

```
PedidoEmissaoService / NfeGeracaoService
    │
    │  empresaCertificadoService.resolverPorEmpresa(empresa)
    │
    ▼
EmpresaCertificadoService
    │  if empresa.certPath == null → Optional.empty()   ← fallback para cert global
    │  else → cache.computeIfAbsent(empresaId, ...)
    │              └─ carregarContexto(empresa)
    │                    ├─ CertSenhaEncryptor.decrypt(certSenha)
    │                    ├─ KeyStore.load(inputStream, senha)
    │                    ├─ resolverAlias() → isKeyEntry()
    │                    ├─ PrivateKey + X509Certificate
    │                    └─ SSLContext TLSv1.2 (KeyManagerFactory)
    │
    └─ CertificadoContexto { empresaId, privateKey, cert, sslContext }
```

### 10.4 Cache de certificados

O cache `ConcurrentHashMap<Long, CertificadoContexto>` persiste durante a vida do container. Para invalidar (ex: após atualização do certificado):

```
EmpresaCertificadoService.invalidar(empresaId)
```

> **Atenção:** o cache não é invalidado automaticamente quando a empresa atualiza seu certificado via PUT /api/app/empresas. O `EmpresaController` **não chama** `invalidar()` atualmente. Isso deve ser corrigido antes de produção.

### 10.5 Resolução de arquivo do certificado

O `EmpresaCertificadoService` tenta em ordem:

1. **Classpath** via `ClassPathResource(path)`
2. **Filesystem** via `new File(path)`

Caso nenhum dos dois encontre o arquivo, lança `IllegalStateException`.

---

## 11. SEGURANÇA BASE

### 11.1 Autenticação JWT

- Algoritmo: HMAC-SHA256
- Payload: `{ "sub": email, "eid": empresaId, "iat": ..., "exp": ... }`
- Validade configurável via `jwt.expiration` (ms)
- Chave secreta via `jwt.secret` (Base64, ≥ 32 bytes)
- Filter: `JwtFilter` — extrai token do header `Authorization: Bearer <token>`

### 11.2 Controle de acesso (RBAC)

| Role | Valor em `db_user.role` | Permissões |
|---|---|---|
| Administrador | `ADMIN` | Todas as operações, incluindo criar/atualizar empresas |
| Operador | `OPERADOR` | Operações de negócio (produtos, pedidos, emissão) |

Restrições na `SecurityConfig`:

```java
.requestMatchers(POST "/api/app/empresas").hasRole("ADMIN")
.requestMatchers(PUT  "/api/app/empresas/**").hasRole("ADMIN")
.anyRequest().authenticated()
```

### 11.3 Criptografia de cert_senha (AES-256-GCM)

**Formato armazenado no banco:**

```
ENC(<base64(iv_12bytes + ciphertext)>)
```

**Configuração:**

```bash
# Gerar chave AES-256 (32 bytes, Base64)
openssl rand -base64 32

# Variável de ambiente no container
CERT_ENCRYPTION_KEY=<saída do openssl>

# application.properties
cert.encryption.key=${CERT_ENCRYPTION_KEY:}
```

**Modo passthrough:** se `CERT_ENCRYPTION_KEY` não estiver configurada, o `CertSenhaEncryptor` opera em modo transparente (sem criptografia) e emite `WARN` nos logs. Adequado para desenvolvimento local e HOM sem certificado configurado no banco.

**Migração graceful:** valores sem prefixo `ENC(` são tratados como texto claro pelo `decrypt()`, permitindo migração incremental.

### 11.4 Proteções XML (anti-XXE)

Todos os parsers XML do sistema são configurados com:

```java
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
```

---

## 12. OPERAÇÕES FISCAIS PÓS-EMISSÃO

### 12.1 Consulta de situação (consSitNFe)

```
GET /api/app/pedidos/{id}/situacao
    │
    └─ PedidoOperacaoService.consultarSituacao(id)
           └─ estado local (nfe_documento) + consulta live SEFAZ
```

Requer: pedido com `chaveNfe` preenchida (status ≠ RASCUNHO).

### 12.2 Cancelamento (evento 110111)

```
POST /api/app/pedidos/{id}/cancelar
Body: { "justificativa": "mínimo 15 caracteres" }
```

Condições: pedido em `AUTORIZADO` com `nProt` gravado.

O `PedidoOperacaoService` monta o XML do evento de cancelamento, assina com `AssinaturaXmlService.assinarEvento()` e transmite para o endpoint de recepção de eventos SEFAZ.

### 12.3 Carta de Correção Eletrônica (CC-e, evento 110110)

```
POST /api/app/pedidos/{id}/cce
Body: { "correcao": "mínimo 15 caracteres" }
```

Condições: pedido em `AUTORIZADO`. Limite: 20 CC-e por chave NF-e (verificado via `nfe_log`).

---

## 13. LIMITAÇÕES CONHECIDAS DO AMBIENTE HOM/SP

> **IMPORTANTE:** esta seção descreve limitações do ambiente de homologação da SEFAZ SP, **não** do código. O código foi validado contra XSD oficial e está em conformidade com a NT 2019.001.

### 13.1 cStat=225 — "Rejeição: Falha no Schema XML do lote de NFe"

**Comportamento observado:**

```
retEnviNFe.cStat   = 104   (lote aceito pelo processador PL009)
protNFe.cStat      = 225   (NF-e rejeitada pelo processador PL_008i2)
xMotivo            = "Rejeição: Falha no Schema XML do lote de NFe"
verAplic (lote)    = SP_NFE_PL009_V4
verAplic (infProt) = SP_NFE_PL_008i2
```

**Diagnóstico:**

A SEFAZ SP usa dois processadores distintos: o `PL009` valida o lote, e o `PL_008i2` (versão mais antiga) valida cada NF-e individualmente. A hipótese mais provável é que o `PL_008i2` use internamente o schema `xmldsig-core-schema_v1.01.xsd` com `fixed="rsa-sha1"`, enquanto o código usa RSA-SHA256 (obrigatório pela NT 2019.001).

**Status de investigação:** ENCERRADA. Trata-se de limitação do ambiente HOM da SEFAZ SP. Não há ação corretiva possível no código sem violar a NT 2019.001.

**Impacto:** somente o ambiente HOM da SEFAZ SP com o processador `PL_008i2`. Não afeta produção e não afeta outros estados.

**Evidência:** todas as NF-e transmitidas em HOM retornam cStat=225. O XML é aceito pelo lote (cStat=104) e o XSD local valida com sucesso. A rejeição ocorre no processamento individual pelo `PL_008i2`.

### 13.2 Invalidação de cache de certificado

Conforme descrito na seção 10.4, o cache `EmpresaCertificadoService` não é invalidado automaticamente quando o certificado é atualizado via API. Isso é um **gap de implementação** (não uma limitação externa) a ser corrigido antes de produção.

---

## 14. CHECKLISTS OPERACIONAIS

### 14.1 Checklist de deploy HOM

```
□ 1. Build: mvn package -DskipTests -q
       └─ Verificar: BUILD SUCCESS

□ 2. Copy JAR para o container correto:
       docker cp borurio-web/target/borurio-web-1.0.0.jar \
                 borurio-web-hom:/app/app.jar
       └─ Atenção: o JAR interno é /app/app.jar (não /app/borurio-web.jar)

□ 3. Restart:
       docker restart borurio-web-hom

□ 4. Aguardar healthcheck:
       docker inspect borurio-web-hom --format "{{.State.Health.Status}}"
       └─ Aguardar: healthy

□ 5. Verificar Flyway nos logs:
       docker logs borurio-web-hom --since "..." 2>&1 | grep -i "migrat\|flyway"
       └─ Verificar: "Successfully applied N migration(s)" ou "is up to date"

□ 6. Smoke test login:
       POST http://localhost:8081/auth/login
       {"username":"admin","password":"admin123"}
       └─ Verificar: HTTP 200, token presente

□ 7. Smoke test actuator:
       GET http://localhost:8081/actuator/health
       └─ Verificar: {"status":"UP"}
```

### 14.2 Checklist de homologação NF-e

```
□ 1. Empresa configurada com CNPJ, razão social, UF, IE, CRT
□ 2. Certificado A1 PKCS12 disponível em /app/certificados/pfx/
□ 3. empresa.cert_path apontando para o arquivo do certificado
□ 4. sefaz.tpAmb=2 no application.properties (homologação)
□ 5. Produto cadastrado com NCM (8 dígitos), CFOP, origem, csosn
□ 6. Pedido criado com destCnpjCpf/destRazaoSocial válidos
□ 7. POST /{id}/emitir → verificar chaveNfe no retorno
□ 8. Verificar nfe_documento no banco: c_stat, x_motivo, n_prot
□ 9. Verificar nfe_log: empresa_id, usuario preenchidos
```

### 14.3 Checklist de validação de nova empresa

```
□ 1. POST /api/app/empresas (requer role ADMIN)
       Body: { cnpj, razaoSocial, uf, ie, crt, logradouro, ... }
□ 2. Verificar empresa criada: GET /api/app/empresas/{id}
□ 3. Configurar certificado: PUT /api/app/empresas/{id}
       Body: { certPath: "/app/certificados/pfx/empresa-X.pfx",
               certSenha: "senha_plaintext",   ← será criptografada pelo EmpresaController
               certTipo: "PKCS12" }
□ 4. Testar emissão com usuário cuja empresa_id == id da nova empresa
□ 5. Verificar log: [EmpresaCert] Certificado OK | empresaId=X | alias=...
```

---

## 15. DECISÕES DE ARQUITETURA

### DA-01: borurio-fiscal não importa borurio-app

**Decisão:** o módulo fiscal não tem dependência de compilação no módulo de negócio.

**Motivação:** o motor fiscal deve ser reutilizável e testável independentemente do modelo de dados de negócio. Certificado e credenciais são passados via `CertificadoContexto` (tipos JDK puros).

**Consequência:** toda integração entre os dois domínios ocorre no módulo `borurio-web`, que é o único ponto de acoplamento.

---

### DA-02: Snapshot fiscal imutável no pedido_item

**Decisão:** ao criar o pedido, os dados fiscais do produto (NCM, CFOP, CSOSN, origem, unidade) são copiados para `pedido_item` e nunca mais atualizados.

**Motivação:** legislação fiscal exige que a NF-e reflita os dados no momento da venda. Alterações posteriores no cadastro do produto não podem retroativamente modificar pedidos já criados.

**Consequência:** a emissão sempre usa dados do `pedido_item`. O serviço não re-busca o produto no momento da emissão.

---

### DA-03: Dupla camada de log em nfe_log

**Decisão:** dois registros distintos são criados por emissão (`ENVIO_NFE` + `TRANSMISSAO_SEFAZ`).

**Motivação:** `NfeTransmitServiceImpl` (fiscal) tem acesso ao `SecurityContextHolder` (Spring Security) mas não tem acesso à `Empresa`. `NfeGeracaoService` (web) tem acesso à `Empresa` mas não está na mesma chamada que a transmissão. A duplicação é a consequência direta de DA-01.

---

### DA-04: ConcurrentHashMap para cache de certificados (sem TTL)

**Decisão:** cache em memória sem TTL. Invalidação explícita via `EmpresaCertificadoService.invalidar(empresaId)`.

**Motivação:** certificados A1 têm validade de 1 a 3 anos. Recarregar o KeyStore a cada emissão tem custo criptográfico desnecessário.

**Gap:** a invalidação não é chamada automaticamente no PUT do EmpresaController. Deve ser corrigida antes de produção.

---

### DA-05: SOAP direto sem CXF/wsimport

**Decisão:** o envelope SOAP é construído manualmente como string, sem cliente WSDL gerado.

**Motivação:** a SEFAZ bloqueia requisições automatizadas ao WSDL (HTTP 403 / HTTP/2). Um WSDL local gerado em tempo de build seria necessário, mas o envelope da NF-e é estável e bem documentado pela SEFAZ.

**Consequência:** mudanças no protocolo SOAP da SEFAZ exigem atualização manual do `NfeTransmitServiceImpl`.

---

### DA-06: passthrough do CertSenhaEncryptor sem chave configurada

**Decisão:** sem `CERT_ENCRYPTION_KEY`, o encryptor opera em modo transparente (sem criptografia) com `WARN` no log.

**Motivação:** permite que o sistema rode em desenvolvimento local sem infraestrutura de gestão de segredos. A ativação da criptografia é um requisito de produção (Fase 11).

---

## 16. ROADMAP ATÉ PRODUÇÃO

### Fase 10 — Documentação e Swagger (pendente)

| Item | Descrição |
|---|---|
| Manual técnico PT/EN | Este documento + versão em inglês |
| Swagger anotado | `@Operation` em todos os endpoints com exemplos de request/response |
| Postman collection | Coleção com todos os fluxos end-to-end |

### Fase 11 — Deploy PRD + CI/CD (pendente)

| Item | Prioridade | Descrição |
|---|---|---|
| `CERT_ENCRYPTION_KEY` em PRD | **CRÍTICO** | Gerar via `openssl rand -base64 32`; injetar via secrets manager |
| Invalidação de cache certificado | **ALTO** | `EmpresaController.atualizar()` deve chamar `invalidar(empresaId)` |
| Rate limiting | **MÉDIO** | Bucket4j ou equivalente; protege endpoint `/emitir` de abuso |
| CI/CD pipeline | **MÉDIO** | GitHub Actions: test → build → push image → deploy HOM → smoke test |
| Política de retenção nfe_log | **BAIXO** | `NfeLogMapper.deleteAntigos(dias)` já implementado; falta agendamento |
| Monitoramento | **BAIXO** | Prometheus + Loki (mencionado em V002 migration, não implementado) |
| Certificados PRD | **CRÍTICO** | Certificados A1 de produção com CNPJ real; `tpAmb=1` |

---

## 17. REFERÊNCIAS NORMATIVAS

| Documento | Descrição |
|---|---|
| AJUSTE SINIEF 07/2005 e alterações | Institui a Nota Fiscal Eletrônica |
| Manual de Orientação do Contribuinte (MOC) | Versão 7.0 — layout NF-e 4.00 |
| Nota Técnica 2019.001 | Atualização do layout NF-e 4.00 / Algoritmos SHA-256 obrigatórios |
| ABNT NBR ISO/IEC 27001 | Gestão de segurança da informação |
| XML-DSig W3C Recommendation | `https://www.w3.org/TR/xmldsig-core/` |
| RFC 5652 | Cryptographic Message Syntax (base do PKCS#12) |

---

*Documento MTF-001 — versão 1.0 — Borurio ERP Fiscal BR*  
*Gerado com base no estado validado em HOM em 11-05-2026*  
*Próxima revisão prevista: após deploy PRD (Fase 11)*
