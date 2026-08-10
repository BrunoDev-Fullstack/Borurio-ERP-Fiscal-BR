# Checklist de Entrega — Borurio ERP Fiscal BR

| Atributo          | Valor                               |
|-------------------|-------------------------------------|
| Versão            | 1.8                                 |
| Data              | 2026-08-10                          |
| Sprint            | Gate 1 da máquina de estados fiscal de numeração — fechado. Ciclo operacional do nNF (`nfe_emissao`), gate de série ativa, ordem canônica de lock, isolamento multiempresa tenant-null fail-closed e classificação de falha pré-transmissão por fase — todos com suíte de testes verde; **código ainda não commitado**. |
| Ambiente validado | Código revisado e testado contra MySQL efêmero de teste; **não deployado em HOM** — release `4a39a88` continua sendo o último release ativo em HOM |

> Esta revisão registra o fechamento técnico do Gate 1 (10-08-2026), anterior a qualquer commit. A consolidação de 22-07-2026 (P0.1–P0.4, Gate 7H, modFrete) permanece válida e não foi alterada retroativamente — ver seção 8 para o estado de cada documento.

## 0. Estado consolidado (10-08-2026)

**Gate 1 — fechado nesta revisão, código não commitado:**
- Ciclo operacional do nNF (`nfe_emissao`, V033) — número fiscal "em voo" até destino definitivo, substitui o sequenciador simples anterior
- Gate de série ativa (`nfe_sequencia.emissao_ativa_id`, V034) — nenhum número seguinte alocado enquanto o anterior da mesma série não tiver resultado terminal
- Ordem canônica de lock (`nfe_sequencia` → `nfe_emissao`) — deadlock real reproduzido e corrigido contra MySQL
- Isolamento multiempresa tenant-null fail-closed — `JwtFilter` nega (403 `TENANT_REQUIRED`) usuário sem empresa vinculada e sem `ROLE_ADMIN`; ADMIN sem tenant preservado; OMS inalterado
- Classificação de falha pré-transmissão por fase de execução — novo `errorCode LOCAL_PROCESSING_FAILURE`; timeout/conexão continuam conservadores (`PENDENTE_CONFIRMACAO`)
- Correção do contrato de exceção obsoleto em `EstoqueService` (`IllegalStateException` → `BusinessException`) e remoção do `skipTests` hardcoded em `borurio-app`, restaurando execução real da suíte do módulo
- Suítes: `borurio-web` 306/306, `borurio-fiscal` 73/73 (1 skip intencional), `borurio-app` 20/20; P0-1/P0-2/P0-3 validados também contra MySQL real (containers efêmeros, descartados após o teste); `git diff --check` limpo
- **Nenhum commit/push realizado** — commit manual pendente de revisão final do pacote (código + documentação)

**Backlog funcional CC — 5 itens solicitados pelo integrador chinês, todos PENDENTES (nenhum concluído):**
1. Numeração + retorno de `serie`/`numeroNFe` no `/emitir`/`/situacao` — fundação do Gate 1 pronta; falta Gate 2 (classificação de cStat), Gate 3 (reconciliação) e Gate 5 (retorno ao contrato)
2. Correção do fluxo de cancelamento — interpretação de `cStat`/`xMotivo`, idempotência
3. CC-e — interpretação completa do retorno SEFAZ antes da rodada de integração
4. Configuração de estoque para o cenário do CC (`controleEstoqueAtivo=false` na empresa específica)
5. Teste de contingência fiscal formal

**Estado consolidado de 22-07-2026 (preservado, não alterado nesta revisão):**

**Concluído internamente:**
- Implementação dos requisitos informados pelo integrador chinês (CC)
- Build e deploy do release `4a39a88` em HOM
- Migration V032 aplicada, `flyway_schema_history` sem falhas
- Health HOM `UP`, `RestartCount=0`
- Gate 7H — revogação/rotação global de autorização OMS (auditoria append-only, controle otimista por versão, idempotência)
- Rotação da autorização real do integrador OMS — token anterior invalidado, token novo ativo
- `modFrete` explícito por fluxo — OMS/marketplace = 2 (Terceiros), legado = 9 (Sem Transporte)
- Nova NF-e autorizada em HOM (`cStat=100`) com `modFrete=2` confirmado no XML transmitido — evidência de que motor fiscal e resolução multi-CNPJ estão funcionais
- Cancelamento, CC-e, inutilização e consulta de situação — implementados e cobertos por teste automatizado (ver seção 1.3)
- Validação interna ponta a ponta (299/299 testes automatizados + validação funcional em HOM real)

