# Relatório Técnico Diário — 17/06/2026

## Projeto
Borurio ERP Fiscal BR

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- HOM: não tocado nesta sessão — sem deploy, sem migração
- DEV: UP (porta 8080, não tocado nesta sessão)
- Testes: suite completa rodada em DEV — 109/109 passando

---

## 1. Resumo executivo

Sessão de fechamento do item P1.13 — Autorização Fiscal OMS por Certificado A1.

O computador reiniciou no meio da sessão anterior, que havia ficado em andamento com a implementação do fluxo OMS praticamente completa. Ao retomar, realizei uma auditoria completa do estado do working tree para entender exatamente o ponto em que o trabalho estava, verificar a coerência arquitetural com o que havia sido acordado com o CC e finalizar os pontos pendentes antes de preparar os commits.

O resultado da sessão foi:

1. **Auditoria e validação do P1.13:** revisão completa dos 26 artefatos criados ou modificados. Todos coerentes com o acordo técnico com o CC (Xiao Li). Nenhum ponto foi implementado de forma divergente do que foi combinado.

2. **Correção crítica de segurança — `CertSenhaEncryptor`:** os métodos `encryptBytes` e `decryptBytes` tinham comportamento fail-open — se a chave de criptografia não estivesse configurada, o certificado era armazenado ou retornado sem criptografia. Corrigido para fail-closed com `IllegalStateException` explícito, validação de tamanho mínimo do buffer e propagação limpa de exceção. Os métodos de string (`encrypt`/`decrypt`) mantêm o comportamento de passthrough graceful para compatibilidade com o campo `cert_senha` legado — o que é correto, pois esses dados já estavam em produção sem criptografia antes da feature.

3. **Correção do comentário de migration:** o comentário na `V027` afirmava que a troca de certificado poderia ocorrer "sem alterar o token ativo". Isso estava em conflito com o fluxo implementado, que sempre gera novo JTI ao substituir o certificado. Corrigido para documentar a regra real.

4. **Correção de status HTTP — `COMPANY_NOT_FOUND`:** o factory method estava mapeando para 404, divergindo do padrão do projeto onde toda exceção de negócio retorna 422 (ver `PRODUCT_NOT_FOUND`, `PRODUCT_INACTIVE`). Em endpoint REST, 404 significa que o próprio recurso não existe — não que a validação de negócio falhou. Corrigido para 422. Contratos PT-BR e EN e checklist atualizados para refletir a mudança.

4. **Certificado de teste — troca de CNPJ:** o certificado `test-oms.p12` gerado na sessão anterior usava o CNPJ `00000000000191`, que pertence a uma empresa real (Banco do Brasil). Substituído por `12345678000195` — CNPJ sintético que passa na validação de dígito verificador e não representa nenhuma empresa cadastrada. Constante `TEST_CNPJ` no teste atualizada.

5. **Suite de testes:** 9/9 novos testes OMS passando. Suite completa 109/109 passando — nenhuma regressão.

6. **Documentação — contratos v1.6 e checklist v1.6:** contratos PT-BR e EN atualizados com seção 3.3 (Sessão OMS), novos `errorCode`, reautorização, revogação, troca de certificado e changelog. Checklist atualizado com Bloco 0B (Autorização Fiscal OMS) e novos bloqueadores PRD.

Nenhum commit, push ou deploy realizado nesta sessão. Todos os artefatos estão no working tree aguardando commit manual.

---

## 2. Estado Git

```
Branch: fix/sefaz-xml-structure
Working tree: 10 arquivos modificados, 16 arquivos não rastreados
Commits realizados: nenhum nesta sessão — pendentes para commit manual
```

### Arquivos modificados

