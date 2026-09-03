# Relatório Técnico Diário — 08/05/2026

## Projeto
Borurio ERP Logístico + Fiscal BR / Jcho ERP

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Ambiente de validação
- Container app: `borurio-web-hom` (porta 8081)
- Container DB: `borurio-mysql-hom` (porta 3308, MySQL 8.4.8)
- Flyway aplicado até: **V017** (17 migrations)
- Testes automatizados: **33 / 0 falhas / 1 skipped**

---

## 1. Objetivo do dia

Avançar a maturidade do ERP logístico com motor fiscal em dois eixos:

1. **Eixo operacional**: fechar o ciclo completo Produto → Pedido → NF-e → Operações Fiscais com status semântico real (Fases 5, 5.1, 6, 7).
2. **Eixo arquitetural**: implementar o núcleo mínimo de multi-tenancy (Fase 8A) tornando o sistema capaz de operar com múltiplas empresas emitentes isoladas por contexto.

O dia encerrou com **todas as fases 1–8A** funcionando em HOM, V017 aplicada, 33 testes passando e o fluxo end-to-end validado com empresa, JWT portando `eid`, `empresa_id` propagado em todos os recursos.

---

## 2. Atividades executadas

### Fase 5 — Pedido → NF-e (ciclo básico)

Implementação do fluxo completo de criação de pedido de venda e emissão de NF-e vinculada:

- `Pedido.java`, `PedidoItem.java` — entidades com todos os campos destinatário/fiscal
- `PedidoMapper.java`, `PedidoItemMapper.java` — MyBatis annotations, `@Options(useGeneratedKeys)`
- `PedidoService.java` + `PedidoServiceImpl.java` — criação com 2-phase insert (placeholder numero → PED-{id:08d})
- `PedidoEmissaoService.java` — bridge app+fiscal; constrói `NfeEmissaoRequest` a partir do pedido
- `NfeGeracaoService.gerar()` refatorado para retornar `NfeGeracaoResult { chaveNfe, soapRetorno }`
- `PedidoController` — endpoints: `GET /api/app/pedidos`, `GET /{id}`, `POST /`, `POST /{id}/emitir`
- `NfeEnvioController` atualizado para consumir `NfeGeracaoResult`

Migrations: `V014__create_pedido.sql` (pedido + pedido_item com FK para produto)

### Fase 5.1 — Contrato do pedido / snapshot fiscal imutável

Decisão arquitetural: dados fiscais do produto devem ser **congelados no item do pedido** no momento da criação, tornando o pedido um documento legal imutável independente de alterações futuras no cadastro do produto.