**Backlog funcional CC/HOM (pendente):**
- Entrega controlada do token OMS novo ao CC
- Testes externos do CC em HOM — autenticação, consulta de pedidos e produtos, emissão, comportamento multi-CNPJ
- Aceite final da integração pelo CC
- **Pendência cadastral fiscal:** a empresa-âncora do token OMS do CC tem Inscrição Estadual não aceita pela SEFAZ; uma emissão real em HOM para essa empresa chegou à SEFAZ e retornou `cStat=209` (IE do emitente inválida) — não foi falha de autenticação, XML, assinatura, transmissão nem da arquitetura multiempresa, é pendência cadastral externa ao código. O cadastro fiscal deve ser confirmado e, se necessário, corrigido pelo responsável fiscal da empresa junto à SEFAZ — segue como item aberto
- Uso temporário de uma empresa alternativa vinculada ao mesmo token, com IE aceita pela SEFAZ nessa emissão em HOM, para os testes de emissão enquanto a pendência acima não é resolvida
- Validação SEFAZ de cancelamento, CC-e, inutilização e consulta em contexto multi-CNPJ (ver seção 1.3)

**Pendente pré-produção (Gate 10, não bloqueia os testes do CC):**
- Troca das credenciais administrativas expostas durante a sessão de trabalho
- Rotação do `SECURITY_JWT_SECRET` de HOM
- Remoção do segredo hardcoded do `application-dev.yml`
- Gestão externa de segredos (secrets manager)
- Revisão final de observabilidade
- Plano de rollback de produção
- Validação de série e numeração inicial de produção
- Checklist formal de go-live

**Roadmap funcional futuro (não bloqueia o primeiro go-live):**
- Devolução, transferência, remessa, bonificação — tipos de operação ainda não implementados, sem urgência confirmada pelo CC
- Demais evoluções fiscais futuras identificadas (parametrização de frete por canal, dashboards/relatórios, Reforma Tributária IBS/CBS/IS)

> **Importante:** o sistema não está em produção. O planejamento de go-live só se inicia após a validação final do CC.

---

## 1. Motor fiscal NF-e 4.00

### 1.1 Geração e transmissão

| Item                                                                                        | Estado      |
|----------------------------------------------------------------------------------------------|-------------|
| Geração de XML NF-e 4.00 completa (`ide`, `emit`, `dest`, `det`, `total`, `transp`, `pag`)   | CONCLUÍDO   |
| Validação XSD pré-assinatura (`nfe_v4.00_consolidado.xsd`)                                   | CONCLUÍDO   |
| Assinatura XMLDSig RSA-SHA1 + C14N (conforme schema oficial vigente)                          | CONCLUÍDO — `cStat=100` obtido em HOM/SP em 14-07-2026 |
| Envelope SOAP 1.2 com `indSinc=1` (síncrono)                                                  | CONCLUÍDO   |
| Autenticação mTLS com certificado A1 PKCS12                                                   | CONCLUÍDO   |
| Chave de acesso 44 dígitos com dígito verificador módulo 11                                   | CONCLUÍDO   |

### 1.2 Numeração e concorrência

| Item                                                                                                                     | Estado                                     |
|----------------------------------------------------------------------------------------------------------------------------|---------------------------------------------|
| Sequenciador atômico de nNF por CNPJ + série                                                                              | CONCLUÍDO |
| Baseline seguro de numeração por CNPJ e série — inicialização idempotente; erro explícito em valor divergente (não avança nem regride silenciosamente) | CONCLUÍDO — validado em HOM/SP em 22-07-2026 (duas séries/CNPJ distintas avançadas corretamente em emissões reais sequenciais, sem colisão) |
| Proteção contra emissão concorrente duplicada por pedido — claim atômico, resposta `HTTP 409 EMISSAO_EM_ANDAMENTO` controlada para a chamada que perde a corrida | CONCLUÍDO — validado por teste automatizado (2 e 10 threads reais); código exercitado com sucesso em emissões reais sequenciais em 22-07-2026 |
| **Gate 1 (10-08-2026) — ciclo operacional do nNF (`nfe_emissao`) + gate de série ativa (`nfe_sequencia.emissao_ativa_id`)** — nenhum número seguinte é alocado enquanto o anterior da mesma série não tiver destino definitivo | CÓDIGO PRONTO, TESTADO CONTRA MYSQL REAL EFÊMERO — **não commitado, não deployado em HOM** |
| **Ordem canônica de lock (`nfe_sequencia` → `nfe_emissao`)** — deadlock real reproduzido e corrigido contra MySQL (9 cenários) | CÓDIGO PRONTO, TESTADO CONTRA MYSQL REAL EFÊMERO — **não commitado, não deployado em HOM** |
| **Classificação de falha pré-transmissão por fase de execução** — `errorCode LOCAL_PROCESSING_FAILURE` para falha comprovadamente local; timeout/conexão continuam `PENDENTE_CONFIRMACAO` | CÓDIGO PRONTO, TESTADO — **não commitado, não deployado em HOM** |
| Isolamento multiempresa tenant-null fail-closed (`JwtFilter`, `errorCode TENANT_REQUIRED`) | CÓDIGO PRONTO, TESTADO CONTRA MYSQL REAL EFÊMERO — **não commitado, não deployado em HOM** |
| Reconciliação ativa de resultado incerto (Gate 3) e classificação semântica definitiva de `cStat` (Gate 2) | NÃO IMPLEMENTADO — posterior ao commit do Gate 1 |
| Retorno de `serie`/`numeroNFe` no `/emitir`/`/situacao` (Gate 5) | NÃO IMPLEMENTADO — posterior aos Gates 2/3 |

