# RELATÓRIO TÉCNICO DIÁRIO — 11/08/2026

**Projeto:** Borurio ERP Fiscal BR

**Branch:** `fix/sefaz-xml-structure`

**Responsável técnico:** Bruno Ribeiro

---

## 1. Objetivo do trabalho de hoje

Fechar a última prova pendente do Gate 3 (reconciliação fiscal) contra MySQL real, consolidar o commit manual do Gate 2 + Gate 3, e implementar o item que o CC pediu com mais frequência nas últimas semanas: o retorno estruturado de série, número da NF-e e resultado fiscal para a OMS em `/emitir` e `/situacao`. Cancelamento, CC-e, configuração de estoque do cenário do CC e contingência formal ficam fora do escopo de hoje — retomo amanhã, nessa ordem.

---

## 2. Retomada pós-reboot — prova final do Gate 3 contra MySQL real

O bloqueio de Docker/WSL2 registrado no encerramento de 10/08/2026 não se repetiu após o reboot. Confirmei `docker version` com `Client` e `Server` respondendo antes de qualquer ação.

Subi um MySQL 8.4 efêmero (container `borurio-mysql-gate3-ephemeral`, porta 3499, nunca 3307/3308/dev/hom) e rodei `Gate3ReconciliacaoRealMySqlIT`: **3/3 verde** — claim atômico da janela de reconciliação sob 10 chamadas concorrentes (exatamente uma vence) e exactly-once de `resolverCicloComEfeitos` sob 5 chamadas concorrentes, tanto para o desfecho `AUTORIZADO` quanto para `NUMERO_OCUPADO` (número queimado nunca reaproveitado, próximo ciclo usa o número seguinte).

Destruí o container (`docker rm -f`) imediatamente após a prova. `borurio-mysql-dev` e `borurio-mysql-hom` (junto com `borurio-web-dev`, `borurio-web-hom`, `borurio-minio-dev/hom`, `borurio-redis-dev/hom`) permaneceram no ar e intocados o tempo todo — confirmado por `docker ps` antes e depois.

Regressão completa revalidada de forma independente antes de declarar o Gate 3 fechado: `borurio-app` 20/20, `borurio-fiscal` 87/88 (1 skip pré-existente), `borurio-web` 369/369.

---

## 3. Commit do Gate 2 + Gate 3

Com a banca completa (MySQL real 3/3 + regressão 369/369), montei o pacote do commit único de Gate 2 + Gate 3 — diffs tecnicamente entrelaçados demais para separar. Conferência de praxe antes do commit: `git status --short`, `git diff --cached --check`, `git diff --cached --stat`, `git diff --cached --name-only`, varredura por segredo/senha/certificado/`.env` nos arquivos candidatos — tudo limpo.

Realizei o commit manual:

```
620005e — feat(fiscal): implementa semantica SEFAZ e reconciliacao da NF-e
```

28 arquivos, 2544 insertions / 136 deletions. Consolida a classificação explícita de `cStat` (Gate 2), o suporte ao `cStat=150` no DANFE, a consulta de situação estruturada e a reconciliação de resultados incertos (Gate 3), o estado terminal `NUMERO_OCUPADO`, o backoff persistente, o claim atômico de consulta e a finalização transacional/idempotente entre ciclo fiscal, sequência, Pedido e estoque. Os 19 documentos históricos de `docs/report/` (julho/início de agosto) permaneceram fora do commit, como já vinha sendo o padrão.

`git log -2 --oneline` no fechamento desta etapa:
```
620005e feat(fiscal): implementa semantica SEFAZ e reconciliacao da NF-e
5640276 feat(fiscal): implementa Gate 1 do ciclo de numeracao NF-e
```

---

## 4. Auditoria e implementação do retorno fiscal OMS

Antes de tocar em qualquer contrato, fiz um levantamento read-only do estado atual de `POST /emitir` e `GET /situacao`: contrato exato de cada um, DTOs envolvidos, e — o achado central — a existência de duas fontes de dado fiscal paralelas (`nfe_emissao`, o ciclo operacional novo do Gate 1/2/3, e `nfe_documento`, o documento consolidado legado). `nfe_documento` não cobre `PENDENTE_CONFIRMACAO` por falha de rede, porque só ganha linha quando a SEFAZ efetivamente responde — exatamente o cenário mais delicado para a OMS acompanhar. Entreguei a auditoria antes de implementar, com proposta de contrato aditivo.