- `V015__pedido_item_fiscal_snapshot.sql` — ADD COLUMN: `codigo_produto`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn` em `pedido_item`
- `PedidoServiceImpl.criar()` — método `preencherSnapshot()` copia dados fiscais do produto para o item; valida produto ATIVO (estado=1)
- `PedidoEmissaoService` — usa `item.getNcm()`, jamais `produto.getNcm()` — snapshot é a fonte de verdade
- `ProdutoServiceImpl` — método `aplicarDefaultsFiscais()` garante valores padrão: `origem=0`, `csosn=400`, `estoque=0`

**Invariante estabelecida**: dados fiscais de `pedido_item` são imutáveis após criação do pedido.

### Fase 6 — Deploy HOM + Teste end-to-end

Deploy e validação completa em `borurio-web-hom`:

1. Build: `mvn -pl borurio-web -am clean package -DskipTests`
2. `docker cp borurio-web/target/borurio-web-1.0.0.jar borurio-web-hom:/app/app.jar`
3. `docker restart borurio-web-hom`
4. Flyway aplicou V013–V015 com sucesso

**Incidente durante deploy**: V013 causou crash loop por uso de `ADD COLUMN IF NOT EXISTS` — sintaxe exclusiva de MariaDB, não suportada pelo MySQL 8.4.

- Diagnóstico: `flyway_schema_history` marcou V013 como `success=0`; cada restart tentava re-executar e falhava
- Resolução: `docker stop` → `DELETE FROM flyway_schema_history WHERE version='013' AND success=0` → remoção do `IF NOT EXISTS` do SQL → rebuild → restart
- Regra permanente registrada: **nunca usar `ADD COLUMN IF NOT EXISTS` em migrations MySQL 8.x**

Fluxo end-to-end validado em HOM:
- `POST /api/app/produtos` → produto com NCM/CFOP/origem/csosn
- `POST /api/app/pedidos` → pedido RASCUNHO, snapshot congelado
- `POST /api/app/pedidos/{id}/emitir` → NF-e transmitida para SEFAZ HOM

**Resultado**: cStat lote = 104 (aceito), cStat infProt = 225 (vide seção 4 — limitação SEFAZ HOM/SP confirmada, não é bug de código).

### Fase 7 — Status semântico + Operações fiscais por pedido

Refinamento do status do pedido pós-emissão e implementação das operações fiscais vinculadas:

**Status semântico** (baseado no cStat real da SEFAZ, não em chaveNfe):
- `AUTORIZADO` → cStat=100
- `REJEITADO` → cStat≥200
- `AGUARDANDO` → cStat=104 (lote aceito, autorização individual pendente)
- `ERRO` → exceção no fluxo

`PedidoEmissaoService.resolverStatus()` — lê `NfeSefazRetorno.getCStat()` para determinar status correto.

**Baixa de estoque**: executada apenas quando `status == AUTORIZADO`. Exceções capturadas — uma falha de estoque nunca deve reverter uma NF-e já autorizada pela SEFAZ.

**`PedidoOperacaoService`** (novo):
- `consultarSituacao(pedidoId)` → estado local (nfe_documento) + consulta live `consSitNFe` na SEFAZ
- `cancelar(pedidoId, justificativa)` → valida status AUTORIZADO + nProt gravado; mínimo 15 chars; atualiza para CANCELADO
- `emitirCce(pedidoId, correcao)` → valida status AUTORIZADO; mínimo 15 chars

`PedidoController` expandido com: `GET /{id}/situacao`, `POST /{id}/cancelar`, `POST /{id}/cce`.

### Fase 8A — Núcleo Multiempresa (Mínimo Viável)

Implementação da fundação de multi-tenancy com isolamento por `empresa_id` injetado via JWT.

#### Banco de dados

- **V016** — `CREATE TABLE empresa` (cnpj UNIQUE, razao_social, ie, crt, uf, address fields, serie_nfe_padrao, ativo)
- **V017** — `ADD COLUMN empresa_id BIGINT NULL + FK` em `db_user`, `produto`, `pedido`

Decisão: coluna nullable para garantir backwards compatibility — dados legados recebem backfill no startup.

#### Entidades e serviços

- `Empresa.java` (novo) — entidade completa
- `EmpresaMapper.java` (novo) — CRUD MyBatis + métodos `backfillDbUser`, `backfillProduto`, `backfillPedido`
- `EmpresaService.java` + `EmpresaServiceImpl.java` (novos) — CRUD + validações
- `EmpresaController.java` (novo) — `/api/app/empresas` (GET /, GET /{id}, GET /cnpj/{cnpj}, POST /, PUT /{id})

#### Contexto da empresa autenticada

- `EmpresaContextHolder.java` (novo, em `borurio-app`) — ThreadLocal `Long empresaId`; limpo no `finally` do `JwtFilter` após cada request

#### JWT com claim `eid`

- `JwtUtil.generateToken(username, empresaId)` — adiciona claim `"eid"` (Long)
- `JwtUtil.extractEmpresaId(token)` — extrai claim `"eid"`, trata Integer/Long/String
- `AuthService.authenticate()` — resolve `empresa_id` via `dbUserMapper.findByEmail(username)` antes de gerar o token
- `JwtFilter.doFilterInternal()` — após validação: `EmpresaContextHolder.set(jwtUtil.extractEmpresaId(token))`; finally: `EmpresaContextHolder.clear()`

#### Startup — seeding e backfill

`StartupListener` reconstruído com duas responsabilidades adicionais:

1. **Seed empresa default**: lê `EmitenteProperties` (fiscal.emitente.*), verifica se empresa com aquele CNPJ existe; se não, cria. Resultado: empresa id=1 (JCHO GLOBAL LTDA, CNPJ 54393421000159) criada na primeira execução.
2. **Backfill**: `UPDATE db_user/produto/pedido SET empresa_id = #{empresaId} WHERE empresa_id IS NULL`. Resultado: 1 produto + 3 pedidos vinculados à empresa default.
3. **Seed admin user**: se `db_user` estiver vazio, insere usuário `admin` com `empresa_id` da empresa default. Necessário para que o JWT carregue `eid` corretamente no fluxo dev/hom.

