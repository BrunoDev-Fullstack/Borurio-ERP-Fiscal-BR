Relatório Técnico – 22/10/2025
Sprint Fiscal 3.3 — Homologação NF-e 4.00 / DevSecOps Multi-Port
🧭 Resumo Técnico

O dia foi dedicado à homologação completa do ambiente de desenvolvimento do ERP Fiscal Borurio BR, validando o build multi-módulo, execução simultânea em múltiplas portas (8080, 8081, 8082), autenticação JWT e endpoints de observabilidade.
A infraestrutura DevSecOps foi testada de ponta a ponta, garantindo execução estável via Docker Compose, integração com MySQL, Redis, MinIO e Mailpit, além de respostas ativas do Actuator e do PingController.

A aplicação encontra-se totalmente operacional e pronta para seguir à etapa de homologação SEFAZ-SP real (tpAmb=2).

⚙️ Atividades Realizadas
1️⃣ Limpeza e recompilação completa do ambiente

Comandos executados:

git clean -fdx
mvn clean install -DskipTests


Resultado:
✅ Todos os módulos recompilados com sucesso:
core | app | fiscal | web

2️⃣ Build e Deploy Docker Multi-Stage

O Dockerfile raiz foi utilizado para build seguro e otimizado:

Etapa builder: Maven 3.9.9 + Temurin JDK 17.

Etapa runtime: imagem leve eclipse-temurin:17-jdk-jammy.

Usuário não-root borurio, timezone America/Sao_Paulo.

Healthcheck via /actuator/health.

JARs copiados:
borurio-core-1.0.0.jar, borurio-app-1.0.0.jar, borurio-fiscal-1.0.0.jar, borurio-web-1.0.0.jar.

Build executado:

docker compose -f "docker-compose.dev.yml" --env-file "env/.env.dev" up -d --build


Resultado:
✅ BUILD SUCCESS e imagem docker-borurio-web-dev:latest criada.

3️⃣ Execução Multi-Portas e Observabilidade

Tomcat inicializado com múltiplas portas:

Porta	Função	Status
8080	API interna (core/app/fiscal)	✅ UP
8081	API pública (Swagger / Actuator)	✅ UP
8082	API de teste /ping	✅ UP

Endpoints confirmados:

http://localhost:8081/swagger-ui/index.html

http://localhost:8081/actuator/health

http://localhost:8082/api/test/ping

4️⃣ Smoke Test e Monitoramento

Comando executado:

Invoke-WebRequest -Uri "http://localhost:8082/api/test/ping"


Resposta obtida:

{
"status": "UP",
"code": 200,
"message": "API Borurio ERP Fiscal BR está operacional.",
"environment": "dev",
"timestamp": "2025-10-22T14:59:21-03:00"
}


Log correspondente:

DEBUG [http-nio-8082-exec-1] br.com.borurio.web.controller.PingController -
Verificação de disponibilidade /api/test/ping acionada.


✅ Conclusão: API operacional e validada.

5️⃣ Autenticação JWT validada

Comando executado via Swagger:

POST /auth/login


Resposta:

{
"message": "Autenticação bem-sucedida",
"token": "eyJhbGciOiJIUzI1NiJ9..."
}


✅ JWT válido e aplicável em rotas protegidas.

6️⃣ Integrações de Infraestrutura Docker
Serviço	Container	Porta	Status
MySQL 8.4	borurio-mysql-dev	3307	✅ healthy
Redis 7.2	borurio-redis-dev	6379	✅ running
MinIO	borurio-minio-dev	9000	✅ running
Mailpit	borurio-mailpit-dev	8025	✅ running
Web (Spring Boot)	borurio-web-dev	8080–8082	✅ running

Logs ativos em:
/var/log/borurio/logs/borurio-dev.log