### 1.3 Eventos pós-emissão

| Item                                                                                                    | Estado                     |
|-----------------------------------------------------------------------------------------------------------|-----------------------------|
| Cancelamento NF-e (evento 110111)                                                                         | IMPLEMENTADO E TESTADO INTERNAMENTE — pendente revalidação SEFAZ multi-CNPJ |
| Carta de Correção — CC-e (evento 110110)                                                                  | IMPLEMENTADO E TESTADO INTERNAMENTE — pendente revalidação SEFAZ multi-CNPJ |
| Inutilização de numeração (`NfeInutilizacaoController`)                                                    | IMPLEMENTADO E TESTADO INTERNAMENTE — pendente revalidação SEFAZ multi-CNPJ |
| Consulta de situação (`consSitNFe`)                                                                       | IMPLEMENTADO E TESTADO INTERNAMENTE — pendente revalidação SEFAZ multi-CNPJ |
| Manifestação do Destinatário (eventos 210200/210210/210220/210240 — NT 2012.004)                         | CONCLUÍDO — entregue 26-05-2026 |
| Contexto fiscal correto por empresa/CNPJ nos eventos acima — empresa, certificado e UF resolvidos pelo CNPJ real da operação, sem fallback silencioso para configuração global; CNPJ da chave de acesso validado contra o contexto resolvido | IMPLEMENTADO E TESTADO INTERNAMENTE — pendente revalidação SEFAZ multi-CNPJ |

**Cancelamento, CC-e, inutilização e consulta de situação já estão implementados e cobertos por testes automatizados** (`PedidoOperacaoServiceTest`, `NfeInutilizacaoControllerTest`, execução incluída no reactor atual — ver seção 6). Permanece pendente a revalidação desses fluxos contra a SEFAZ real especificamente em contexto multi-CNPJ: confirmação de que a seleção de empresa/certificado nesses eventos funciona ponta a ponta com uma NF-e emitida por uma empresa diferente da empresa-âncora do token — o mesmo padrão já comprovado na emissão (ver seção 1.5, autorização `cStat=100` via `cnpjEmitente` de uma empresa vinculada ao token com cadastro fiscal aceito pela SEFAZ), mas ainda não exercitado especificamente para cancelamento/CC-e/inutilização/consulta.

### 1.4 indFinal / indIntermed

| Item                                                                                                     | Estado                                    |
|--------------------------------------------------------------------------------------------------------|---------------------------------------------|
| `indFinal` configurável por empresa emitente — padrão `"1"`, override `"0"`/`"1"` por empresa, fallback de compatibilidade para empresa nula/campo ausente, falha explícita para valor fora do domínio | CONCLUÍDO — não depende mais do documento (CPF/CNPJ) do destinatário |
| `indIntermed` presente e serializado no XML para o fluxo atual — escopo: operação de venda direta, `indIntermed="0"`, tag presente no `<ide>`, nenhuma alteração no contrato OMS | CONCLUÍDO — validado em HOM/SP em 22-07-2026; não representa regra universal para toda NF-e |
| Modalidade de frete (`modFrete`) explícita por fluxo — OMS/marketplace = 2 (Terceiros), legado = 9 (Sem Transporte) | CONCLUÍDO — validado em HOM/SP em 22-07-2026, confirmado no XML autorizado (`cStat=100`) |
| Revogação e rotação global de autorização/token OMS (Gate 7H) — auditoria append-only, controle otimista por versão, idempotência | CONCLUÍDO — validado em HOM/SP em 22-07-2026 |
| Marketplace / plataforma de terceiro (origem da venda por pedido, `indIntermed="1"`, grupo `infIntermed` com CNPJ do intermediador e identificador da operação) | FORA DO ESCOPO ATUAL / EVOLUÇÃO FUTURA — depende de definição de negócio e possível evolução do contrato OMS |