Implementação, revisada e ajustada em duas rodadas de banca contratual antes do commit (detalhe nas seções 5 e 6):

- **`POST /emitir`** passou a devolver, além de `chaveNfe`/`soapRetorno` (inalterados), os campos `serie`, `numeroNFe`, `estadoFiscal`, `cStat`, `xMotivo` e `nProt` — fonte única `nfe_emissao`, nunca `nfe_documento`.
- **`GET /situacao`** deixou de ser uma consulta em tempo real à SEFAZ e passou a ser leitura pura do estado persistido em `nfe_emissao` — o mesmo conjunto de campos acima, mais `chaveNfe`. `nfe_documento` só é consultado no fallback legado, para pedidos emitidos antes do Gate 1, e para `dhRecbto`.
- `NUMERO_FISCAL_OCUPADO` (409, Gate 3) passou a expor `serie`/`numeroNFe`/`estadoFiscal` no `data`, junto com o `cStat`/`xMotivo` que já existiam.
- `SEFAZ_REJECTED` (422) ganhou o mesmo tratamento — identifica qual número ficou pendente de correção.

---

## 5. Revisão contratual — rodada 1 (7 achados)

Antes de qualquer commit, revisei o diff completo contra o contrato vigente e contra os riscos arquiteturais que o próprio Gate 3 tinha acabado de fechar. Sete pontos corrigidos:

1. **P0 — tipo de `cStat` alternando** entre `String` (fallback `nfe_documento`) e `Integer` (`nfe_emissao`) no mesmo campo público de `/situacao`. Corrigido: `cStat` em `/situacao` é sempre `String`, preservando o tipo do contrato anterior.
2. **P0 — `/situacao` chamando a SEFAZ ao vivo em todo `GET`.** Era o ponto de maior risco: o endpoint que a OMS usaria para polling contornava exatamente o claim atômico e o backoff que o Gate 3 tinha acabado de construir, com risco real de consumo indevido/`cStat 656` sob consultas repetidas. Removida a chamada SOAP direta (`transmitService.consultarNfe`); `/situacao` passou a ser leitura pura do estado persistido. Reconciliação ativa continua existindo, mas só pelo mecanismo protegido (`POST /emitir` sobre pedido `AGUARDANDO`, que já delega para `NfeReconciliacaoService`).
3. **P1 — mistura de fonte de `chaveNfe`** entre `Pedido` e `nfe_emissao`.
4. **P1 — nomenclatura pública** `numeroNfe` vs `numeroNFe` — fechada a favor de `numeroNFe`, com precedente já documentado em `MTF-001` e `CHECKLIST_ERP_DELIVERY.md` antes desta sessão.
5. **P2 — `cStat=-1` sintético** no overload de `numeroFiscalOcupado` — trocado por `null`.
6. **P2 — overload morto** (3 argumentos) sem nenhum chamador em produção/teste após a migração — removido.
7. **P2 — risco de serialização de `NfeGeracaoResult`** em endpoint legado (`NfeEnvioController`) — verificado sem risco: nenhum endpoint serializa o objeto diretamente, todos extraem campos manualmente.

Regressão pós-rodada 1: `borurio-app` 20/20, `borurio-fiscal` 87/88, `borurio-web` 380/380.

---

## 6. Revisão contratual — rodada 2 (3 achados mais finos)

Segunda revisão, sobre o diff já corrigido:

1. **Retrocompatibilidade de `consultaSefaz`.** Eu tinha removido o campo do JSON de `/situacao` — quebra de contrato, porque o Swagger antigo o documentava como sempre presente. Corrigido: campo mantido, sempre `null`, marcado como deprecated no Swagger e nos dois contratos (nunca mais dispara consulta live).
2. **Mistura de chave ainda não eliminada por completo.** `consultarSituacao()` ainda validava `Pedido.chaveNfe` antes de carregar `nfe_emissao`, e a busca em `nfe_documento` (para `dhRecbto`) ainda usava a chave do Pedido mesmo quando a chave "vencedora" vinha de `nfe_emissao`. Reestruturei o método: carrega `nfe_emissao` primeiro, decide a chave fiscal efetiva uma única vez, e usa essa mesma variável para resposta, validação de CNPJ e busca em `nfe_documento` — nunca mais uma fonte para uma coisa e outra fonte para outra dentro do mesmo ramo.
3. **Cenário `PENDENTE_CONFIRMACAO` com `Pedido.chaveNfe` nulo.** Verifiquei no código (não presumi): é um cenário real. Em `PedidoEmissaoService.emitir()`, no catch de falha de rede na primeira tentativa de emissão, o Pedido é atualizado para `status="ERRO"` usando o valor de `chaveNfe` **anterior** ao ciclo (nulo na primeira tentativa), enquanto o ciclo em `nfe_emissao` já tem a chave congelada (definida antes da chamada à SEFAZ) e fica em `PENDENTE_CONFIRMACAO`. A validação antiga de `/situacao` bloqueava exatamente esse cenário — o pior possível, porque é quando a OMS mais precisa consultar. Corrigido junto com o item 2, com teste específico reproduzindo o cenário.