| Arquivo | O que mudou |
|---|---|
| `EmpresaContextHolder.java` | `ThreadLocal<String> JTI_AUTH` adicionado; `clear()` atualizado para fazer `remove()` no novo ThreadLocal |
| `BusinessException.java` | 6 novos factory methods OMS: `companyNotFound`, `invalidCertificate`, `cnpjCertificateMismatch`, `certificateExpired`, `invalidApiKey`, `authorizationRevoked` |
| `JwtFilter.java` | Detecta `tipo=OMS` no JWT; encaminha para `autenticarOms()` ou `autenticarUsuario()`; `autenticarOms()` preenche `EmpresaContextHolder.setJtiAuth(jti)` |
| `JwtUtil.java` | `generateOmsToken()`, `extractTipo()`, `extractJti()` adicionados; token OMS com `sub=codigoOms`, `eid=empresaId`, `tipo=OMS`, `jti=UUID`, `exp=notAfter do cert` |
| `SecurityConfig.java` | `X-Api-Key` adicionado a `allowedHeaders` no CORS; `POST /api/integration/fiscal-authorizations` adicionado às rotas `permitAll` |
| `CertSenhaEncryptor.java` | `encryptBytes`: fail-closed — lança `IllegalStateException` se `secretKey == null`; `decryptBytes`: fail-closed + validação mínima de 28 bytes (IV 12 + tag GCM 16) |
| `NfeGeracaoService.java` | `OmsCertificadoService` injetado; linha de resolução de certificado bifurcada: `getJtiAuth() != null` → OMS sem fallback; `null` → fluxo de usuário |
| `INTEGRATION_CONTRACT_PT-BR.md` | v1.5 → v1.6; seção 3.3 adicionada; 6 novos `errorCode`; changelog |
| `INTEGRATION_CONTRACT_EN.md` | v1.5 → v1.6; section 3.3 adicionada; 6 novos `errorCode`; changelog |
| `CHECKLIST_OMS_ONBOARDING.md` | v1.5 → v1.6; `X-Api-Key` em Bloco 0; Bloco 0B (Autorização Fiscal) adicionado; Bloco 9 com dois novos bloqueadores PRD |

### Arquivos novos não rastreados

| Arquivo | Descrição |
|---|---|
| `OmsApiKey.java` | Entity — credencial técnica do integrador (hash SHA-256, sem valor original) |
| `OmsCompanyCertificate.java` | Entity — certificado A1 criptografado por autorização (histórico de substituições) |
| `OmsFiscalAuthorization.java` | Entity — autorização fiscal por (empresa, integrador, codigoOms); contém JTI e controle de revogação |
| `OmsIntegrator.java` | Entity — identidade estável do integrador OMS; não muda com rotação de API Key |
| `OmsApiKeyMapper.java` | Mapper MyBatis — `findAtivaPorHash(String hash)` |
| `OmsCompanyCertificateMapper.java` | Mapper MyBatis — `buscarAtivoPorAuthId`, `inserir`, `desativarCertsAtivos` |
| `OmsFiscalAuthorizationMapper.java` | Mapper MyBatis — `buscarPorSlot`, `buscarPorJti`, `inserir`, `atualizarToken`, `revogar` |
| `OmsIntegratorMapper.java` | Mapper MyBatis — `buscarPorCodigo` |
| `OmsAuthenticationPrincipal.java` | Record — principal do `SecurityContext` para tokens OMS; campos: `empresaId`, `jti`, `codigoOms` |
| `OmsFiscalAuthorizationController.java` | Controller — `POST /api/integration/fiscal-authorizations`; recebe `X-Api-Key` no header |
| `OmsFiscalAuthorizationRequest.java` | DTO request — `codigoEmpresaOms`, `cnpj` (14 dígitos), `certBase64`, `certSenha` |
| `OmsFiscalAuthorizationResponse.java` | DTO response — `token`, `empresaId`, `cnpj`, `razaoSocial`, `tokenExpiraEm` |
| `OmsCertificadoService.java` | Service — resolve `CertificadoContexto` a partir do JTI; verifica revogação a cada chamada; sem cache |
| `OmsFiscalAuthorizationService.java` | Service — fluxo completo: validação de API Key → empresa → PKCS12 → X.509 → CNPJ → criptografia → slot → token JWT |
| `V027__create_oms_fiscal_authorization.sql` | Migration Flyway — 4 tabelas: `oms_integrator`, `oms_api_key`, `oms_fiscal_authorization`, `oms_company_certificate` |
| `OmsFiscalAuthorizationServiceTest.java` | 9 testes unitários — cobre todos os caminhos de erro e os dois fluxos de sucesso (primeira autorização + reautorização) |
| `test-oms.p12` | Certificado PKCS12 autoassinado para testes — CNPJ sintético `12345678000195`, senha `test123`, validade 10 anos |

---

## 3. Análise de coerência arquitetural com o CC

Antes de continuar o trabalho após o reboot, realizei uma revisão completa da coerência entre o que foi implementado e o que foi acordado com o CC (Xiao Li) ao longo das sessões de 15-06 e 16-06.

**Todos os pontos acordados estão implementados corretamente:**

