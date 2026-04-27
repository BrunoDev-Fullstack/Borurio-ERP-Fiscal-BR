# Relatório Técnico — 27/04/2026

## Projeto

Borurio ERP Fiscal BR / Jcho ERP

## Responsável técnico

Bruno Ribeiro

## Objetivo do dia

Estabilizar o ambiente DEV, corrigir o healthcheck da aplicação, revisar o cabeamento do certificado A1, validar o runtime Docker/Spring Boot, corrigir o pipeline fiscal NF-e para garantir assinatura XML antes da transmissão e versionar os principais marcos técnicos do dia.

## Contexto técnico

O projeto está estruturado como backend Java/Spring Boot multi-module, com foco em ERP brasileiro logístico e fiscal. A prioridade atual é consolidar o motor fiscal NF-e 4.00 integrado à SEFAZ, mantendo o backend em Java 17 com Spring Boot 3.3.2.

Stack principal:

- Java 17
- Spring Boot 3.3.2
- Maven multi-module
- Docker Compose
- MySQL 8.4
- Redis 7.2
- MinIO
- MyBatis
- Flyway
- Swagger/OpenAPI
- JWT
- NF-e 4.00
- SOAP 1.2
- Certificado A1 PKCS12

Módulos Maven:

- `borurio-core`
- `borurio-app`
- `borurio-fiscal`
- `borurio-web`

## Estrutura documental criada

Foi consolidada a pasta:

