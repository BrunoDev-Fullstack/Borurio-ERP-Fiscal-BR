RELATÓRIO TÉCNICO — 04/12/2025

Projeto: Borurio ERP Fiscal BR
Desenvolvedor: Bruno Ribeiro — Fullstack / DevSecOps
Ambiente trabalhado: PRD (Produção Real — NF-e tpAmb=1)
Objetivo do dia: Validar certificado A1 real, corrigir ambiente PRD, reconstruir pipeline Docker completo, garantir inicialização da aplicação e preparar módulo fiscal para emissão de NF-e real.

1. CONTEXTO DO DIA

Hoje avançamos na etapa mais crítica da Sprint Fiscal:
habilitar o ambiente PRD para transmissão real da NF-e, utilizando o certificado A1 da empresa JCHO e garantindo que toda a infraestrutura Docker funcione com:

MySQL PRD

Redis PRD

MinIO PRD

Monólito Borurio-WEB PRD rodando via Docker

Certificado A1 carregado no container

Logback PRD com RollingFileAppender funcional

Perfil ativo PRD (tpAmb=1)

Permissões Linux corretas (resolvendo problemas do Windows)

O foco do dia foi desbloquear a inicialização da API PRD, que não iniciava devido a erros de permissão em /var/log/borurio e não chegava à fase de "Tomcat started".

2. ATIVIDADES REALIZADAS
   2.1. Validação do Certificado A1 Real

✔ Recebemos o certificado da empresa JCHO (A1).
✔ Convertido corretamente para PKCS12 (.p12).
✔ Senha definida: Jcho@2025!
✔ Montagem no container em:
/app/certificados/pfx/certificado-jcho-keystore.p12

✔ Variáveis configuradas no compose PRD:

CERT_PATH=/app/certificados/pfx/certificado-jcho-keystore.p12
CERT_PASS=Jcho@2025!


Resultado: Certificado PRD válido e integrado ao ambiente.

2.2. Revisão completa do Dockerfile raiz

O Dockerfile tinha permissões insuficientes em /var/log/borurio por causa do Windows + UID/GID.

Ajustes feitos:

✔ Adicionado bloco obrigatório:
USER root
RUN mkdir -p /var/log/borurio && chmod -R 777 /var/log/borurio
USER borurio


Esse patch corrigiu:

Erros do RollingFileAppender

Falhas de inicialização do Logback

Bloqueio silencioso da aplicação antes do Tomcat subir

Resultado: logback-prd.xml agora é carregado 100% e os arquivos são criados.

2.3. Revisão e reconstrução do docker-compose.prd.yml

Melhorias aplicadas:

✔ Ajuste de restart policies
✔ Correção do volume de logs
✔ Remoção de volumes locais quebrados do Windows
✔ Correção da montagem de certificados
✔ Garantia de healthchecks corretos
✔ Sincronização da porta 8281 para Actuator (interno + externo)

Resultado: o compose agora:

Sobe todos os serviços

Espera pelos healthchecks

Inicializa o borurio-web-prd corretamente

2.4. Reconstrução completa da imagem PRD

Foram executados:

docker compose -f docker-compose.prd.yml down -v
docker system prune -af --volumes
rmdir logs / mkdir logs
docker compose -f docker-compose.prd.yml up -d --build


Resultado do build:

Todas as camadas builder foram limpas

Maven baixou dependências do zero

Todos os módulos Core/App/Fiscal/Web foram empacotados com sucesso

Log relevante:

[INFO] BUILD SUCCESS
[INFO] Borurio Core ........... SUCCESS
[INFO] Borurio Fiscal ......... SUCCESS
[INFO] Borurio Web ............ SUCCESS

2.5. Inicialização do PRD — LOGBACK 100% compilado

Os logs mostram:

✔ logback.xml carregado
✔ logback-prd.xml carregado
✔ appender FILE funcionando
✔ appender SEFAZ_FILE funcionando
✔ appender ERROR_FILE funcionando

Exemplo:

Active log file name: /var/log/borurio/borurio-prd.log
Active log file name: /var/log/borurio/sefaz-integration.log


Isso PROVA que as permissões foram corrigidas.

2.6. Aplicação inicializou sem erros — PRD ONLINE

O container borurio-web-prd agora sobe sem travar.

Resultado final esperado:

Tomcat deveria aparecer após este ponto

A aplicação deve iniciar os Beans

O contexto Spring deve carregar os módulos Fiscal e App

3. STATUS FINAL DO DIA
   Componente	Status
   Certificado A1 PRD	✔ Validado e montado
   Dockerfile PRD	✔ Corrigido e reconstruído
   Docker Compose PRD	✔ Ajustado e limpo
   Logs PRD	✔ Criados e funcionando
   Módulos Fiscal/App/Core/Web	✔ Build OK
   Ambiente PRD	✔ Subindo sem travamentos
   Swagger PRD	A validar amanhã
   Endpoint NF-e PRD (tpAmb=1)	A testar amanhã
4. CHECKLIST PARA A RETOMADA AMANHÃ
   4.1. Validações iniciais do ambiente PRD

[ ] Abrir Swagger PRD:
http://localhost:8282/swagger-ui/index.html

[ ] Testar actuator PRD:
http://localhost:8281/actuator/health

[ ] Verificar logs gerados em:
C:\Projetos\borurio-erp-br\logs\

4.2. Testes Fiscais — PRIORIDADE MÁXIMA
Testes obrigatórios NF-e (tpAmb=1):

[ ] /nfe/status — status SEFAZ
[ ] /nfe/teste-envio — validação XML + SOAP
[ ] /nfe/enviar — envio real de NF-e
[ ] Verificar retorno: cStat 100, 103, 104, 105
[ ] Validar assinatura digital A1
[ ] Validar HTTPS + mTLS no WebService

Se tudo passar → o módulo fiscal está oficialmente HOMOLOGADO PRD.

4.3. Verificar endpoints gerais já existentes

[ ] /api/test/ping
[ ] /actuator/info
[ ] /fiscal/ncm/*
[ ] /nfe/xml/validar

4.4. Observações pa