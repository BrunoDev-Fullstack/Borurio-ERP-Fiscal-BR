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