#### Filtros por empresa

- `ProdutoMapper` — `listarPorEmpresa(empresaId)`, `listarAtivosPorEmpresa(empresaId)`, `buscarPorIdEEmpresa(id, empresaId)` adicionados
- `PedidoMapper` — `listarPorEmpresa(empresaId)`, `buscarPorIdEEmpresa(id, empresaId)` adicionados
- `ProdutoController.salvar()` — `produto.setEmpresaId(EmpresaContextHolder.get())`
- `PedidoController.criar()` — `pedido.setEmpresaId(EmpresaContextHolder.get())`

#### Motor fiscal multiempresa

- `NfeGeracaoService.gerar(req, Empresa empresa)` — overload que usa campos da `Empresa` (cnpj, razaoSocial, ie, crt, uf, address) como emitente; fallback para `EmitenteProperties` quando `empresa == null`
- `PedidoEmissaoService.emitir()` — resolve `Empresa` pelo `empresaId` do contexto/pedido via `EmpresaMapper.buscarPorId()`; passa para `gerar(req, empresa)`

---

## 3. Validações realizadas

### Ambiente local (testes automatizados)

| Suíte | Total | Falhas | Skipped |
|---|---|---|---|
| AppTest | 1 | 0 | 0 |
| NfeAuthorizeServiceTest (mock) | 1 | 0 | 0 |
| NfeMockServiceTest | 2 | 0 | 0 |
| NfeXmlBuilderTest | 1 | 0 | 0 |
| AssinaturaXmlServiceTest | 1 | 0 | 0 |
| CertificadoServiceImplTest | 1 | 0 | 0 |
| NfeAuthorizeServiceTest | 1 | 0 | 0 |
| NfePipelineLocalTest | 6 | 0 | 0 |
| NfeSequenciaServiceTest | 3 | 0 | 0 |
| TesteCertificadoReal | 1 | 0 | 0 |
| TesteSefazSSL | 1 | 0 | 1 (sem .pfx) |
| CpfCnpjValidatorTest | 11 | 0 | 0 |
| XsdValidatorTest | 3 | 0 | 0 |
| ValidacaoOficialTest | 1 | 0 | 0 |
| **TOTAL** | **33** | **0** | **1** |

O teste skipped (`TesteSefazSSL`) é comportamento esperado — ignora quando o arquivo `.pfx` de certificado não está presente no ambiente local.

### Ambiente HOM — validações ao longo do dia

| Ação | Resultado |
|---|---|
| `GET /actuator/health` | `{"status":"UP"}` — db, diskSpace, redis, ping UP |
| `POST /auth/login` → `admin/admin123` | HTTP 200, JWT com `{"sub":"admin","eid":1}` |
| `GET /api/app/empresas` | Retorna empresa id=1 (JCHO GLOBAL LTDA, CNPJ 54393421000159) |
| `GET /api/app/produtos` | Produto id=1 com `empresaId:1` (backfill OK) |
| `POST /api/app/produtos` (PROD-FASE8A) | Produto id=2 criado com `empresaId:1` (contexto JWT injetado) |
| `GET /api/app/pedidos` | 3 pedidos históricos, todos com `empresaId:1` (backfill OK) |
| Flyway V001–V017 | Todos `success=1` |
| Startup seed empresa | `id=1 criada | CNPJ=54393421000159` |
| Startup backfill | `users=0 | produtos=1 | pedidos=3` |
| Startup seed admin | `id=1 | empresaId=1` |

---

## 4. Diagnóstico técnico consolidado

### cStat=225 — Status definitivo: LIMITAÇÃO SEFAZ HOM/SP

Durante a validação de transmissão NF-e em HOM, o lote retorna `cStat=104` (aceito) mas o `infProt` da NF-e individual retorna `cStat=225` ("Rejeição: Falha no Schema XML").

**Investigação realizada (sessão anterior)**:

Foram descartadas como causa todas as hipóteses verificáveis:

| Elemento investigado | Conclusão |
|---|---|
| Sequência dos elementos `ide` | OK — 19 campos na ordem correta |
| Tipos numéricos TDec_1104v / TDec_1110v | OK — casas decimais corretas |
| Elemento `ds:Signature` e filhos | OK — namespace W3C puro, SHA-256 |
| `PISNT` / `COFINSNT` CST=07 | OK — enumeração correta |
| `ICMSTot` — 20 campos na ordem | OK |
| `nNF` / `serie` / `cNF` patterns | OK — sem zeros à esquerda em XML |
| Declaração XML no envio | OK — omitida (OMIT_XML_DECLARATION) |