### 1.5 Snapshot fiscal imutável

| Item                                                                                          | Estado    |
|-----------------------------------------------------------------------------------------------|-----------|
| Cópia de `codigo`, `descricao`, `ncm`, `cfop`, `unidade`, `origem`, `csosn` no item do pedido | CONCLUÍDO |
| `csosn` default `"400"` quando nulo no produto                                                | CONCLUÍDO |
| `origem` default `0` quando nulo no produto                                                   | CONCLUÍDO |
| Snapshot congelado no momento da criação do pedido                                            | CONCLUÍDO |

### 1.6 Auditoria fiscal

| Item                                                            | Estado    |
|-------------------------------------------------------------------|-----------|
| Tabela `nfe_documento` com estado persistido de cada NF-e         | CONCLUÍDO |
| Tabela `nfe_log` com eventos `ENVIO_NFE` e `TRANSMISSAO_SEFAZ`    | CONCLUÍDO |
| Falhas de log não interrompem o fluxo fiscal                      | CONCLUÍDO |

### 1.7 Estoque mínimo fiscal

| Item                                                                                     | Estado    |
|-------------------------------------------------------------------------------------------|-----------|
| Reserva atômica de estoque antes da transmissão SEFAZ (lança 422 se insuficiente)         | CONCLUÍDO |
| Baixa definitiva após `cStat=100` (AUTORIZADO)                                            | CONCLUÍDO |
| Desfazer reserva após REJEITADO ou ERRO (nunca mascara resultado SEFAZ)                   | CONCLUÍDO |
| Manutenção da reserva em AGUARDANDO (liberação manual ou novo ciclo)                      | CONCLUÍDO |
| Estorno de baixa após CANCELADO (`estoque += qtd` auditado)                               | CONCLUÍDO |
| Entrada manual de estoque via `POST /api/app/produtos/{id}/estoque/entrada` [ADMIN]       | CONCLUÍDO |
| Consulta de saldo em tempo real via `GET /api/app/produtos/{id}/estoque`                  | CONCLUÍDO |
| Tabela `estoque_movimento` — auditoria completa de todos os movimentos                    | CONCLUÍDO |
| Isolamento multiempresa — `empresa_id` em todos os UPDATEs atômicos                       | CONCLUÍDO |
| Controle de estoque opcional por empresa (`controleEstoqueAtivo`)                         | CONCLUÍDO — entregue 10-07-2026 |

---

## 2. Integração OMS

### 2.1 API REST

| Módulo                         | Endpoint base                                 | Estado                                     |
|--------------------------------|------------------------------------------------|--------------------------------------------|
| Ping / health check            | `GET /api/test/ping`                           | CONCLUÍDO |
| Autenticação                   | `POST /auth/login`                             | CONCLUÍDO |
| Autorização Fiscal OMS         | `POST /api/integration/fiscal-authorizations`  | CONCLUÍDO — entregue 22-06-2026 (V028 multi-CNPJ) |
| Empresas                       | `/api/app/empresas`                            | CONCLUÍDO |
| Produtos                       | `/api/app/produtos`                            | CONCLUÍDO |
| Estoque de produtos            | `/api/app/produtos/{id}/estoque`               | CONCLUÍDO — entregue 18-05-2026 |
| Clientes                       | `/api/app/clientes`                            | CONCLUÍDO |
| Pedidos + ciclo fiscal         | `/api/app/pedidos`                             | CONCLUÍDO |
| Usuários                       | `/api/app/usuarios`                            | CONCLUÍDO |
| Logs fiscais                   | `/api/fiscal/nfe/logs`                         | CONCLUÍDO |
| NCM                            | `/api/fiscal/ncm`                              | CONCLUÍDO |
| DANFE (PDF)                    | `/api/fiscal/nfe/{chave}/danfe`                | CONCLUÍDO — entregue 18-05-2026 |
| Manifestação Destinatário      | `POST /api/fiscal/nfe/manifestar`              | CONCLUÍDO — entregue 26-05-2026 |
| NF-e legado (deprecated)       | `/api/fiscal/nfe`                              | Mantido por compatibilidade — não usar em integrações novas |

### 2.2 O que o time chinês precisa consumir / integrar