Também troquei a forma de rodar o teste focado para usar o reactor correto (`-pl borurio-web -am`), depois que uma execução isolada tinha apontado erro de dependência desatualizada no repositório Maven local — não era defeito funcional, era escopo de build incorreto.

Regressão pós-rodada 2: `borurio-app` 20/20, `borurio-fiscal` 87/88, `borurio-web` 381/381.

---

## 7. Microajuste final — remoção do último `cStat` sintético

Revisão final do diff aprovado encontrou um resquício: `NfeReconciliacaoService`, no ramo `AGUARDANDO_CORRECAO`, ainda convertia `cStat` nulo em `-1` antes de repassar para `BusinessException.sefazRejected`. Ajustei o overload de `sefazRejected` usado pelo retorno OMS para aceitar `Integer` (nullable) e passei `decisao.cStat()` direto, sem fallback sintético — mesma regra já aplicada a `numeroFiscalOcupado`. O overload legado de 2 argumentos (usado por um teste de `GlobalExceptionHandlerTest`) ficou intocado. Classificação fiscal e máquina de estados não mudaram.

Teste focado (`NfeReconciliacaoServiceTest`, reactor `-am`): 24/24. Regressão completa: inalterada, `borurio-web` 381/381.

---

## 8. Documentação de contrato atualizada

Atualizei `INTEGRATION_CONTRACT_PT-BR.md` e `INTEGRATION_CONTRACT_EN.md`, seções 6.4 (`/emitir`) e 6.5 (`/situacao`): novos campos e tipos, bloco de resposta `NUMERO_FISCAL_OCUPADO` (não existia documentado ainda), `consultaSefaz` deprecated, tabela de origem por campo, e a nota sobre chave fiscal congelada em `nfe_emissao` mesmo com `Pedido.chaveNfe` nulo.

---

## 9. Commit do retorno fiscal OMS

Com a banca de duas rodadas + microajuste fechada e verde, montei o pacote final — 12 arquivos, 654 insertions / 115 deletions. Conferência de stage antes do commit (`git status --short`, `git diff --cached --check/--stat/--name-only`) confirmou exatamente os 12 arquivos esperados, nenhum documento histórico junto.

Realizei o commit manual:

```
d8590fd — feat(fiscal): retorna resultado fiscal da NF-e para o OMS
```

Arquivos do commit:
```
borurio-app/src/main/java/br/com/borurio/app/exception/BusinessException.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/dto/NfeGeracaoResult.java
borurio-web/src/main/java/br/com/borurio/web/controller/app/PedidoController.java
borurio-web/src/main/java/br/com/borurio/web/service/NfeReconciliacaoService.java
borurio-web/src/main/java/br/com/borurio/web/service/PedidoEmissaoService.java
borurio-web/src/main/java/br/com/borurio/web/service/PedidoOperacaoService.java
borurio-web/src/test/java/br/com/borurio/web/service/NfeReconciliacaoServiceTest.java
borurio-web/src/test/java/br/com/borurio/web/service/PedidoEmissaoServiceTest.java
borurio-web/src/test/java/br/com/borurio/web/service/PedidoOperacaoServiceTest.java
borurio-web/src/test/java/br/com/borurio/web/service/PedidoTenantIsolationAdversarialTest.java
docs/manual/INTEGRATION_CONTRACT_EN.md
docs/manual/INTEGRATION_CONTRACT_PT-BR.md
```

`git log -3 --oneline` no fechamento desta etapa:
```
d8590fd feat(fiscal): retorna resultado fiscal da NF-e para o OMS
620005e feat(fiscal): implementa semantica SEFAZ e reconciliacao da NF-e
5640276 feat(fiscal): implementa Gate 1 do ciclo de numeracao NF-e
```