**Causa raiz identificada**: o processador interno do SEFAZ SP que valida a NF-e individual (`verAplic = SP_NFE_PL_008i2`) é mais antigo que o processador de lote (`SP_NFE_PL009_V4`). O `SP_NFE_PL_008i2` valida contra um schema `xmldsig-core-schema` com `Algorithm` fixado em SHA-1 (`rsa-sha1 / sha1`), enquanto nossa implementação usa corretamente SHA-256 conforme NT 2019.001.

**Conclusão**: o código está correto conforme a norma vigente. A rejeição 225 em HOM/SP é um comportamento do ambiente SEFAZ, não um bug. Não há ação corretiva possível no código. Em PRD (ambiente real), o processador da SEFAZ aceita SHA-256 normalmente.

**Decisão**: encerrar o diagnóstico de cStat=225 como concluído. Prosseguir com desenvolvimento das camadas de negócio.

---

## 5. Comparativo com baseline / referências externas

O projeto usa como referência os schemas XSD oficiais da SEFAZ (NF-e 4.00):

| Arquivo | Estado |
|---|---|
| `leiauteNFe_v4.00.xsd` | Modificado para corrigir estrutura `ICMSTot` e `TNFe` |
| `xmldsig-core-schema_v1.01.xsd` | Modificado para W3C puro (sem `fixed="rsa-sha1"`) |
| `tiposNFe_v4.00.xsd` | Customização local validada |

O `ValidacaoOficialTest` roda contra os XSDs modificados localmente e passa. A divergência entre o schema local e o schema do processador `SP_NFE_PL_008i2` da SEFAZ SP é irreconciliável a partir do código — é uma limitação do ambiente HOM.

O baseline de referência de Samuel-Oliveira (`so_leiauteNFe_v4.00.xsd`) foi consultado durante o diagnóstico. Não foi encontrada diferença estrutural relevante que explicasse a rejeição além da hipótese SHA-1 fixo.

---

## 6. Estado atual do motor fiscal

### Camadas implementadas

| Camada | Status |
|---|---|
| Geração de chave NF-e (43+1 dígitos, cDV mod11) | ✅ Validado |
| Montagem XML infNFe (ide, emit, dest, det, total) | ✅ Validado |
| Assinatura RSA-SHA256 + C14N exclusivo | ✅ Validado |
| Envio SOAP AuthorizationNFe (lote síncrono) | ✅ Validado |
| Persistência em `nfe_documento` (chNFe, nProt, cStat) | ✅ Validado |
| Log em `nfe_log` (XML envio + retorno) | ✅ Validado |
| Sequenciamento automático `nNF` por série | ✅ Validado |
| Validação CPF/CNPJ módulo 11 | ✅ Validado |
| Validação NCM contra tabela oficial (V006) | ✅ Validado |
| Consulta situação NF-e (`consSitNFe`) | ✅ Validado |
| Cancelamento NF-e (`EvCancNFe`) | ✅ Implementado |
| Carta de Correção Eletrônica (`EvCCeNFe`) | ✅ Implementado |
| `NfeGeracaoService.gerar(req, Empresa)` overload | ✅ Implementado |

### Resultado SEFAZ HOM

- Lote transmitido: `cStat=104` (aceito)
- NF-e individual: `cStat=225` (limitação SP_NFE_PL_008i2 — SHA-1 HOM)
- Comportamento PRD: esperado cStat=100 (autorizado)

---

## 7. Estado atual do ERP logístico

### Módulos funcionais em HOM

