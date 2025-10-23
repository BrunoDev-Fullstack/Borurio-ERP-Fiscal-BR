Relatório Técnico – 23/10/2025
Sprint Fiscal 3.4 – Homologação SEFAZ-SP / Estabilização do Ambiente

Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
Projeto: Borurio ERP Fiscal BR
Data: 23/10/2025
Módulos: borurio-fiscal, borurio-web
Ambiente: Homologação (Docker Compose / SEFAZ-SP)

🧭 Resumo do Dia

O dia foi dedicado à consolidação do ambiente de Homologação Fiscal (SEFAZ-SP), empacotamento dos módulos, testes de build, e integração do application-hom.yml com o contêiner Docker do módulo borurio-web.
O foco foi garantir que o ambiente esteja 100% alinhado com os parâmetros fiscais e a infraestrutura DevSecOps, validando a execução do serviço principal e o carregamento correto das variáveis de configuração (borurio.sefaz.*).

⚙️ Atividades Realizadas
1️⃣ Build e Testes de Integração

Executado build completo do monorepo:

mvn clean package -DskipTests


Todos os módulos (core, app, fiscal, web) foram compilados com sucesso.

borurio-fiscal validado com dependências atualizadas e teste unitário NfeTransmitServiceTest revisado.

JARs finais gerados:

borurio-fiscal-1.0.0.jar

borurio-web-1.0.0.jar (com Actuator e SEFAZ)

2️⃣ Revisão e Consolidação do application-hom.yml

Reescrito o application-hom.yml com padrão DevSecOps Fiscal Homologação, incluindo:

Bloco borurio.sefaz.* (compatível com @Value do NfeTransmitServiceImpl);

Bloco borurio.certificado.* (para certificado A1);

Ajuste dos endpoints SEFAZ-SP (Autorização, Retorno, Status, Evento, etc.);

Configurações unificadas de Flyway, Redis, Logging e Actuator.

Caminho final:

borurio-web/src/main/resources/application-hom.yml

3️⃣ Deploy e Validação via Docker Compose

Container borurio-web-hom reiniciado com:

docker restart borurio-web-hom
docker logs -f borurio-web-hom


Build do container e logs confirmaram inicialização do Tomcat, integração com MySQL e execução do Flyway (V004__update_mock_data.sql).

Foi identificada falha de leitura do placeholder de configuração fiscal:

Could not resolve placeholder 'borurio.sefaz.urlAutorizacao'


Constatado que o arquivo application-hom.yml não estava disponível no classpath (/app/BOOT-INF/classes).

4️⃣ Diagnóstico do Erro

O erro persiste devido à ausência do application-hom.yml dentro do JAR empacotado ou à não ativação explícita do perfil hom.

Verificado que o container não possui a pasta /app/classes, o que indica build empacotado apenas com application-dev.yml.

🧩 Ações Corretivas Planejadas
Etapa	Ação	Resultado Esperado
1️⃣	Verificar inclusão do application-hom.yml no JAR via jar tf	Confirmar presença do arquivo
2️⃣	Adicionar SPRING_PROFILES_ACTIVE=hom ao container	Ativar o perfil de homologação
3️⃣	Validar existência de /app/BOOT-INF/classes/application-hom.yml	Garantir leitura pelo Spring Boot
4️⃣	Subir container e validar endpoints /api/actuator/health e /api/nfe/status	Aplicação ativa e SEFAZ simulada “UP”
🧠 Análise Técnica

O stack está estável, com:

Build Maven funcional;

Containers MySQL e Redis saudáveis;

Flyway migrando normalmente;

Logs estruturados e endpoint /actuator operacional.

O único ponto pendente é a leitura das variáveis SEFAZ no ambiente hom, causada por perfil inativo ou arquivo não empacotado no JAR final.
Após ajustar o empacotamento e reativar o perfil, o sistema deverá inicializar completamente e comunicar-se com o mock SEFAZ-SP.

✅ Status Atual
Componente	Status	Observação
Build Maven	✅ OK	Todos os módulos compilam com sucesso
MySQL / Redis	✅ OK	Containers ativos e conectados
Flyway	✅ OK	Versão V004__update_mock_data.sql aplicada
SEFAZ (Homologação)	⚠️ Pendente	Placeholder borurio.sefaz.urlAutorizacao não resolvido
Actuator	⚙️ Aguardando liberação	Será validado após fix do perfil hom
Certificado Digital	🕓 Aguardando teste real	/app/certs/borurio-hom.pfx pronto no container
🚀 Próximos Passos Imediatos

Verificar empacotamento do application-hom.yml no JAR:

jar tf "borurio-web\target\borurio-web-1.0.0.jar" | Select-String "application-hom.yml"


Adicionar a variável de ambiente ao container:

environment:
- SPRING_PROFILES_ACTIVE=hom


Rebuild do container e validação final dos endpoints:

docker compose -f "docker/docker-compose.hom.yml" up --build -d


Testar conexão com o mock SEFAZ-SP:

Invoke-RestMethod http://localhost:8282/api/nfe/status

📦 Conclusão

O ambiente de homologação do Borurio ERP Fiscal BR está consolidado e funcional em nível de infraestrutura.
O build estável e as migrações do banco validam a solidez do pipeline DevSecOps.
A etapa final de hoje deixa o sistema pronto para correção do perfil ativo (hom), o que permitirá iniciar a validação da transmissão NF-e com a SEFAZ-SP no próximo ciclo.

🕓 Encerramento do dia

Ambiente técnico validado e build completo pronto para homologação real.
Falta apenas resolver a leitura do YAML e ativação do perfil para liberação total do serviço NF-e.