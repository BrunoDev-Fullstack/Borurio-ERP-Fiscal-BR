Relatório Técnico — 30/04/2026
Projeto

Borurio ERP Fiscal BR / Jcho ERP

Responsável técnico

Bruno Ribeiro

Branch

fix/sefaz-xml-structure

Pasta

C:\Projetos\borurio-erp-br

Objetivo do dia

Evoluir o motor fiscal local para fechar o bloco de funcionalidades fiscais avançadas pendentes, consolidar consistência de auditoria e validação, manter o ambiente em estado compilável e testável, e preparar o projeto para a próxima fase de estabilização runtime em DEV antes de qualquer validação fiscal ponta a ponta.

Contexto técnico

O projeto segue estruturado como backend Java/Spring Boot multi-module, com foco em ERP brasileiro logístico + fiscal e priorização do motor NF-e 4.00.

Stack principal:

Java 17
Spring Boot 3.3.2
Maven multi-module
Docker Compose
MySQL 8.4
Redis 7.2
MinIO
MyBatis
Flyway
Swagger/OpenAPI
JWT
NF-e 4.00
SOAP 1.2
Certificado A1 PKCS12

Módulos Maven:

borurio-core
borurio-app
borurio-fiscal
borurio-web
Situação de partida do dia

Ao início da sessão, o projeto já havia consolidado:

ambiente DEV saudável
Spring Boot em 8080
/actuator/health com HTTP 200 / UP
/auth/login com JWT
certificado A1 carregando
SSLContext TLSv1.2 inicializado
contrato do endpoint fiscal definido para receber <NFe> bare
guarda P0 em NfeOrquestradorService rejeitando <enviNFe> como input
pipeline fiscal local validado sem chamar SOAP/SEFAZ

As pendências fiscais avançadas abertas até então incluíam:

inutilização de numeração
Carta de Correção Eletrônica (CC-e)
consulta NF-e por chave
consolidação de auditoria
reforço de validação NCM
Atividades realizadas
1. Implementação de consulta NF-e por chave

Foi implementado o suporte a consulta de situação de NF-e pela chave de acesso.

Arquivos alterados:

borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/NfeTransmitService.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/impl/NfeTransmitServiceImpl.java
borurio-web/src/main/java/br/com/borurio/web/controller/fiscal/NfeEnvioController.java

Entregas:

novo método consultarNfe(...) no contrato de transmissão
montagem de envelope SOAP consSitNFe
validação de chave com exatamente 44 dígitos numéricos
persistência de log fiscal com tipoEvento="CONSULTA"
novo endpoint:

GET /api/fiscal/nfe/{chave}

Fluxo implementado:

Controller
→ normalização da chave
→ validação
→ chamada de NfeTransmitService.consultarNfe(...)
→ retorno do XML/resposta da SEFAZ

Observação técnica: a implementação foi feita mantendo o padrão já existente de transporte SOAP 1.2 e reaproveitando a infraestrutura de certificado já usada pelo motor fiscal.

2. Consistência de auditoria em NfeLog

Foi corrigido o ponto em que eventos fiscais registrados via registrarEvento(...) podiam ser persistidos sem status.

Arquivo alterado:

borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/impl/NfeLogServiceImpl.java

Ajuste aplicado:

preenchimento explícito de status="SUCCESS" no fluxo de registro padrão

Impacto:

elimina registros inconsistentes com status nulo
melhora rastreabilidade de eventos
prepara a base para uso mais seguro de auditoria e histórico fiscal
3. Centralização da validação NCM na service layer

Foi reforçada a validação NCM dentro do fluxo de geração fiscal.

Arquivo alterado:

borurio-web/src/main/java/br/com/borurio/web/service/NfeGeracaoService.java

Ajustes aplicados:

injeção de NcmService
validação de formato NCM com 8 dígitos
consulta à tabela NCM oficial durante a validação do request
rejeição de NCM inexistente na base oficial
degradação controlada em caso de falha infraestrutural de consulta

Resultado:

o fluxo de emissão ficou menos dependente de validação espalhada
a checagem NCM passou a ocorrer em ponto único dentro da camada de serviço
reduziu a chance de emissão com NCM sintaticamente válido, porém inexistente na tabela
4. Implementação de inutilização de numeração NF-e

Foi implementado o bloco de inutilização de faixa numérica.

Arquivos criados/alterados:

borurio-fiscal/src/main/java/br/com/borurio/fiscal/dto/NfeInutilizacaoRequest.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/NfeInutilizacaoService.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/AssinaturaXmlService.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/impl/NfeInutilizacaoServiceImpl.java
borurio-web/src/main/java/br/com/borurio/web/controller/fiscal/NfeInutilizacaoController.java

Entregas:

DTO de entrada para inutilização
interface de serviço específica
implementação completa de montagem de inutNFe
assinatura de infInut
geração de Id técnico da inutilização
envio via SOAP 1.2 com certificado A1
persistência em NfeLog com tipoEvento="INUTILIZACAO"
novo endpoint:

POST /api/fiscal/nfe/inutilizar

Validações implementadas:

ano com 2 dígitos
série numérica
faixa nNFIni / nNFFin
ordem correta entre número inicial e final
justificativa entre 15 e 255 caracteres
5. Implementação de Carta de Correção Eletrônica (CC-e)

Foi implementado o suporte a CC-e como evento fiscal próprio.

Arquivos criados/alterados:

borurio-fiscal/src/main/java/br/com/borurio/fiscal/mapper/NfeLogMapper.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/NfeLogService.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/impl/NfeLogServiceImpl.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/dto/NfeCceRequest.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/NfeCceService.java
borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/impl/NfeCceServiceImpl.java
borurio-web/src/main/java/br/com/borurio/web/controller/fiscal/NfeCceController.java
borurio-fiscal/src/test/java/br/com/borurio/fiscal/mock/NfeAuthorizeServiceTest.java

Entregas:

DTO específico para CC-e
interface e implementação do serviço
endpoint:

POST /api/fiscal/nfe/cce

montagem de evento 110110
assinatura via assinarEvento()
persistência em NfeLog com tipoEvento="CCE"
contagem de eventos por chave para sequência
bloqueio acima do limite de 20 eventos por NF-e
derivação de nSeqEvento a partir do banco, com fallback seguro

Validações implementadas:

chave com 44 dígitos
xCorrecao entre 15 e 1000 caracteres
sequência entre 1 e 20
controle de histórico por chave fiscal
6. Ajuste em teste mock após evolução de contrato

A expansão de NfeLogService com contagem de eventos exigiu adequação do mock de teste.

Arquivo alterado:

borurio-fiscal/src/test/java/br/com/borurio/fiscal/mock/NfeAuthorizeServiceTest.java

Ajuste aplicado:

implementação do novo método contarEventos(...) no mock

Impacto:

restauração da compatibilidade da suite de testes
preservação da cobertura sem regressão após a mudança do contrato de auditoria
7. Validação de compilação e testes locais

Foram executadas compilações e testes locais após os blocos fiscais implementados.

Validações registradas:

mvn -pl borurio-web -am clean compile
mvn -pl borurio-fiscal test
mvn clean test

Resultado consolidado:

18 testes
0 falhas
1 skip
BUILD SUCCESS

Também foi mantida a restrição de não chamar SEFAZ real nos testes locais, preservando o modelo de validação segura.

8. Organização do working tree e consolidação local por blocos

Após fechar o milestone fiscal local, o trabalho migrou para organização do estado do repositório.

Foi feito:

leitura de git status --short
agrupamento das alterações por áreas
separação em blocos atômicos
consolidação local do trabalho em commits organizados por risco e domínio

Áreas agrupadas:

docs
fiscal/log
security/config
docker/env
app/NCM/migrations
web/controllers/services
fiscal engine + eventos

Observação importante:

a consolidação ficou local
a publicação remota deve continuar condicionada à estabilização runtime
o próximo passo correto não é nova feature, e sim validação controlada do ambiente DEV
Arquivos de maior impacto técnico no dia
Bloco consulta / auditoria / NCM
NfeTransmitService.java
NfeTransmitServiceImpl.java
NfeEnvioController.java
NfeLogServiceImpl.java
NfeGeracaoService.java
Bloco inutilização
NfeInutilizacaoRequest.java
NfeInutilizacaoService.java
AssinaturaXmlService.java
NfeInutilizacaoServiceImpl.java
NfeInutilizacaoController.java
Bloco CC-e
NfeLogMapper.java
NfeLogService.java
NfeCceRequest.java
NfeCceService.java
NfeCceServiceImpl.java
NfeCceController.java
NfeAuthorizeServiceTest.java
Resultado consolidado do dia

O dia fechou com avanço técnico relevante no motor fiscal local.

Resumo validado:

[OK] Consulta NF-e por chave implementada
[OK] Consistência de NfeLog ajustada
[OK] Validação NCM centralizada na camada de serviço
[OK] Inutilização de numeração implementada
[OK] CC-e implementada
[OK] Compile local validado
[OK] Suite de testes local verde
[OK] 18 testes executados
[OK] 0 falhas
[OK] 1 skip previsto
[OK] BUILD SUCCESS
[OK] Sem chamada SEFAZ real nos testes locais
[OK] Working tree organizado por blocos
[OK] Consolidação local preparada para estabilização
Pendências técnicas imediatas

Pendências imediatas:

[PENDENTE] Validar novamente o runtime DEV após o fechamento do bloco fiscal
[PENDENTE] Confirmar healthcheck, login e Swagger no estado mais recente
[PENDENTE] Validar os endpoints locais sem chamar SEFAZ real
[PENDENTE] Validar POST /api/fiscal/nfe/gerar em ambiente controlado
[PENDENTE] Revisar cuidadosamente mudanças de Docker/HOM/PRD antes de qualquer publicação remota
[PENDENTE] Consolidar README público em versão técnica estável
[PENDENTE] Revisar o diff final antes de qualquer push

Pendências funcionais futuras:

[AUSENTE] DANFE
[AUSENTE] Manifestação do destinatário
[AUSENTE] Pedido / Ordem
[AUSENTE] Estoque / Logística
[AUSENTE] Integração Pedido → NF-e
[AUSENTE] Multiempresa
[AUSENTE] Certificado por CNPJ/empresa
[AUSENTE] Relatórios e dashboard
Riscos ainda existentes

Riscos técnicos identificados:

O milestone fiscal local está mais completo, mas ainda não equivale a validação runtime ponta a ponta.
O ambiente DEV precisa ser revalidado após o fechamento do bloco fiscal avançado.
A publicação remota antes da estabilização pode consolidar alterações ainda não validadas em runtime.
A emissão real como cliente/CNPJ ainda depende de validação controlada do fluxo local com banco, Docker e Swagger.
DANFE, manifestação e integração ERP operacional ainda não foram tratados.
Próximo passo recomendado

O próximo passo técnico correto é interromper a criação de novas features e iniciar a fase de estabilização.

Sequência recomendada:

validar git status e o estado final dos blocos locais
subir/validar o ambiente DEV novamente
confirmar:
/actuator/health
/auth/login
Swagger
rotas fiscais protegidas
validar o fluxo local controlado via POST /api/fiscal/nfe/gerar
só depois avaliar teste funcional de emissão como cliente/CNPJ
Conclusão

O dia fechou com um marco fiscal local importante.

O sistema passou a suportar, no ambiente local e com compile/test verde:

emissão estruturada
cancelamento
inutilização
CC-e
consulta por chave
auditoria fiscal mais consistente
validação NCM mais robusta

Com isso, o motor fiscal deu um salto relevante de maturidade. O foco agora não deve ser expansão funcional do ERP, e sim estabilização do runtime DEV e validação controlada do fluxo operacional antes de qualquer consolidação remota ou mudança de módulo.