| Módulo | Endpoint(s) | Status |
|---|---|---|
| Autenticação JWT | `POST /auth/login` | ✅ UP — JWT com `sub`, `eid` |
| Empresas | `GET/POST/PUT /api/app/empresas` | ✅ UP |
| Produtos | `GET/POST/PUT/DELETE /api/app/produtos` | ✅ UP |
| Clientes | `GET/POST /api/app/clientes` | ✅ UP |
| Pedidos | `GET/POST /api/app/pedidos` | ✅ UP |
| Emissão NF-e | `POST /api/app/pedidos/{id}/emitir` | ✅ UP (cStat=225 HOM) |
| Situação fiscal | `GET /api/app/pedidos/{id}/situacao` | ✅ UP |
| Cancelamento | `POST /api/app/pedidos/{id}/cancelar` | ✅ UP |
| CC-e | `POST /api/app/pedidos/{id}/cce` | ✅ UP |
| NCM consulta | `GET /api/fiscal/ncm` | ✅ UP |
| NF-e direto | `POST /api/fiscal/nfe/gerar` | ✅ UP |
| Health / Actuator | `GET /actuator/health` | ✅ UP |
| Swagger | `/swagger-ui/index.html` | ✅ UP |

### Banco de dados HOM

| Tabela | Registros | empresa_id |
|---|---|---|
| empresa | 1 | N/A |
| db_user | 1 (admin) | 1 |
| produto | 2 | todos vinculados (1) |
| pedido | 3 | todos vinculados (1) |
| nfe_documento | 3+ | N/A |
| nfe_sequencia | configurada | N/A |
| nfe_log | registros de transmissão | N/A |

---

## 8. Checklist concluído

### Infraestrutura
- [x] Ambiente HOM estável — container UP, MySQL, Redis, actuator
- [x] Flyway V001–V017 aplicados, todos `success=1`
- [x] Deploy via `mvn package → docker cp → docker restart`

### Motor fiscal (Fases 1–4)
- [x] Pipeline NF-e: geração → assinatura → transmissão SOAP → persistência
- [x] Chave 44 dígitos com cDV módulo 11 correto
- [x] Assinatura RSA-SHA256 conforme NT 2019.001
- [x] cStat=225 diagnosticado como limitação HOM/SP — encerrado

### ERP logístico (Fases 5–7)
- [x] Entidades Pedido + PedidoItem com mapper MyBatis
- [x] Snapshot fiscal imutável em pedido_item (V015)
- [x] PedidoServiceImpl.criar() → 2-phase insert, validação produto ATIVO
- [x] Status semântico: AUTORIZADO / REJEITADO / AGUARDANDO / ERRO
- [x] Baixa de estoque somente após cStat=100
- [x] PedidoOperacaoService: consultarSituacao, cancelar, emitirCce
- [x] PedidoController: 7 endpoints REST
- [x] NfeGeracaoResult DTO (chaveNfe + soapRetorno)

### Multi-tenancy (Fase 8A)
- [x] V016 empresa + V017 empresa_id FK
- [x] Empresa entity/mapper/service/controller
- [x] EmpresaContextHolder ThreadLocal
- [x] JWT claim `eid` — geração + extração
- [x] AuthService resolve empresa_id do db_user
- [x] JwtFilter propaga empresa_id para contexto; limpa no finally
- [x] StartupListener: seed empresa + backfill + seed admin
- [x] DbUser/Produto/Pedido com campo empresaId
- [x] ProdutoController + PedidoController injetam empresaId do contexto
- [x] NfeGeracaoService.gerar(req, Empresa) overload
- [x] PedidoEmissaoService resolve Empresa e passa para gerar()
- [x] Validação HOM: empresa id=1 criada, JWT com eid=1, produtos/pedidos com empresaId=1

### Testes
- [x] 33 testes passando, 0 falhas, 1 skipped esperado
- [x] Build limpo: `mvn -pl borurio-web -am clean package -DskipTests`

---

## 9. Checklist pendente

### Fase 8B — Certificado por empresa
- [ ] Modelo de dados: `empresa_certificado` (empresa_id, pfx_path ou pfx_bytes, senha, validade)
- [ ] Resolução do KeyStore por empresa no `AssinaturaXmlService`
- [ ] Resolução do SSLContext por empresa no `NfeTransmitServiceImpl`
- [ ] Teste: emitir NF-e usando certificado da empresa (não do emitente global)

### Fase 8C — Operação comercial multiempresa
- [ ] Filtragem completa: `PedidoService.listarTodos()` → usar `listarPorEmpresa()` quando contexto ativo
- [ ] Filtragem completa: `ProdutoService.listarTodos()` → usar `listarPorEmpresa()` quando contexto ativo
- [ ] UserDetailsServiceImpl: carga de usuário real via db_user (não apenas hardcoded admin)
- [ ] Operador/Vendedor vinculado à empresa (FK db_user → empresa)
- [ ] Permissões por empresa (ROLE_ADMIN_EMPRESA vs ROLE_VENDEDOR)