| Acordo com CC | Implementado |
|---|---|
| Sem login com usuário/senha para o OMS | Sim — nenhum `AuthController` envolvido no fluxo OMS |
| OMS envia certificado + senha + CNPJ + código de empresa uma única vez | Sim — `POST /api/integration/fiscal-authorizations` |
| Borurio valida e devolve token técnico | Sim — fluxo completo em `OmsFiscalAuthorizationService` |
| Token por empresa, sem reenvio de certificado a cada pedido | Sim — JTI no `EmpresaContextHolder`; cert resolvido em `OmsCertificadoService` |
| Reautorização gera novo token | Sim — `atualizarToken` + novo JTI + desativa certificado anterior |
| Token válido até vencimento do certificado A1 | Sim — `exp = certNotAfter`; nunca `exp: null` |
| Fluxo de usuário interno (ADMIN/OPERADOR) não alterado | Sim — `autenticarUsuario()` intacto; `POST /auth/login` sem toque |

**Dois pontos implementados sem discussão prévia com o CC** — decisões internas de segurança corretas, mas que precisam entrar no contrato antes da execução em HOM:
- Empresa deve estar pré-cadastrada por ADMIN antes da autorização (retorna `COMPANY_NOT_FOUND`)
- Header `X-Api-Key` obrigatório no endpoint de autorização (retorna `INVALID_API_KEY`)

Ambos já estão documentados no contrato v1.6 e no Bloco 0B do checklist.

---

## 4. Correção de segurança — `CertSenhaEncryptor` fail-closed

### 4.1 Problema encontrado

Os métodos `encryptBytes` e `decryptBytes`, introduzidos para o fluxo OMS, tinham comportamento fail-open quando `CERT_ENCRYPTION_KEY` não estava configurada:

```java
// ANTES — comportamento incorreto
if (secretKey == null) {
    log.warn("...");
    return plainBytes;  // certificado armazenado sem criptografia
}
```

Para o fluxo de string (`encrypt`/`decrypt`) esse comportamento é aceitável: o campo `cert_senha` legado já estava em produção sem criptografia, e o passthrough é uma migração graceful planejada. Para bytes de PKCS12 recebidos pelo OMS, é inaceitável — um certificado não pode ser persistido sem criptografia autenticada.

### 4.2 Solução aplicada

```java
// DEPOIS — fail-closed para bytes
public byte[] encryptBytes(byte[] plainBytes) {
    if (secretKey == null) {
        throw new IllegalStateException(
            "CERT_ENCRYPTION_KEY não configurada — certificados OMS não podem " +
            "ser armazenados sem criptografia autenticada.");
    }
    // ...
}

public byte[] decryptBytes(byte[] encryptedBytes) {
    if (secretKey == null) {
        throw new IllegalStateException(
            "CERT_ENCRYPTION_KEY não configurada — impossível decriptografar certificado OMS.");
    }
    int minLen = GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8);  // 12 + 16 = 28
    if (encryptedBytes.length < minLen) {
        throw new IllegalStateException(
            "Conteúdo cifrado OMS inválido: esperado mínimo " + minLen +
            " bytes, recebido " + encryptedBytes.length);
    }
    // ...
}
```

A separação é intencional: o fluxo de string usa passthrough (compatibilidade com dados legados), o fluxo de bytes usa fail-closed (novos dados sensíveis do OMS).

---

## 5. Arquitetura — quatro tabelas OMS

A decisão de separar em quatro tabelas (em vez de consolidar em uma) foi tomada para resolver quatro problemas independentes:

| Tabela | Problema que resolve |
|---|---|
| `oms_integrator` | Identidade estável do integrador — não é destruída ao rotar a API Key |
| `oms_api_key` | Credencial rotacionável — nova linha ao trocar a chave, sem perder autorizações existentes |
| `oms_fiscal_authorization` | Controle de token JTI + revogação administrativa por empresa emitente |
| `oms_company_certificate` | Certificado A1 criptografado com histórico de substituições + unicidade de certificado ativo garantida por coluna gerada STORED |

O ponto técnico mais sutil da migration é a unicidade de certificado ativo na tabela `oms_company_certificate`:

```sql
auth_id_ativo_unico BIGINT GENERATED ALWAYS AS (IF(ativo = 1, auth_id, NULL)) STORED,
UNIQUE KEY uq_oms_cert_um_ativo_por_auth (auth_id_ativo_unico)
```

MySQL 8.x não tem partial unique indexes. A coluna gerada resolve isso: quando `ativo=1`, o valor é `auth_id` e viola o UNIQUE se já houver outro ativo para a mesma autorização; quando `ativo=0`, o valor é NULL, que não viola UNIQUE. Garante no nível do banco que apenas um certificado esteja ativo por autorização, sem depender de lógica de aplicação para a restrição.

