# Relatório Técnico Diário — 04/05/2026

## Projeto
Borurio ERP Logístico + Fiscal BR / Jcho ERP

## Responsável técnico
Bruno Ribeiro

## Branch
`fix/sefaz-xml-structure`

## Objetivo do dia
Consolidar o marco DEV do motor fiscal, fechar pendências técnicas críticas antes da homologação e iniciar a subida controlada do ambiente HOM para validação real do fluxo NF-e contra SEFAZ homologação.

## Resumo executivo
O dia fechou com avanço concreto em dois níveis:

1. DEV consolidado com novo marco funcional do motor fiscal.
2. HOM efetivamente iniciado, com ambiente subido, autenticação funcionando, Swagger acessível, Flyway aplicado até V011 e consulta real de status SEFAZ retornando `cStat=107` (“Serviço em Operação”). :contentReference[oaicite:0]{index=0}

O ponto que permaneceu em aberto ao final do dia não é mais infraestrutura nem certificado. O bloqueio atual está na emissão real da NF-e em HOM, onde o lote já chega a processamento, mas a NF-e individual ainda retorna rejeição de schema (`cStat=225`), exigindo diagnóstico fino do XML efetivamente assinado/transmitido. :contentReference[oaicite:1]{index=1}

## Atividades realizadas no dia

### 1. Consolidação do marco DEV no motor fiscal
Foi fechado um pacote técnico importante no ambiente de desenvolvimento, incluindo:

- correção estrutural do XML fiscal
- validação de CPF/CNPJ por módulo 11
- controle de sequência de numeração `nNF` por série
- ampliação da cobertura automatizada

Esse checkpoint foi consolidado em commit manual:

- `ffe78f5 feat(fiscal): fecha validacao cpf cnpj e sequenciamento de nfe`

Após o commit, o branch ficou limpo e o push para `origin/fix/sefaz-xml-structure` foi executado com sucesso. :contentReference[oaicite:2]{index=2}

### 2. Fechamento das pendências fiscais críticas
Foram implementadas e validadas as duas pendências técnicas priorizadas no motor fiscal:

#### A. Validação CPF/CNPJ
Foi criado o validador `CpfCnpjValidator` no módulo fiscal e ele passou a ser aplicado no `NfeGeracaoService`, rejeitando documentos inválidos antes da montagem do XML. A suíte automatizada passou a cobrir casos válidos e inválidos de CPF/CNPJ. :contentReference[oaicite:3]{index=3}

#### B. Sequenciamento de `nNF`
Foi criada a estrutura de controle de sequência por emitente e série:

- migration `V011__create_nfe_sequencia.sql`
- `NfeSequencia`
- `NfeSequenciaMapper`
- `NfeSequenciaService`
- `NfeSequenciaServiceImpl`

O `NfeGeracaoService` passou a permitir número opcional no request, atribuindo automaticamente o próximo número quando ausente. A implementação foi protegida com atualização transacional e controle por série. :contentReference[oaicite:4]{index=4}

### 3. Ampliação da suíte de testes
A base automatizada evoluiu de 18 para 32 testes, com `BUILD SUCCESS`, `0` falhas e `1` skip esperado. Esse aumento veio principalmente dos novos testes de:

- `CpfCnpjValidatorTest`
- `NfeSequenciaServiceTest`

Com isso, o motor fiscal passou a ter maior cobertura de regras críticas ligadas à geração e consistência fiscal antes da homologação. :contentReference[oaicite:5]{index=5}

### 4. Situação do branch e do GitHub
No final do bloco DEV:

- `git status` limpo após commit
- `git push origin fix/sefaz-xml-structure` executado com sucesso
- branch remoto atualizado até `ffe78f5`

Após o push, surgiu uma alteração local nova em:

- `borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/impl/NcmServiceImpl.java`

Essa alteração ainda **não foi commitada** e ficou aberta como trabalho em progresso ligado à normalização/importação da base NCM em HOM. :contentReference[oaicite:6]{index=6}

## Início efetivo da homologação (HOM)

### 5. Revisão e correção da configuração HOM
Antes da subida do ambiente de homologação, foi identificado um bloqueador de configuração no `.env.hom`:

- ausência das variáveis `FISCAL_EMITENTE_*`
- divergência de caminho do certificado
- divergência de truststore
- senha do certificado diferente entre DEV e HOM, exigindo alinhamento

O `.env.hom` foi ajustado para refletir o emitente e o path operacional correto do certificado/truststore. Isso destravou a subida do ambiente. :contentReference[oaicite:7]{index=7}

### 6. Subida do ambiente HOM
Foi executado o `docker compose` do ambiente de homologação. O resultado observado foi:

- imagem buildada
- containers iniciados
- MySQL HOM e Redis HOM saudáveis
- `borurio-web-hom` saudável em `8081`

O ambiente ficou operacional. :contentReference[oaicite:8]{index=8}

### 7. Validações básicas de runtime em HOM
Com o ambiente no ar, foram confirmados os checkpoints básicos:

- `/actuator/health` = `UP`
- `/auth/login` = autenticação bem-sucedida
- Swagger HTTP 200
- Flyway aplicado de `V001` até `V011`

Isso confirmou que a homologação não está bloqueada por boot, perfil, migração, banco ou autenticação. :contentReference[oaicite:9]{index=9}

### 8. Validação real de comunicação com a SEFAZ HOM
Foi executado o teste real de status contra SEFAZ homologação e obtido:

- `cStat=107`
- `xMotivo=Serviço em Operação`

Esse é um marco importante, pois comprova:

- certificado A1 carregado
- TLS funcional
- truststore operacional
- SOAP chegando corretamente à SEFAZ HOM