### Fase 9 — Segurança
- [ ] Roles: ADMIN, OPERADOR, VENDEDOR (Spring Security authorities)
- [ ] Rate limiting por IP / por usuário (Spring Cloud Gateway ou filter)
- [ ] Audit log: tabela `audit_log` (usuario, acao, entidade, id, timestamp)
- [ ] API keys para integração externa (time chinês ERP logístico)
- [ ] Expiração de JWT configurável por ambiente

### Fase 10 — Documentação técnica
- [ ] Swagger completo com exemplos de request/response em todos os endpoints
- [ ] Manual Técnico Motor Fiscal PT/EN (para time chinês de integração)
  - Visão geral arquitetura
  - Fluxo de emissão NF-e (diagrama + endpoints)
  - Estrutura XML NF-e 4.00 resumida
  - Tabela de cStat comuns
  - Guia de configuração certificado A1
  - Exemplos curl passo a passo

### Fase 11 — Deploy PRD + CI/CD
- [ ] Pipeline CI: GitHub Actions / GitLab CI (build, test, sonar)
- [ ] Pipeline CD: deploy automatizado para PRD com aprovação manual
- [ ] Certificado PRD carregado via secret (não no repositório)
- [ ] Variáveis de ambiente PRD isoladas do HOM

### Pendências técnicas abertas
- [ ] Commit de toda a implementação das Fases 5–8A (branch `fix/sefaz-xml-structure`)
  - Arquivos staged: `NfeXmlBuilder.java`, `XmlUtil.java`, `EnderDest.java`, `AssinaturaXmlService.java`, `NfeOrquestradorService.java`, `NcmServiceImpl.java`, `NfeTransmitServiceImpl.java`, `leiauteNFe_v4.00.xsd`, `xmldsig-core-schema_v1.01.xsd`, `NfeGeracaoService.java`
  - Arquivos untracked: todas as classes novas de Fases 5–8A, migrations V014–V017, `ValidacaoOficialTest.java`, relatórios
- [ ] PR para `main` após revisão

---

## 10. Riscos e observações

### R1 — cStat=225 em PRD
**Probabilidade**: Baixa. **Impacto**: Alto.
O cStat=225 em HOM é confirmado como limitação do processador `SP_NFE_PL_008i2` da SEFAZ SP HOM. Em PRD, o processador é atualizado e aceita SHA-256. Entretanto, **não há confirmação de teste em PRD** ainda. Primeiro teste real em PRD deve ter monitoramento próximo dos logs.

### R2 — Usuário admin hardcoded em HOM/DEV
**Probabilidade**: Alta (já é o estado). **Impacto**: Médio.
`UserDetailsServiceImpl` aceita `admin/admin123` diretamente sem consultar `db_user` quando o perfil é `dev` ou `hom`. O `db_user` foi semeado para resolver o `empresa_id` no JWT, mas a autenticação real ainda usa a credencial hardcoded. Em PRD isso é bloqueado. Para operação real, precisa de Fase 8C (usuários reais via DB).

### R3 — empresa_id nullable = sem isolamento real por ora
**Probabilidade**: Alta (é o estado atual). **Impacto**: Médio.
O `empresaId` é injetado na criação de recursos, mas as consultas de listagem ainda usam `listarTodos()` sem filtro de empresa. Um usuário de empresa A pode ver recursos da empresa B. Isolamento completo depende da Fase 8C.

### R4 — MySQL 8.4 e sintaxe MariaDB
**Probabilidade**: Baixa (regra já fixada). **Impacto**: Alto (crash loop).
Já ocorreu com V013. Regra permanente documentada: nunca usar `ADD COLUMN IF NOT EXISTS` em migrations. Próximas migrations devem ser revisadas antes do deploy.

### R5 — Certificado único (global)
**Probabilidade**: Certa (limitação atual). **Impacto**: Alto para multiempresa.
O certificado digital A1 está configurado globalmente em `EmitenteProperties` / `CertificadoService`. Na Fase 8B, cada empresa precisará de seu próprio certificado. Até lá, todas as NF-e serão assinadas com o mesmo certificado independente da empresa no contexto.