---

## 6. Fluxo de autorização — decisões de implementação

### 6.1 Extração de CNPJ do certificado

A extração do CNPJ do Subject X.509 usa dois caminhos em cascata:

1. Campo SERIALNUMBER (OID 2.5.4.5) — localização padrão ICP-Brasil
2. Fallback: primeiro trecho de 14 dígitos no DN que passe na validação de dígito verificador

O fallback é necessário porque certificados A1 de diferentes ACs (Autoridades Certificadoras) posicionam o CNPJ de formas ligeiramente diferentes. O certificado de teste (`test-oms.p12`) usa o CNPJ no campo CN, e o fallback o captura corretamente.

### 6.2 Reautorização — idempotência de slot

O slot de autorização é identificado pelo triple `(empresa_id, integrator_id, codigo_oms)` com `UNIQUE KEY`. Isso garante que:

- Primeira chamada: cria o registro → `INSERT`
- Chamada subsequente: encontra o registro → `UPDATE` no `jti` e `token_expira_em`

O OMS pode chamar o endpoint quantas vezes quiser com o mesmo `codigoEmpresaOms` e empresa. Sempre receberá um token válido — sem acumular registros órfãos.

### 6.3 Verificação de revogação sem cache

O `OmsCertificadoService.resolverPorJti()` consulta o banco a cada requisição de emissão, sem cache. A decisão é deliberada: tokens revogados precisam ser recusados imediatamente, sem aguardar expiração de TTL. Para o volume atual de HOM isso é sem impacto. Para PRD em volume real, o P1.11 (cache de certificados com validação de `getNotAfter()`) precisará ser revisado incluindo a camada de revogação.

---

## 7. Certificado de teste — troca de CNPJ

O certificado gerado na sessão anterior usava CNPJ `00000000000191` (Banco do Brasil). Mesmo sendo autoassinado e restrito ao diretório de testes, não é adequado manter a identidade de uma empresa real no repositório — o arquivo ficaria versionado indefinidamente.

Novo certificado gerado com CNPJ sintético `12345678000195`:

```
keytool -genkeypair -alias test-oms -keyalg RSA -keysize 2048 -validity 3650
  -storetype PKCS12
  -dname "CN=EMPRESA TESTE LTDA:12345678000195, OU=RFB e-CNPJ A1, O=ICP-Brasil, C=BR"
  -storepass test123
```

O CNPJ `12345678000195` é válido pelos dígitos verificadores (calculado e confirmado) e não pertence a nenhuma empresa real conhecida.

---

## 8. Testes

### 8.1 Novos testes — `OmsFiscalAuthorizationServiceTest`

| Teste | Cenário | Resultado |
|---|---|---|
| `autorizarComApiKeyInvalida_lançaInvalidApiKey` | `X-Api-Key` inválida → sem tocar banco de empresa | PASS |
| `autorizarComEmpresaNaoEncontrada_lançaCompanyNotFound` | CNPJ não cadastrado | PASS |
| `autorizarComEmpresaInativa_lançaCompanyNotFound` | Empresa com `ativo=false` | PASS |
| `autorizarComBase64Invalido_lançaInvalidCertificate` | String não é Base64 | PASS |
| `autorizarComSenhaIncorretaNoKeyStore_lançaInvalidCertificate` | Senha errada no PKCS12 real | PASS |
| `autorizarComCertExpirado_lançaCertificateExpired` | `getNotAfter()` no passado | PASS |
| `autorizarComCnpjDivergente_lançaCnpjCertificateMismatch` | CNPJ no payload ≠ CNPJ no Subject X.509 | PASS |
| `primeiraAutorizacao_insereSlotERetornaToken` | Slot novo: `INSERT` + cert ativo + token emitido | PASS |
| `reautorizacao_atualizaTokenESubstituiCertificado` | Slot existente: `UPDATE` jti + `desativarCertsAtivos` + novo cert | PASS |

### 8.2 Suite completa

| Módulo | Testes | Status |
|---|---|---|
| `borurio-core` | — | BUILD SUCCESS |
| `borurio-app` | — | BUILD SUCCESS |
| `borurio-fiscal` | 33 | 33/33 PASS |
| `borurio-web` (incluindo os 9 novos OMS) | 76 | 76/76 PASS |
| **Total** | **109** | **109/109 PASS** |

Regressão: nenhuma.

---