| Ação                                                                                                       | Responsável       | Status   |
|--------------------------------------------------------------------------------------------------------------|--------------------|----------|
| Ler o contrato de integração vigente (ver seção 8 quanto à versão)                                          | Time chinês        | PENDENTE TÉCNICO |
| Receber `X-Api-Key` OMS via canal seguro                                                                    | Operações          | PENDENTE TÉCNICO |
| Executar `POST /api/integration/fiscal-authorizations` para cada CNPJ emitente                              | Time chinês        | PENDENTE TÉCNICO |
| Executar smoke test multi-CNPJ em HOM (produto, pedido, emissão, eventos pós-emissão)                       | Time chinês        | Emissão validada internamente em 22-07-2026 (`cStat=100`); execução pelo próprio CC e eventos pós-emissão em contexto multi-CNPJ seguem PENDENTE TÉCNICO |
| Adaptar OMS para a sequência: autorização OMS → produto → pedido (com `cnpjEmitente`) → emitir → situação    | Time chinês        | PENDENTE TÉCNICO |
| Implementar renovação de token ao aproximar-se do `tokenExpiraEm` (data do certificado A1)                  | Time chinês        | PENDENTE TÉCNICO |
| Tratar máquina de estados do pedido: `RASCUNHO`, `AUTORIZADO`, `AGUARDANDO`, `REJEITADO`, `ERRO`, `CANCELADO` | Time chinês        | PENDENTE TÉCNICO |
| Tratar `HTTP 403 CNPJ_NOT_AUTHORIZED` na criação de pedidos                                                  | Time chinês        | PENDENTE TÉCNICO |
| Mapear campos da OMS para os payloads validados do contrato de integração vigente                            | Time chinês        | PENDENTE TÉCNICO |

---

## 3. Multiempresa

| Item                                                                                                              | Estado                     |
|------------------------------------------------------------------------------------------------------------------|-----------------------------|
| Isolamento de dados por `empresa_id` em `produto`, `pedido`, `nfe_log`                                           | CONCLUÍDO |
| `empresa_id` extraído do JWT via ThreadLocal — não enviado no body                                               | CONCLUÍDO |
| Certificado A1 por empresa com cache em memória                                                                  | CONCLUÍDO |
| Criptografia AES-256-GCM para senha do certificado                                                               | CONCLUÍDO — passthrough em HOM |
| Multi-CNPJ OMS — token por cliente OMS; múltiplos CNPJs sob o mesmo token                                        | CONCLUÍDO — entregue 22-06-2026 |
| Auto-criação de empresa a partir do Subject X.509 na autorização OMS                                             | CONCLUÍDO — entregue 22-06-2026 |
| Validação `cnpjEmitente` OMS em `POST /pedidos` — fail-fast antes de persistir                                   | CONCLUÍDO — entregue 22-06-2026 |
| Token determinístico — `emitidoEm` truncado a segundos garante token idêntico em reautorizações                 | CONCLUÍDO — entregue 22-06-2026 |
| Estoque vinculado à empresa-âncora do pedido, distinta da empresa fiscal do CNPJ emitente                        | CONCLUÍDO — entregue 30-06-2026 |
| Endereço do emitente completado automaticamente via `POST /pedidos` quando o cadastro estiver incompleto         | CONCLUÍDO — entregue 10-07-2026 |
| Bloqueio de `/emitir` antes da SEFAZ quando o endereço do emitente está incompleto (`EMITTER_ADDRESS_INCOMPLETE`) | CONCLUÍDO — entregue 10-07-2026 |
| Contexto fiscal resolvido pela empresa emitente real (não pela empresa-âncora nem por configuração global) na emissão | CONCLUÍDO — validado em HOM/SP em 22-07-2026 (autorização real multi-CNPJ, `cStat=100`, empresa resolvida por `cnpjEmitente` distinta da empresa-âncora). Cobertura restrita à emissão — eventos pós-emissão (cancelamento, CC-e, consulta, inutilização) seguem no item abaixo, ainda não exercitados contra a SEFAZ real em contexto multi-CNPJ |
| Certificado e UF vinculados ao CNPJ correto em toda operação pós-emissão                                         | IMPLEMENTADO, AGUARDANDO VALIDAÇÃO EM HOM |
| Numeração isolada por CNPJ e série                                                                               | CONCLUÍDO — validado em HOM/SP em 22-07-2026 (duas séries distintas avançadas corretamente, sem colisão) |
| Baseline de numeração protegido contra sobrescrita divergente (erro explícito, sem avanço/regressão silenciosa) | IMPLEMENTADO, AGUARDANDO VALIDAÇÃO EM HOM |
| `indFinal` configurável por empresa emitente                                                                     | CONCLUÍDO — ver seção 1.4 |
| Smoke test multi-CNPJ real em HOM — emissão e baseline de numeração | CONCLUÍDO — validado em HOM/SP em 22-07-2026 (`cStat=100`) |
| Smoke test multi-CNPJ real em HOM — cancelamento, CC-e, consulta, inutilização                                  | Pendente — não executado |

---

## 4. Banco e migrations

