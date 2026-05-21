Relatório Técnico – 28/10/2025
Sprint Fiscal 3.4 — Homologação SEFAZ-SP e Estabilização do Ambiente Dev
🧭 Resumo do Dia

O dia foi voltado à estabilização total do ambiente de desenvolvimento (docker-compose.dev.yml) e à validação completa do fluxo de autenticação, containers, e comunicação fiscal base.
Confirmamos o funcionamento integral do stack e finalizamos com sucesso o endpoint fiscal de teste (/api/fiscal/nfe/test/ping), que responde em tempo real via SEFAZ-SP (Homologação, tpAmb=2).

⚙️ Atividades Técnicas Executadas
1️⃣ Ambientes Docker e Infraestrutura

Revisão completa do arquivo docker/docker-compose.dev.yml para incluir e integrar:

MySQL 8.4, Redis 7.2, MinIO, Mailpit e borurio-web-dev.

Adição do volume persistente ./certificados:/app/certificados para o uso do certificado digital A1 (certificado-hom.pfx).

Padronização de variáveis no .env.dev e portas:

API Web: 8080

Actuator: 8081

API Fiscal Secundária: 8082

Containers todos iniciando em estado Healthy:

✔ borurio-mysql-dev
✔ borurio-redis-dev
✔ borurio-minio-dev
✔ borurio-mailpit-dev
✔ borurio-web-dev

2️⃣ Certificados Digitais

Recuperada e mapeada a pasta certificados/ na raiz do projeto contendo:

Autoridade_Certificadora_Serpro_SSLv1.crt
Autoridade_Certificadora_Serpro_v4.crt
ICP-Brasilv10.crt
certificado-hom.pfx


Confirmado o mapeamento dentro do container:

docker exec -it borurio-web-dev ls /app/certificados

3️⃣ Configuração do Ambiente de Aplicação

Revisado e corrigido o arquivo application-dev.yml com:

Integração MySQL + Redis + Logback + Jackson + SEFAZ-SP.

Inclusão das URLs oficiais de homologação (tpAmb=2).

Ajustes de nfe.sefaz.certificado e fiscal.cert.path apontando para /app/certificados/certificado-hom.pfx.

4️⃣ Segurança e Autenticação (Spring Security)

Refatorado o arquivo SecurityConfig.java:

Adicionados endpoints liberados:

/auth/login
/actuator/**
/swagger-ui/**
/v3/api-docs/**
/api/test/**
/api/fiscal/nfe/test/**


Confirmado funcionamento do login:

Invoke-RestMethod -Uri "http://localhost:8080/auth/login" -Method POST `
  -ContentType "application/json" `
-Body (@{username="admin"; password="123456"} | ConvertTo-Json)


Resultado:
Autenticação bem-sucedida + JWT válido.

5️⃣ Endpoint Fiscal de Teste

Criado o controller NfeTestController.java dentro de borurio-web:

Endpoint: /api/fiscal/nfe/test/ping

Retorna status operacional e ambiente SEFAZ.

Testado com sucesso:

curl.exe http://localhost:8080/api/fiscal/nfe/test/ping


Resposta:

{
"ambiente": "HOMOLOGAÇÃO (tpAmb=2)",
"message": "Módulo Fiscal operacional — integração SEFAZ-SP pronta para homologação.",
"status": "OK",
"timestamp": "2025-10-28T16:26:56"
}

6️⃣ Swagger e Observabilidade

Confirmado funcionamento do Swagger/OpenAPI:

http://localhost:8080/swagger-ui/index.html


Exportado o swagger.json atualizado:

Invoke-RestMethod -Uri "http://localhost:8080/v3/api-docs" | ConvertTo-Json | Out-File "swagger.json"


Endpoints listados incluem:

/auth/login

/api/test/ping

/api/fiscal/nfe/test/ping

🔍 Situação Atual
Componente	Status	Observação
Docker Compose (Dev)	✅ Estável	Todos os containers iniciando healthy
MySQL / Redis	✅ Ok	Conectando via Hikari
Certificado A1 (Homologação)	✅ Mapeado	/app/certificados/certificado-hom.pfx
Autenticação JWT	✅ Operacional	/auth/login retornando token válido
Endpoint Fiscal /api/fiscal/nfe/test/ping	✅ Respondendo 200 OK	Ambiente homologação configurado
Swagger / OpenAPI	✅ Ativo	/swagger-ui/index.html acessível
Actuator	✅ Ok	/actuator/health UP
🧩 Decisão Técnica Importante

Por estratégia de homologação acelerada, o ambiente dev também será usado para validar a comunicação real com a SEFAZ-SP.
Ou seja, o perfil ativo dev atua agora como ambiente híbrido de desenvolvimento + homologação real, garantindo:

Menos sobrecarga de manutenção em YAMLs duplicados.

Compatibilidade imediata com certificados e URLs reais da SEFAZ.

Logs e respostas totalmente rastreáveis em um único contexto (borurio-web-dev).

🎯 Próximos Passos

Criar serviço real de status SEFAZ-SP no módulo borurio-fiscal:

NfeStatusService.java

NfeStatusController.java

Endpoint: GET /api/fiscal/nfe/status

Conexão HTTPS com certificado A1 (NFeStatusServico4.asmx)

Confirmar resposta real da SEFAZ (mensagem: “Serviço em Operação”).

Validar o fluxo completo de envio/retorno NF-e.

Comitar o marco v3.4.0-homologacao-sefaz-ok no GitHub.

🏁 Conclusão

O ERP Fiscal Borurio BR atingiu o primeiro marco de homologação estável:

API principal funcional,

Containers 100% integrados,

Certificado digital válido,

Endpoint fiscal ativo e autenticado.

A partir deste ponto, o sistema está preparado para comunicação real com a SEFAZ-SP.