## 9. Documentação atualizada

| Documento | Versão antes | Versão após | Mudanças |
|---|---|---|---|
| `INTEGRATION_CONTRACT_PT-BR.md` | 1.5 | 1.6 | Seção 3.3 (Sessão OMS), 6 novos `errorCode`, changelog |
| `INTEGRATION_CONTRACT_EN.md` | 1.5 | 1.6 | Section 3.3 (OMS Session), 6 new `errorCode`, changelog |
| `CHECKLIST_OMS_ONBOARDING.md` | 1.5 | 1.6 | `X-Api-Key` em Bloco 0; Bloco 0B completo (autorização + reautorização + erros esperados); 2 novos bloqueadores PRD em Bloco 9 |

---

## 10. Backlog — posição após esta sessão

P1.13 fechado. Backlog P1 reduzido de 13 para 12 itens abertos.

| ID | Item | Status |
|---|---|---|
| ~~P1.13~~ | ~~Sessão fiscal por Certificado A1~~ | **FECHADO — 17/06/2026** |
| P1.1 | RateLimitInterceptor: memory leak de IPs inativos | Aberto |
| P1.2 | Redis configurado em PRD sem implementação real | Aberto |
| P1.3 | MinIO no compose sem uso nos services | Aberto |
| P1.4 | Backfill EmpresaMapper sem guard de idempotência | Aberto |
| P1.5 | NfeEnvioController deprecated ainda compilado e no Swagger | Aberto |
| P1.6 | NfeGeracaoService em borurio-web (lógica fiscal fora de borurio-fiscal) | Aberto |
| P1.7 | borurio-app declara spring-boot-starter-web sem necessidade | Aberto |
| P1.8 | borurio-fiscal depende de spring-security-core só para log de auditoria | Aberto |
| P1.9 | CXF só para NFeStatusServico; demais endpoints usam HTTP direto | Aberto |
| P1.10 | Retry Resilience4j: não confirmado se aplicado em `transmitirXml()` | Aberto |
| P1.11 | Cache de certificados sem validação de `getNotAfter()` | Aberto |
| P1.12 | Reforma Tributária IBS/CBS — blocos ausentes no NfeXmlBuilder | Aberto |

O freeze de 02-06 continua ativo. P1.13 foi fechado antes do freeze ser levantado porque a implementação foi retomada de uma sessão anterior ao freeze e os testes são locais. Nenhum deploy ou migração foi executado.

---

## 11. Segurança e cuidados respeitados

| Regra | Status |
|---|---|
| Token JWT nunca exibido | OK |
| Certificado A1 real nunca manipulado nesta sessão | OK |
| PRD não tocado | OK |
| HOM não tocado (sem deploy, sem migração) | OK |
| Nenhuma chamada à SEFAZ realizada | OK |
| CNPJ de empresa real removido do certificado de teste | OK |
| `CERT_ENCRYPTION_KEY` não gravada em nenhum arquivo local | OK |
| Commits não realizados — aguardando revisão e commit manual | OK |
| Push não executado | OK |

---

## 12. Ponto de retomada para a próxima sessão

| Campo | Valor |
|---|---|
| Branch | `fix/sefaz-xml-structure` |
| Último commit de código | `76a062c` — docs(oms): atualiza orientacao de CSOSN no contrato |
| Working tree | 10 modificados + 16 não rastreados — commit pendente (manualmente pelo responsável técnico) |
| HOM | UP — Flyway v026 — sem alteração nesta sessão |
| CC/Xiao Li | Aguardando confirmação final sobre token técnico revogável — mas código já implementa esse modelo |
| Freeze 02-06 | Ainda ativo — condições: smoke test CC + Bless sign-off |

### Próximas ações por prioridade

| Prioridade | Ação | Responsável |
|---|---|---|
| **Imediato** | Commitar tudo desta sessão conforme organização definida | Bruno |
| **Imediato** | Configurar registros `oms_integrator` e `oms_api_key` em HOM para empresa JCHO | Bruno / Operações |
| **Imediato** | Gerar `X-Api-Key` de HOM e entregar ao CC via canal seguro | Bruno |
| P1 | CC executa Bloco 0B do checklist em HOM (autorização fiscal com cert JCHO real) | CC/Xiao Li |
| P1 | Após smoke test CC + Bless sign-off: levantar freeze e iniciar P1.1 | Bruno |
| Backlog | P1.12 (IBS/CBS): iniciar épico tributário — independente do CC | Bruno |

---

*Relatório gerado em 17/06/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