| Item                                                              | Estado                                                   |
|--------------------------------------------------------------------|-----------------------------------------------------------|
| Migrations Flyway V001–V028                                        | CONCLUÍDO — aplicadas em HOM (V028 com reparo manual em 22-06-2026) |
| Migration V029 — controle de estoque opcional por empresa          | CONCLUÍDO — aplicada em HOM, confirmada em 22-07-2026 (`flyway_schema_history`, 32 migrations, 0 falhas) |
| Migration V030 — `ind_final_padrao` em `empresa` (`CHAR(1) NOT NULL DEFAULT '1'`) | CONCLUÍDO — aplicada em HOM, confirmada em 22-07-2026 |
| Migration V031 — auditoria de sincronização fiscal (`nfe_sequencia_auditoria`) | CONCLUÍDO — aplicada em HOM, confirmada em 22-07-2026 |
| Migration V032 — revogação/rotação de autorização OMS (`oms_fiscal_authorization_audit`, coluna `versao`) | CONCLUÍDO — aplicada em HOM em 22-07-2026, Flyway em v032, 0 falhas |

---

## 5. Segurança

| Item                                                       | Estado    |
|--------------------------------------------------------------|-----------|
| JWT stateless HMAC-SHA256, validade 1h                      | CONCLUÍDO |
| RBAC com roles `ADMIN` e `OPERADOR`                          | CONCLUÍDO |
| Respostas 401/403 em JSON estruturado                        | CONCLUÍDO |
| Proteção anti-XXE em parsers XML                             | CONCLUÍDO |
| `SecurityConfig` com dois `SecurityFilterChain` separados     | CONCLUÍDO |
| Criptografia AES-256-GCM para senha do certificado            | CONCLUÍDO — passthrough em HOM |

> Pendências de segurança específicas para liberação de PRD estão consolidadas na seção 9, não repetidas aqui.

---

## 6. Testes automatizados

| Item                                                                                                                        | Estado                    |
|-------------------------------------------------------------------------------------------------------------------------------|----------------------------|
| Última execução local registrada em 22/07/2026 (`mvn test`, reactor completo) — resultado: 299 testes aprovados, 1 teste ignorado preexistente (não relacionado) | Registrado — não substitui migration em MySQL real, smoke test em HOM, uso de certificado real de homologação nem validação contra SEFAZ (essa validação contra SEFAZ real ocorreu separadamente em HOM, `cStat=100`, ver seção 1.5) |
| Última execução registrada em 10/08/2026 (Gate 1) — `borurio-web` 306/306, `borurio-fiscal` 73/73 (1 skip intencional preexistente, não relacionado), `borurio-app` 20/20; P0-1/P0-2/P0-3 validados também contra MySQL real (container efêmero de teste, descartado após a banca) | Registrado — código ainda **não commitado**; não substitui deploy/smoke test em HOM |
| Cenários de autorização OMS cobertos em `OmsFiscalAuthorizationServiceTest`                                                   | CONCLUÍDO — entregue 22-06-2026 |
| Validação `cnpjEmitente` OMS em `PedidoControllerTest`                                                                        | CONCLUÍDO — entregue 22-06-2026 |
| Endpoints deprecated cobertos em `NfeEnvioControllerTest`                                                                     | CONCLUÍDO — entregue 22-06-2026 |
| Reemissão REJEITADO/ERRO — `PedidoEmissaoServiceTest`                                                                         | CONCLUÍDO — entregue 10-07-2026 |
| Endereço do emitente via pedido — `PedidoControllerTest`                                                                      | CONCLUÍDO — entregue 10-07-2026 |
| `errorCode`/`retryable` — `GlobalExceptionHandlerTest`, `NfeGeracaoServiceTest`                                              | CONCLUÍDO — entregue 10-07-2026 |
| Concorrência de emissão (2 e 10 threads reais) — `PedidoEmissaoServiceTest`                                                   | CONCLUÍDO |
| Contexto fiscal multi-CNPJ — `PedidoOperacaoServiceTest`, `NfeInutilizacaoControllerTest`                                     | CONCLUÍDO |
| Baseline de numeração — `NfeSequenciaServiceTest` (concorrência determinística, lock em memória)                              | CONCLUÍDO |
| `indFinal` configurável por empresa — `NfeGeracaoServiceTest`                                                                 | CONCLUÍDO |
| Contexto WebMvc isolado por módulo — MockitoExtension para serviços                                                           | CONCLUÍDO |

> Contagens históricas anteriores (163, 167, 190) não devem ser reutilizadas como estado atual — cada uma corresponde a um ponto diferente do desenvolvimento. A contagem válida é a última execução registrada nesta tabela.

---

## 7. Validação em HOM