Ou seja, a comunicação real externa já está estabelecida em homologação. :contentReference[oaicite:10]{index=10}

## Problemas encontrados em HOM

### 9. Tabela NCM vazia na homologação
Na primeira tentativa de emissão real, a API retornou erro de negócio:

- `NCM '39269090' do item 0001 não encontrado na tabela NCM oficial.`

Foi confirmado que a tabela `ncm` em HOM estava vazia. A partir disso foi iniciado o processo de carga/saneamento da base NCM. :contentReference[oaicite:11]{index=11}

### 10. Problemas na sincronização/importação de NCM
Durante a tentativa de popular a base NCM em HOM, foram identificados problemas reais:

- arquivo CSV não estava disponível automaticamente no container da aplicação
- `LOAD DATA LOCAL INFILE` estava bloqueado
- `descricao` excedia o tamanho máximo em alguns registros
- os códigos foram importados com pontos, enquanto a busca do sistema ocorria sem pontos
- o `NcmServiceImpl` estava tratando o CSV com separador incorreto (`;` em vez de `,`) e sem normalização adequada do código

A tabela foi populada com `15.144` NCMs e depois normalizada no banco para remoção dos pontos. Em paralelo, foi iniciada correção de código no `NcmServiceImpl` para:

- tratar CSV com vírgula
- remover pontos do código
- limitar `descricao` ao tamanho suportado

Essa correção ficou como alteração local ainda não commitada ao fim do dia.

## Status atual da emissão real em HOM

### 11. Situação funcional
Após o saneamento parcial do NCM, o fluxo avançou:

- o lote chegou a aceitação/processamento (`cStat=104`)
- porém a NF-e individual continuou com `cStat=225`

Isso indica que o ambiente HOM, a infraestrutura, a autenticação, o certificado e a comunicação com a SEFAZ estão corretos. O problema restante está no schema/estrutura do XML real enviado dentro do processo de emissão. :contentReference[oaicite:13]{index=13}

### 12. Diagnóstico em andamento
No fim do dia, a investigação estava focada em:

- extrair o XML real gravado em `nfe_log`
- comparar o XML pré-assinatura e o XML transmitido
- entender a rejeição de schema residual
- revisar a estrutura real do XML assinado/envelopado
- verificar o efeito da assinatura e do conteúdo final transmitido para a SEFAZ

A Claude chegou a levantar hipóteses intermediárias, mas o último teste fino ainda **não foi concluído** porque o limite de tokens acabou antes do fechamento da análise. Portanto, o diagnóstico final da causa do `cStat=225` em HOM permanece aberto. :contentReference[oaicite:14]{index=14}

## Marco obtido em DEV
O marco DEV que pode ser considerado consolidado ao final do dia é:

- motor fiscal com geração XML
- assinatura
- validação XSD local
- transmissão SOAP
- consulta de status
- cancelamento
- inutilização
- CC-e
- consulta por chave
- auditoria fiscal
- validação NCM centralizada
- validação CPF/CNPJ
- sequenciamento de numeração `nNF` por série
- 32 testes automatizados, 0 falhas, 1 skip esperado
- branch consolidado e enviado ao GitHub até `ffe78f5`

## Onde estamos em homologação
Ao final do dia, o estado de HOM é este:

### HOM já validado
- compose sobe
- MySQL/Redis saudáveis
- aplicação HOM saudável
- `/actuator/health` ok
- `/auth/login` ok
- Swagger ok
- Flyway até V011
- SEFAZ HOM respondendo `107` via SOAP real

### HOM ainda pendente
- fechamento da carga/sincronização NCM por fluxo definitivo de aplicação
- commit da correção em `NcmServiceImpl`
- identificação exata da divergência residual do XML que ainda gera `cStat=225` na NF-e individual
- nova emissão real após ajuste fino do XML/schema

## Posição do ERP Logístico + Motor Fiscal
No objetivo maior do ERP Logístico + Fiscal, a situação atual é:

### Consolidado
- núcleo fiscal NF-e está avançado e funcional em DEV
- integração externa com SEFAZ HOM já comprovada
- base técnica do motor fiscal está madura o suficiente para foco de homologação

### Ainda não iniciado de forma intencional
- pedido / ordem
- estoque / logística
- integração pedido → NF-e
- dashboard / relatórios gerenciais

Portanto, o projeto ainda está corretamente concentrado no **motor fiscal**. O ERP logístico continua fora do foco imediato até o fechamento da homologação fiscal. :contentReference[oaicite:17]{index=17}

## Exato ponto em que paramos
Paramos exatamente neste ponto:

1. o commit fiscal `ffe78f5` já foi feito e enviado ao GitHub
2. HOM já subiu e está saudável
3. consulta real de status SEFAZ HOM já respondeu `107`
4. a emissão real avançou até `cStat=104` no lote, mas a NF-e individual ainda retorna `225`
5. a tabela NCM em HOM já foi carregada/normalizada emergencialmente no banco
6. `NcmServiceImpl.java` foi alterado localmente para corrigir parsing e normalização do CSV, mas essa alteração ainda **não foi commitada**
7. a próxima ação correta é retomar a investigação do XML real transmitido/assinado para descobrir a divergência residual de schema em HOM

## Próximos passos recomendados
1. revisar o diff local de `NcmServiceImpl.java`
2. decidir se a correção de NCM será consolidada em commit separado
3. retomar a análise do `xml_envio/xml_retorno` em `nfe_log`
4. isolar a causa exata do `cStat=225` residual
5. corrigir o ponto mínimo necessário
6. repetir emissão real em HOM
7. só depois disso avançar para Swagger automatizado/documentado e, futuramente, para os módulos do ERP logístico