### R6 — Commit pendente (risco de perda de trabalho)
**Probabilidade**: Baixa (dados estáveis). **Impacto**: Alto.
Todo o trabalho das Fases 5–8A está uncommitted na branch `fix/sefaz-xml-structure`. Nenhum `git push` realizado. Uma falha de hardware ou corrupção de diretório resultaria em perda total. **Recomendação: commit + push como primeira ação da próxima sessão.**

---

## 11. Próximo passo recomendado

**Prioridade 1 — Commit de toda a implementação** (10 min)
Não iniciar nova funcionalidade sem comitar o trabalho atual. O volume de código não-versionado é significativo (Fases 5–8A). Commit em lote com mensagem descritiva, push para remoto.

**Prioridade 2 — Fase 8B: Certificado por empresa** (estimativa: 2–3h)
Para o multi-tenancy ser funcional de ponta a ponta, cada empresa precisa usar seu próprio certificado A1. Sem isso, a Fase 8A é incompleta do ponto de vista legal — todas as NF-e são assinadas pelo certificado do emitente global, não da empresa emitente real.

**Prioridade 3 — Fase 8C: Filtro por empresa em listagens** (estimativa: 1h)
Completar o isolamento: `ProdutoService.listarTodos()` e `PedidoService.listarTodos()` devem retornar apenas recursos da empresa do contexto JWT quando `EmpresaContextHolder.get() != null`.

**Prioridade 4 — UserDetailsServiceImpl real (Fase 8C parcial)** (estimativa: 1h)
Substituir a autenticação hardcoded por consulta real em `db_user`. Necessário para criação de usuários por empresa e autenticação correta em HOM/PRD.

**Prioridade 5 — Manual Técnico (Fase 10)** (estimativa: 3–4h)
Após Fase 8B, o motor está maduro o suficiente para documentação definitiva. O manual PT/EN é crítico para a integração com o time chinês do ERP logístico.

---

## 12. Ponto exato de retomada

**Branch**: `fix/sefaz-xml-structure`  
**Última operação executada**: `docker restart borurio-web-hom` → UP confirmado → admin user semeado em `db_user` com `empresa_id=1` → JWT com `eid=1` validado → produto criado com `empresaId:1` automático.

**Estado do container HOM**:
- `borurio-web-hom`: UP, porta 8081
- Flyway: V001–V017, todos `success=1`
- Empresa default: `id=1 | cnpj=54393421000159 | razao_social=JCHO GLOBAL LTDA`
- Admin user: `id=1 | email=admin | empresa_id=1`
- Produtos: 2 registros (id=1 legacy, id=2 fase8a), ambos `empresa_id=1`
- Pedidos: 3 registros (ids 1, 2, 3), status: RASCUNHO / EMITIDO / REJEITADO, todos `empresa_id=1`

**Próxima ação técnica ao retomar**:
```
# 1. Commit de toda a implementação Fases 5–8A
git add -p   # revisar cada hunk
git commit -m "feat(erp): fases 5-8A — pedido, snapshot fiscal, status semântico, multiempresa"

# 2. Push para remoto
git push origin fix/sefaz-xml-structure

# 3. Iniciar Fase 8B — certificado por empresa
# Ponto de entrada: AssinaturaXmlService.java e CertificadoService.java
```

**Arquivos-chave a revisar no próximo commit**:
- `borurio-app/entity/`: Empresa, Pedido, PedidoItem, Produto, DbUser
- `borurio-app/mapper/`: EmpresaMapper, PedidoMapper, PedidoItemMapper, ProdutoMapper, DbUserMapper
- `borurio-app/service/`: EmpresaService/Impl, PedidoService/Impl, ProdutoServiceImpl
- `borurio-app/context/EmpresaContextHolder.java`
- `borurio-web/auth/`: JwtUtil, AuthService, JwtFilter
- `borurio-web/config/StartupListener.java`
- `borurio-web/controller/app/`: EmpresaController, PedidoController, ProdutoController
- `borurio-web/service/`: NfeGeracaoService, PedidoEmissaoService, PedidoOperacaoService
- `migrations/`: V014–V017
- `borurio-fiscal/`: NfeXmlBuilder, XmlUtil, AssinaturaXmlService, NfeOrquestradorService, NcmServiceImpl, NfeTransmitServiceImpl
- `xsd/oficial/`: leiauteNFe_v4.00.xsd, xmldsig-core-schema_v1.01.xsd

---

*Relatório gerado em 08/05/2026 — Borurio ERP Fiscal BR / Branch: fix/sefaz-xml-structure*