`cStat=225` deixou de ser o comportamento esperado. A causa raiz foi identificada e corrigida: o motor assinava o XML com RSA-SHA256, mas o schema XMLDSig oficial vigente da SEFAZ (`xmldsig-core-schema_v1.01.xsd`, confirmado no pacote oficial `PL_010e_v1.02`) exige RSA-SHA1/SHA-1.

**Resultado:** `cStat=100` (Autorizado o uso da NF-e) obtido em HOM/SP para a empresa homologada atual (vinculada ao token, com IE aceita pela SEFAZ), em teste interno e em teste do integrador chinês via OMS, com confirmação cruzada no portal público (hom.nfe.fazenda.gov.br), em 14-07-2026. O campo foi incluído após a rejeição `434`, e o fluxo posteriormente alcançou `cStat=100`.

**Cadastro histórico do emitente** (mantido no sistema desde etapas anteriores do projeto, não apto para emissão atual) segue bloqueado em HOM — IE cassada por inatividade desde 2024, pendência cadastral externa, não corrigível por código.

**Bug de dado já corrigido em 10-07-2026:** cadastro de emitente sem endereço (empresa auto-criada via OMS). Bloqueado preventivamente via `EMITTER_ADDRESS_INCOMPLETE` antes de chamar a SEFAZ.

**Nova autorização real em 22-07-2026:** com o release `4a39a88` (Gate 7H + modFrete) já ativo em HOM, uma nova NF-e foi autorizada pela SEFAZ-SP (`cStat=100`) usando uma empresa vinculada ao token com cadastro fiscal aceito pela SEFAZ, confirmando `<modFrete>2</modFrete>` no XML efetivamente transmitido. Essa autorização real cobre o código vigente na data — incluindo a proteção contra emissão concorrente (seção 1.2), o contexto fiscal multi-CNPJ nos eventos pós-emissão (seções 1.3 e 3), o baseline de numeração (seção 1.2), o `indFinal` configurável por empresa (seção 1.4) e o `indIntermed`, todos presentes no release testado.

Validação em HOM: se `cStat=225` ainda ocorrer, tratar como rejeição real e verificar `data.xMotivo`. `cStat≥200` retorna HTTP 422 `SEFAZ_REJECTED` (não HTTP 200).

---

## 8. Documentação

| Documento                                                          | Estado          |
|----------------------------------------------------------------------|------------------|
| Manual técnico motor fiscal PT-BR (`MTF-001_motor-fiscal-nfe.md`)    | CONCLUÍDO — v3.2, atualizado em 10-08-2026 (Gate 1: seções 4.4, 5.9, 7.5, 9.3, 9.7, 11.2a) |
| Manual técnico motor fiscal EN (`MTF-001_motor-fiscal-nfe_EN.md`)    | PENDENTE — backfill da v3.0 (22-07-2026) já registrado como pendente; v3.2 (Gate 1, 10-08-2026) também pendente. Não atualizado nesta revisão — PT-BR é a versão canônica, EN segue em catch-up separado |
| Contrato de integração PT-BR (`INTEGRATION_CONTRACT_PT-BR.md`)      | CONCLUÍDO — v1.12, atualizado em 10-08-2026 (novo `errorCode LOCAL_PROCESSING_FAILURE`, seção 6.4/8.2a) |
| Contrato de integração EN (`INTEGRATION_CONTRACT_EN.md`)            | PENDENTE — v1.11 catch-up de 22-07-2026 continua sendo a última versão EN; v1.12 (10-08-2026) não portada nesta revisão |
| Checklist onboarding OMS chinesa (`CHECKLIST_OMS_ONBOARDING.md`)    | CONCLUÍDO — v1.15, atualizado em 10-08-2026 (nota de `LOCAL_PROCESSING_FAILURE` no Bloco 5) |
| FAQ Smoke Test OMS (`FAQ_SMOKE_TEST_OMS.md`)                         | CONCLUÍDO — consolidado em 22-07-2026 |
| Roteiro de entrega ao time chinês (`ROTEIRO_ENTREGA_TIME_CHINES.md`) | PENDENTE TÉCNICO — fora do escopo desta consolidação (22-07-2026) |
| Postman collection                                                    | PENDENTE TÉCNICO — atualização para os endpoints/campos mais recentes não confirmada |
| Swagger UI                                                            | CONCLUÍDO — operacional em DEV e HOM |
| Diagramas arquiteturais                                               | PENDENTE TÉCNICO — atualização não confirmada nesta consolidação |

---

## 9. Bloqueadores para PRD

### 9.1 Pendências que não bloqueiam integração HOM