---

## 10. Resultados finais consolidados (executados nesta sessão)

```
Gate3ReconciliacaoRealMySqlIT (MySQL 8.4 efêmero, porta 3499, criado e destruído nesta sessão) = 3/3

borurio-app    = 20/20
borurio-fiscal = 87/88 (1 skip pré-existente, não relacionado)
borurio-web    = 381/381

NfeReconciliacaoServiceTest (teste focado, reactor -am)      = 24/24

git diff --check = limpo
```

---

## 11. Item solicitado pelo CC — status real

O item mais pedido pelo CC nas últimas semanas — retorno de série, número da NF-e e resultado fiscal (`serie`, `numeroNFe`, `chaveNfe`, `estadoFiscal`, `cStat`, `xMotivo`, `nProt`) em `/emitir` e `/situacao` — está **implementado, testado e documentado localmente**, commitado em `d8590fd`. **Ainda não está disponível em homologação** (nenhum deploy foi feito) **e ainda não foi validado pelo CC**. Não enviei nenhuma mensagem ao CC afirmando que este item está pronto para uso.

---

## 12. O que não foi feito

- Não houve deploy em HOM nesta sessão.
- Não houve deploy em PRD nesta sessão.
- Não houve nenhuma chamada real à SEFAZ (produção ou homologação) nesta sessão.
- Não houve alteração de certificado.
- Cancelamento, CC-e, configuração de estoque do cenário do CC e contingência formal **não foram tocados** nesta sessão — ficam para amanhã.
- Nenhum `git push` foi executado — os três commits do dia (`620005e`, `d8590fd`) permanecem locais, à frente de `origin/fix/sefaz-xml-structure`.

---

## 13. Riscos conhecidos de cancelamento — registrados para investigação amanhã

Sem qualquer implementação hoje, ficam registrados os pontos que o CC já sinalizou e que preciso auditar antes de mexer no fluxo de cancelamento:

- Interpretação real de `cStat`/`xMotivo` do evento de cancelamento — hoje o fluxo não confirma o resultado estruturado antes de decidir o desfecho.
- Não marcar `CANCELADO` quando a SEFAZ rejeitar o evento.
- Rollback de estoque exatamente uma vez (nunca duplicado, nunca omitido).
- Idempotência de cancelamento repetido para o mesmo pedido.
- Timeout ou resultado incerto na chamada de cancelamento — hoje não há reconciliação equivalente à do Gate 3 para esse fluxo.
- Isolamento multiempresa/tenant no cancelamento — já auditado no P0-2 de 07/08/2026 para os fluxos existentes, precisa ser reconfirmado no novo desenho.

Nenhum desses pontos foi investigado em profundidade nem corrigido hoje — apenas listado para abrir a sessão de amanhã.

---

## 14. Estado Git no encerramento

```
$ git branch --show-current
fix/sefaz-xml-structure

$ git log -4 --oneline
d8590fd feat(fiscal): retorna resultado fiscal da NF-e para o OMS
620005e feat(fiscal): implementa semantica SEFAZ e reconciliacao da NF-e
5640276 feat(fiscal): implementa Gate 1 do ciclo de numeracao NF-e
badf795 chore(hom): amplia rate limit de emissao do OMS

$ git status --short
(documentação deste relatório e do checkpoint de 11/08/2026 sendo preparada agora)
(19 documentos históricos em docs/report/ permanecem ?? — fora de escopo, não alterados)

$ git diff --check
(vazio — limpo)
```

Nenhum segredo, senha, API key, certificado ou conteúdo de `.env` em nenhum arquivo alterado ou criado nesta sessão.

---

## 15. Próxima ação obrigatória — retomada em 12/08/2026

1. Auditoria e correção do cancelamento (riscos listados na seção 13).
2. Integração/testes de CC-e.
3. Ajuste/validação da regra de estoque para o cenário do CC.
4. Definição e testes de contingência.
5. Regressão final.
6. Documentação.
7. Preparação de HOM.
8. Smoke interno.
9. Só então nova rodada conjunta com o CC.

---

**Documento consolidado — cobre o dia completo de 11/08/2026 (Gate 3 fechado com prova real de MySQL, commit de Gate 2+3 em `620005e`, retorno fiscal OMS implementado/revisado/commitado em `d8590fd`). Complementar ao checkpoint de 11/08/2026.**