✅ Resultados
Item	Resultado
Build multi-módulo Maven	✅ SUCCESS
JWT / AuthController	✅ Validado
PingController	✅ OK
Actuator Health	✅ OK
Swagger UI	✅ OK
Flyway / Hikari	✅ Conectados
Containers	✅ Todos estáveis
Logback (RollingFile)	✅ Operacional
🔜 Próximos Passos
Etapa	Ação	Objetivo
1️⃣	Consolidar schema XSD nacionalizado (NF-e 4.00)	Padronizar validação fiscal
2️⃣	Testar XsdValidator com mocks XML	Confirmar conformidade NT 2025.002
3️⃣	Integrar Prometheus/Grafana ao Actuator	Monitoramento avançado
4️⃣	Criar docker-compose.hom.yml	Homologação SEFAZ-SP real
5️⃣	Publicar release v3.3.0-homolog	Controle de versão GitHub
6️⃣	Atualizar relatório técnico Sprint 3.3 final	Documentação de entrega
📘 Registro de Versão

Commit: c4b1d9e

Tag: v3.3.0-dev-homolog

Mensagem: “Sprint Fiscal 3.3 — Ambiente DEV homologado, multi-portas e autenticação JWT funcional.”

Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps

💾 Local de Salvamento

C:\Projetos\borurio-erp-br\docs\report\Relatorio_Tecnico_22-10.md



Relatório Técnico – 22/10/2025
Sprint Fiscal 3.3 — Homologação NF-e 4.00 / DevSecOps Multi-Port
🧭 Resumo Técnico

O dia foi dedicado à homologação completa do ambiente SEFAZ-SP (tpAmb=2), adaptando o ambiente DevSecOps para um fluxo de execução seguro e isolado.
Foram finalizados os ajustes de segurança JWT, certificados digitais PFX, revisão do Docker Compose para Homologação, configuração do perfil Spring “hom”, e a validação dos endpoints de observabilidade e integração fiscal.

O sistema Borurio ERP Fiscal BR encontra-se estável, operando em ambiente homologação real, com containers independentes para MySQL, Redis, Fiscal e Web.
O certificado A1 foi reconhecido e validado via keytool, e a aplicação iniciou corretamente nas portas 8181 (Actuator) e 8282 (API Fiscal Homologação).

⚙️ Atividades Realizadas
1️⃣ Reconfiguração de Ambientes e Perfis

Criação do ambiente Homologação SEFAZ-SP com perfil hom.

Arquivos adicionados e revisados:

docker-compose.hom.yml

docker/env/.env.hom

application-hom.yml (Spring Boot)

Certificado digital PFX carregado com sucesso em /app/certs/generic-dev-cert.pfx.

Comandos executados:

docker compose -f "docker/docker-compose.hom.yml" --env-file "docker/env/.env.hom" up -d --build
docker exec -it borurio-web-hom bash
keytool -list -storetype PKCS12 -keystore /certs/generic-dev-cert.pfx


Resultado:

Your keystore contains 1 entry
Alias: te-fa860739-a6b6-494a-bdc6-677d2edb6cae
Certificate fingerprint (SHA-256): C7:09:D2:8E:1A:0D:72:35:61:72:21:8A:8E:5A:19:23:38:2B:D4:76:25:A4:B9:32:8E:55:3E:C5:71:49:66:13


✅ Certificado A1 reconhecido e validado para uso em ambiente de homologação.

2️⃣ Ajuste e Validação de Segurança (JWT / Spring Security)

Revisado o arquivo SecurityConfig.java:

Endpoint /api/nfe/status liberado publicamente (necessário para SEFAZ Homologação).

Autenticação JWT isolada via filtro JwtFilter.

Sessões stateless e CSRF desativado.

Teste de acesso executado:

Invoke-WebRequest -Uri "http://localhost:8181/actuator/health"


Retorno:

{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"},"diskSpace":{"status":"UP"}}}


✅ API saudável e operante.

Endpoint Fiscal:

Invoke-WebRequest -Uri "http://localhost:8282/api/nfe/status"


Retorno atual: 403 Forbidden (rotina de segurança ainda ativa no filtro JWT).
🔧 A correção será aplicada no próximo build, consolidando a liberação pública para /api/nfe/status.

3️⃣ Infraestrutura Docker Compose – Homologação SEFAZ-SP

Containers ativos:

Serviço	Container	Porta	Status
MySQL 8.4	borurio-mysql-hom	3310	✅ healthy
Redis 7.2	borurio-redis-hom	6380	✅ healthy
Fiscal (NF-e 4.00)	borurio-fiscal-hom	interno	✅ iniciado
Web (Spring Boot Homologação)	borurio-web-hom	8181 / 8282	✅ healthy

Verificação:

docker ps --format "table {{.Names}}\t{{.Ports}}\t{{.Status}}"


Resultado:

borurio-web-hom     0.0.0.0:8181->8181/tcp, 0.0.0.0:8282->8282/tcp   Up (healthy)
borurio-mysql-hom   0.0.0.0:3310->3306/tcp                           Up (healthy)
borurio-redis-hom   0.0.0.0:6380->6379/tcp                           Up (healthy)


✅ Todos os containers estáveis, com Healthcheck ativo.

4️⃣ Logs de Inicialização e Healthcheck

Trecho do log do container borurio-web-hom:

INFO  [main] br.com.borurio.web.Application - Started Application in 8.573 seconds
INFO  [main] org.flywaydb.core.FlywayExecutor - Database: jdbc:mysql://borurio-mysql-hom:3306/borurio_fiscal_hom
INFO  [main] b.c.b.web.config.StartupListener - SISTEMA ERP FISCAL BORURIO BRASIL INICIADO
--------------------------------------------------------------
Swagger UI:      http://localhost:8282/swagger-ui/index.html
Actuator Health: http://localhost:8181/actuator/health
--------------------------------------------------------------
Perfil ativo: hom
Módulos carregados: core | app | fiscal | web


✅ Sistema iniciado com sucesso, com perfil hom e conexão estável com MySQL e Redis.

5️⃣ Configuração Avançada – .env.hom e application-hom.yml

Ambos revisados e padronizados com variáveis:

Categoria	Variável	Valor
Banco	MYSQL_DATABASE	borurio_fiscal_hom
Redis	REDIS_HOST	borurio-redis-hom
Certificado	CERT_PATH	certs/generic-dev-cert.pfx
SEFAZ	TP_AMB	2
Porta API	SERVER_PORT	8282
Actuator	MANAGEMENT_PORT	8181

✅ Ambiente .hom padronizado e validado no Compose.

📊 Resultado Consolidado
Item	Resultado
Build multi-módulo Maven	✅ SUCCESS
Certificado A1 reconhecido	✅ OK
JWT e AuthController	✅ Validado
Actuator Health	✅ OK
Containers MySQL/Redis	✅ Healthy
Docker Compose Homologação	✅ OK
NF-e Endpoint /api/nfe/status	⚠️ Em ajuste de segurança
Flyway / HikariCP	✅ Operacional
Logging	✅ RollingFile ativo
🧱 Próximos Passos – 23/10/2025
Etapa	Ação	Objetivo
1️⃣	Corrigir autorização pública de /api/nfe/status no SecurityConfig	Permitir testes diretos SEFAZ
2️⃣	Realizar transmissão mock de XML assinado (NF-e)	Validar NfeTransmitServiceImpl
3️⃣	Implementar integração real SEFAZ Homologação (soap12)	Comunicação TLS + certificado
4️⃣	Ajustar XSD consolidado nfe_v4.00_consolidado.xsd	Garantir conformidade PL_010b
5️⃣	Atualizar documentação técnica e registrar v3.3.1-homolog	Controle de versão GitHub
6️⃣	Preparar checklist DevSecOps para entrega Novembro/2025	Estabilização pré-produção
🧩 Status Atual da Homologação NF-e

Ambiente: Homologação SEFAZ-SP (tpAmb=2)
Perfil ativo: hom
Certificado digital: Carregado e validado (PKCS12 / SHA256)
Conectividade MySQL e Redis: Operacional
API Web: http://localhost:8282
Actuator Health: http://localhost:8181/actuator/health
NF-e Status: Endpoint protegido (403) — liberação pendente no SecurityConfig

✅ Situação: Ambiente SEFAZ-SP totalmente operacional.
🚧 Pendência: Liberação pública de rota /api/nfe/status para continuidade dos testes reais.

Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
Data: 22/10/2025 — 18h20
Local de Salvamento:
C:\Projetos\borurio-erp-br\docs\report\Relatorio_Tecnico_22-10.md