| Item                                                                                    | Prioridade |
|--------------------------------------------------------------------------------------------|------------|
| CI/CD automatizado (GitHub Actions → deploy HOM → smoke test)                              | Médio      |
| Atualização da Postman collection para os endpoints e campos mais recentes                 | Médio      |
| Smoke test com certificado A1 real de um segundo CNPJ (depende de certificado disponível)  | Baixo      |

### 9.2 Bloqueadores críticos

| Bloqueador                                                                                                  | Prioridade                            | Responsável        |
|-----------------------------------------------------------------------------------------------------------|----------------------------------------|---------------------|
| Teste controlado em produção (operação fiscal real, passível de cancelamento, acompanhado pelo contador)   | Crítico — bloqueante para go-live      | Bruno / Contador     |
| Certificados A1 de produção com CNPJ real (`tpAmb=1`)                                                       | Crítico                                | Bruno / Operações    |
| `CERT_ENCRYPTION_KEY` configurada em PRD por mecanismo seguro de gestão de segredos, fora do repositório e dos arquivos versionados | Crítico | Bruno / Operações    |
| URL de PRD definida e acessível externamente                                                                | Crítico                                | Operações            |
| Retirar segredos de arquivos versionados                                                                    | Crítico                                | Bruno                |
| Rotacionar credenciais antes de PRD                                                                          | Crítico                                | Bruno                |
| Revisar sanitização de logs para impedir exposição de XML fiscal completo, headers de autenticação, senhas, certificados, tokens e demais dados sensíveis | Crítico | Bruno |
| Isolar certificados por empresa (armazenamento e acesso)                                                     | Crítico                                | Bruno / Operações    |
| Revisar permissões de acesso ao ambiente de produção                                                        | Crítico                                | Operações            |
| Validar procedimento de rollback                                                                             | Crítico                                | Bruno / Operações    |
| Primeira emissão controlada                                                                                  | Crítico — bloqueante para go-live      | Bruno / Contador     |
| Validação fiscal e contábil da operação                                                                     | Crítico — bloqueante para go-live      | Contador / Responsável tributário |
| Definir e implementar estratégia de contingência fiscal formal, avaliando SVC-AN, SVC-RS e EPEC conforme UF, operação e documentação oficial vigente | Crítico — P1 pré-produção | Bruno / Responsável fiscal |
| Documentar critérios de ativação da contingência                                                            | Crítico                                | Bruno                |
| Documentar reconciliação posterior a contingência                                                            | Crítico                                | Bruno                |
| Testar estados incertos (falha de comunicação com a SEFAZ após transmissão)                                 | Crítico                                | Bruno                |
| Validar a estratégia de contingência com o responsável fiscal                                                | Crítico                                | Contador / Responsável tributário |
| Definição de negócio para `indIntermed` em cenário de marketplace/plataforma de terceiro (ver seção 1.4)     | Bloqueante apenas para esse cenário — não bloqueia venda direta | Bruno / Time chinês / Responsável fiscal |

**Escopo técnico da contingência fiscal formal:** controlar `tpEmis`, `dhCont` e `xJust`; selecionar o autorizador adequado; preservar série, número e chave de acesso; tratar estado incerto após a transmissão (consultar situação antes de nova tentativa, evitando autorização duplicada); persistir XML e estado da emissão; reconciliar posteriormente com a SEFAZ; implementar observabilidade, auditoria e testes unitários, de integração e homologação.

**Impacto na homologação OMS:** este item não bloqueia os testes atuais do CC e não exige alteração imediata no contrato. Enquanto a contingência fiscal formal não estiver implementada, o OMS continua tratando `HTTP 503` com `retryable=true` e repetindo `POST /api/app/pedidos/{id}/emitir` no mesmo pedido.

> `cStat=100` obtido em HOM/SP em 14-07-2026 confirma conformidade de schema/assinatura, mas **não substitui** a validação em produção — número de série real, comportamento do certificado PRD e o ciclo fiscal completo perante o contador só podem ser confirmados com uma emissão real controlada.

---

## 10. Critérios finais de aceite

Antes de considerar a entrega do motor fiscal encerrada para produção, os itens abaixo devem estar satisfeitos:

- [ ] Todos os itens da seção 9.2 concluídos.
- [ ] Smoke test multi-CNPJ real em HOM executado para: emissão, cancelamento, CC-e, consulta, inutilização e baseline de numeração.
- [ ] Migrations V029 e V030 confirmadas aplicadas em HOM.
- [ ] Documentação das seções 8 individualmente revisada e com versões consistentes entre si.
- [ ] Definição de negócio para `indIntermed` obtida e, se aplicável, implementada — ou registrada como fora de escopo formalmente aceito para o lançamento inicial.
- [ ] Bloco estrutural de `indIntermed` formalizado em commit.
- [ ] Teste controlado em produção realizado e validado pelo contador.