```text
docs/architecture

Com os arquivos fonte Draw.io:

docs/architecture/drawio/01_topologia_geral_erp_borurio.drawio
docs/architecture/drawio/02_topologia_alvo_funcional_motor_fiscal_nfe.drawio
docs/architecture/drawio/03_fluxo_tecnico_emissao_nfe_sefaz.drawio
docs/architecture/drawio/04_topologia_ambientes_dev_hom_prd.drawio
docs/architecture/drawio/05_topologia_seguranca_certificados_nfe.drawio
docs/architecture/drawio/06_topologia_cicd_versionamento_evidencias_deploy.drawio

E os exports PNG:

docs/architecture/exports/01_topologia_geral_erp_borurio.png
docs/architecture/exports/02_topologia_alvo_funcional_motor_fiscal_nfe.png
docs/architecture/exports/03_fluxo_tecnico_emissao_nfe_sefaz.png
docs/architecture/exports/04_topologia_ambientes_dev_hom_prd.png
docs/architecture/exports/05_topologia_seguranca_certificados_nfe.png
docs/architecture/exports/06_topologia_cicd_versionamento_evidencias_deploy.png

As topologias criadas foram:

Topologia geral do ERP Borurio.
Topologia alvo funcional com motor fiscal NF-e.
Fluxo técnico de emissão NF-e/SEFAZ.
Topologia de ambientes DEV/HOM/PRD.
Topologia de segurança e certificados NF-e.
Topologia de CI/CD, versionamento, evidências e deploy.

Esses artefatos serão usados posteriormente no manual técnico, manual de acesso, manual de implantação, documentação para CC/time interno e checklist DEV/HOM/PRD.

Atividades realizadas
1. Estabilização do ambiente DEV

Foi validado que o ambiente DEV sobe corretamente com Docker Compose.

Componentes validados:

borurio-web-dev
MySQL
Redis
MinIO

O Spring Boot iniciou corretamente na porta 8080 com perfil dev.

Resultado:

Spring Boot iniciado com sucesso.
Container borurio-web-dev executando.
Perfil dev ativo.
2. Correção do healthcheck

Foi identificado que o endpoint:

GET http://localhost:8080/actuator/health

retornava HTTP 403.

A causa raiz foi localizada no arquivo:

docker/env/.env.dev

As variáveis abaixo estavam movendo o Actuator para a porta 8081:

MANAGEMENT_SERVER_PORT=8081
ACTUATOR_PORT=8081

Enquanto isso, o Docker healthcheck e a validação local consultavam a porta 8080.

Fluxo incorreto anterior:

Aplicação principal: 8080
Actuator: 8081
Docker healthcheck: 8080
Resultado: HTTP 403

Correção aplicada:

#MANAGEMENT_SERVER_PORT=8081
#ACTUATOR_PORT=8081

Fluxo corrigido:

Aplicação principal: 8080
Actuator: 8080
Docker healthcheck: 8080
Resultado: HTTP 200 / UP

Resultado validado:

{
  "status": "UP"
}

Componentes saudáveis:

db: UP
redis: UP
ping: UP
diskSpace: UP
3. Ajuste do SecurityConfig

Foi ajustada a chain de segurança do Actuator no arquivo:

borurio-web/src/main/java/br/com/borurio/web/config/SecurityConfig.java

Alteração técnica aplicada:

Uso de EndpointRequest.toAnyEndpoint() para a chain do Actuator.
Aplicação de SessionCreationPolicy.STATELESS na chain do Actuator.
Separação da chain do Actuator em relação à chain principal da aplicação.

Observação técnica: a causa raiz final do 403 não era o SecurityConfig, e sim o deslocamento do Actuator para a porta 8081 via variável de ambiente. Mesmo assim, o ajuste no SecurityConfig deixou a configuração mais adequada para endpoints de management no Spring Boot.

4. Revisão e padronização do certificado A1

Foi revisado o cabeamento do certificado A1 usado pelo motor fiscal NF-e.

Caminho esperado dentro do container:

/app/certificados/pfx/certificado-jcho.pfx

Validações realizadas em runtime:

Certificado A1 carregado com sucesso.
Alias de chave privada resolvido automaticamente como 1.
SSLContext TLSv1.2 inicializado com sucesso.
Nenhum erro de PKCS12.
Nenhum erro de senha.
Nenhum erro de alias.
Nenhum erro de certificado.

Também foi removido o fallback obsoleto de senha no application-dev.yml, mantendo o uso por variável de ambiente.

Arquivos envolvidos:

borurio-web/src/main/resources/application-dev.yml
docker/docker-compose.dev.yml
docker/env/.env.dev

Observação de segurança: valores sensíveis não devem ser registrados em relatório, commit, print ou documentação técnica.

5. Correção do pipeline fiscal NF-e

Foi identificada uma lacuna crítica no pipeline fiscal: o XML era validado por XSD e encaminhado para transmissão sem garantia de assinatura pelo AssinaturaXmlService.

Fluxo anterior:

XML recebido
→ validação XSD
→ transmissão

Esse fluxo era insuficiente, pois a NF-e precisa ser assinada digitalmente antes da transmissão.

Fluxo corrigido:

XML recebido
→ parse Document
→ validação XSD
→ AssinaturaXmlService.assinar(xmlNfe)
→ NfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente, "SP", 2)
→ montagem enviNFe
→ SOAP 1.2

Arquivos alterados:

borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/NfeOrquestradorService.java
borurio-web/src/main/java/br/com/borurio/web/controller/fiscal/NfeEnvioController.java

Correções aplicadas:

AssinaturaXmlService passou a ser injetado no NfeOrquestradorService.
O método processar passou a receber xmlNfe e cnpjEmitente.
O XML passou a ser assinado antes da transmissão.
O CNPJ do emitente passou a ser encaminhado do controller para o orquestrador.
O valor hardcoded "00000000000000" foi removido do fluxo principal.
6. Validação Maven

Foi executada validação de compile dos módulos fiscais e web.

Resultado:

borurio-fiscal: SUCCESS
borurio-web: SUCCESS
BUILD SUCCESS

Sem erro de compilação.

7. Validação Docker/runtime

Foi realizado rebuild da imagem Docker do borurio-web-dev.

Resultado validado:

Docker image build: sucesso
Container borurio-web-dev: running
Health status: healthy
Spring Boot: iniciado com sucesso
/actuator/health: HTTP 200 / UP
/auth/login: HTTP 200 com JWT
MySQL: UP
Redis: UP
DiskSpace: UP
Ping: UP
Certificado A1: carregado
SSLContext TLSv1.2: inicializado

Não foram identificados erros de runtime relacionados a:

BeanCreationException
UnsatisfiedDependencyException
NfeOrquestradorService
AssinaturaXmlService
NfeTransmitService
XsdValidator
CertificadoService
8. Versionamento Git

Branch atual:

fix/sefaz-xml-structure
Commit 1

Hash:

8a8eb34

Mensagem:

fix(dev): estabiliza healthcheck e assinatura no pipeline nfe

Arquivos versionados:

borurio-fiscal/src/main/java/br/com/borurio/fiscal/service/NfeOrquestradorService.java
borurio-web/src/main/java/br/com/borurio/web/config/SecurityConfig.java
borurio-web/src/main/java/br/com/borurio/web/controller/fiscal/NfeEnvioController.java
borurio-web/src/main/resources/application-dev.yml
docker/docker-compose.dev.yml

Objetivo do commit:

Corrigir a configuração do Actuator/healthcheck.
Corrigir a assinatura XML antes da transmissão NF-e.
Ajustar o uso de variáveis para certificado A1.
Remover senha de certificado do bloco inline do Docker Compose.
Consolidar checkpoint técnico de runtime DEV.
Commit 2

Hash:

fd8528b

Mensagem:

docs(architecture): adiciona topologias do erp fiscal

Arquivos versionados:

docs/architecture/drawio/01_topologia_geral_erp_borurio.drawio
docs/architecture/drawio/02_topologia_alvo_funcional_motor_fiscal_nfe.drawio
docs/architecture/drawio/03_fluxo_tecnico_emissao_nfe_sefaz.drawio
docs/architecture/drawio/04_topologia_ambientes_dev_hom_prd.drawio
docs/architecture/drawio/05_topologia_seguranca_certificados_nfe.drawio
docs/architecture/drawio/06_topologia_cicd_versionamento_evidencias_deploy.drawio
docs/architecture/exports/01_topologia_geral_erp_borurio.png
docs/architecture/exports/02_topologia_alvo_funcional_motor_fiscal_nfe.png
docs/architecture/exports/03_fluxo_tecnico_emissao_nfe_sefaz.png
docs/architecture/exports/04_topologia_ambientes_dev_hom_prd.png
docs/architecture/exports/05_topologia_seguranca_certificados_nfe.png
docs/architecture/exports/06_topologia_cicd_versionamento_evidencias_deploy.png

Objetivo do commit:

Versionar as 6 topologias arquiteturais.
Registrar a base visual para documentação técnica, manual de implantação e alinhamento com CC/time interno.

Não houve push.

9. Resultado consolidado do dia

O dia fechou com um marco técnico relevante.

Resumo validado:

[OK] Docker DEV funcional.
[OK] borurio-web-dev healthy.
[OK] Spring Boot ativo na porta 8080.
[OK] /actuator/health HTTP 200 / UP.
[OK] /auth/login HTTP 200 com JWT.
[OK] MySQL saudável.
[OK] Redis saudável.
[OK] Certificado A1 carregado.
[OK] SSLContext TLSv1.2 inicializado.
[OK] Pipeline NF-e corrigido para assinar XML antes da transmissão.
[OK] Topologias arquiteturais criadas e versionadas.
[OK] Checkpoint técnico versionado em Git.
10. Pendências técnicas

Pendências imediatas:

[PENDENTE] Validar se existe modo seguro para testar XML parse, XSD e assinatura XMLDSIG sem transmissão SEFAZ.
[PENDENTE] Não chamar POST /api/fiscal/nfe/enviar antes de confirmar estratégia segura.
[PENDENTE] Revisar arquivos restantes não commitados.
[PENDENTE] Validar ClienteController via API.
[PENDENTE] Validar NcmController via API.
[PENDENTE] Versionar migrations V007/V008 e MyBatisConfig em commit separado, após inspeção.
[PENDENTE] Revisar alterações em application-hom.yml, application-prd.yml e docker-compose.hom.yml antes de qualquer commit.
[PENDENTE] Criar manual técnico usando as 6 topologias.
[PENDENTE] Criar manual de acesso.
[PENDENTE] Criar manual de implantação.
[PENDENTE] Criar checklist DEV/HOM/PRD para CC/time interno.

Pendências funcionais futuras:

[AUSENTE] Módulo de Produtos.
[AUSENTE] Módulo de Pedidos / Ordens.
[AUSENTE] Módulo de Estoque / Logística.
[AUSENTE] Relatórios / Dashboard.
[AUSENTE] DANFE.
[AUSENTE] Cancelamento de NF-e.
[AUSENTE] Inutilização de numeração NF-e.
[AUSENTE] Carta de Correção Eletrônica.
[AUSENTE] Manifestação do destinatário.
[AUSENTE] Multiempresa.
[AUSENTE] Certificado por CNPJ/empresa.
11. Riscos ainda existentes

Riscos técnicos identificados:

1. Ainda existem arquivos modificados não commitados fora do escopo dos commits 1 e 2.
2. O endpoint POST /api/fiscal/nfe/enviar ainda não deve ser chamado sem confirmar se existe modo seguro de teste local.
3. As alterações de HOM/PRD ainda precisam ser revisadas antes de versionamento.
4. As migrations V007/V008 e MyBatisConfig ainda precisam ser versionadas em commit separado.
5. Módulos Produtos, Pedidos e Estoque ainda não existem.
6. Fiscal avançado ainda não existe: DANFE, cancelamento, inutilização, CC-e e manifestação do destinatário.
7. Homologação real com SEFAZ ainda precisa de estratégia controlada.
12. Próximo passo recomendado

Antes de qualquer chamada ao endpoint real de envio NF-e, inspecionar se o projeto possui teste local, mock, dry-run ou modo validation-only para executar:

parse do XML
validação XSD
assinatura XMLDSIG

sem transmitir para a SEFAZ.

Se esse modo não existir, o próximo passo técnico recomendado é criar primeiro um teste local controlado para validar XSD + assinatura, antes de acionar o endpoint real.

13. Conclusão

O dia fechou com avanço técnico relevante.

O ambiente DEV foi estabilizado, o problema de healthcheck foi corrigido na causa raiz, o certificado A1 foi validado em runtime, o pipeline fiscal NF-e foi ajustado para assinar o XML antes da transmissão e as topologias arquiteturais foram criadas e versionadas.

O projeto agora está em condição técnica melhor para avançar para validação fiscal controlada, evitando repetir tentativas no SecurityConfig e evitando chamada prematura à SEFAZ.