# 🚀 Borurio ERP Fiscal BR

**ERP Fiscal Nacionalizado – Integração SEFAZ-SP (NF-e 4.00)**

Desenvolvido em **Java 17 / Spring Boot 3.3.2**, o **Borurio ERP Fiscal BR** é um sistema modular voltado à gestão e automação fiscal de NF-e (Nota Fiscal Eletrônica), 100% nacionalizado e preparado para comunicação segura com a SEFAZ-SP via certificado digital A1.  
A solução foi projetada seguindo padrões DevSecOps e boas práticas de microserviços, observabilidade e integração contínua (CI/CD Docker + GitHub Actions).

---

## 🧩 Estrutura Modular

| Módulo | Descrição | Artefato |
|--------|------------|-----------|
| **borurio-core** | Núcleo compartilhado (enums, padrões, utilitários, resposta padrão `{code, message, data}`) | `borurio-core-1.0.0.jar` |
| **borurio-app** | Camada de negócios e entidades comuns (usuários, permissões, cadastros básicos) | `borurio-app-1.0.0.jar` |
| **borurio-fiscal** | Módulo responsável pelas integrações NF-e (envio, retorno, status, logs, certificado A1, XSD) | `borurio-fiscal-1.0.0.jar` |
| **borurio-web** | API REST principal (autenticação, endpoints públicos, controllers fiscais, Swagger/OpenAPI) | `borurio-web-1.0.0.jar` |

---

## ⚙️ Stack Tecnológica

- **Linguagem:** Java 17
- **Framework:** Spring Boot 3.3.2
- **ORM / Mapper:** MyBatis-Plus
- **Banco de Dados:** MySQL 8.4
- **Cache:** Redis 7.2
- **Mensageria / Storage:** MinIO
- **Migração de Banco:** Flyway
- **Logs:** Logback (ambientes DEV, HOM, PRD)
- **Documentação:** Swagger / SpringDoc OpenAPI 3
- **Build e Testes:** Maven + Jacoco
- **CI/CD:** GitHub Actions (`ci-devsecops.yml`, `cd-docker.yml`)
- **Containerização:** Docker Compose (dev / hom / prd)

---

## 🧱 Estrutura de Pastas (Resumo)

borurio-erp-br/
│
├── borurio-core/ → Núcleo comum (respostas, enums, padrões MVC)
├── borurio-app/ → Lógica de negócios e persistência base
├── borurio-fiscal/ → Integração SEFAZ-SP (NF-e 4.00)
├── borurio-web/ → API REST principal e autenticação JWT
│
├── docker/ → Ambientes Docker (DEV, HOM, PRD)
│ ├── docker-compose.dev.yml
│ ├── docker-compose.hom.yml
│ ├── docker-compose.yml (produção)
│ ├── env/.env.dev, .env.hom, .env.prd
│ └── certificados/, mysql/, redis/, minio/
│
├── certificados/ → Certificados digitais e cadeias ICP-Brasil
├── docs/ → Relatórios técnicos e documentação
├── sql/ → Scripts SQL (schema e tabelas fiscais)
├── logs/ → Logs de execução (aplicação e integração SEFAZ)
├── scripts/ → Automação PowerShell (importação, migração etc.)
└── pom.xml → Projeto Maven principal (Reactor POM)

yaml
Copiar código

---

## 🧰 Ambientes Docker

### 🔹 Desenvolvimento (DEV)
```powershell
cd "docker"
docker-compose -f "docker-compose.dev.yml" up -d
Porta principal: 8080

Actuator: 8081

Fiscal mock: 8082

Certificado: certificados/certificado-hom.pfx

🔹 Homologação (HOM)
powershell
Copiar código
docker-compose -f "docker-compose.hom.yml" up -d
Ambiente SEFAZ-SP (tpAmb=2)

Certificado A1 de homologação

Logs: /var/log/borurio/borurio-web-hom.log

🔹 Produção (PRD)
powershell
Copiar código
docker-compose -f "docker-compose.yml" up -d --build
Porta principal: 8282

Actuator: 8281

Certificado: /app/certificados/certificado-prd.pfx

Logs:

/var/log/borurio/borurio-prd.log

/var/log/borurio/sefaz-integration.log

🔐 Segurança e Autenticação
Autenticação JWT (AuthController, JwtUtil, JwtFilter)

Usuário padrão configurado via base ou variável de ambiente

Endpoints abertos:

bash
Copiar código
/auth/login
/swagger-ui/**
/v3/api-docs/**
/actuator/**
Endpoints protegidos exigem token JWT no header:

makefile
Copiar código
Authorization: Bearer <token>
🧾 Módulo Fiscal – SEFAZ-SP NF-e 4.00
Integração direta com os WebServices SEFAZ-SP:

NFeAutorizacao4.asmx

NFeRetAutorizacao4.asmx

NFeStatusServico4.asmx

Certificado A1 (SSLContext) carregado dinamicamente pelo CertificadoServiceImpl

Validação XML via XSD consolidado:

swift
Copiar código
borurio-fiscal/src/main/resources/xsd/custom/nfe_v4.00_consolidado.xsd
Registro completo de logs no banco (nfe_log) e arquivo:

lua
Copiar código
/var/log/borurio/sefaz-integration.log
📊 Observabilidade
Health Check: /actuator/health

Swagger UI: http://localhost:8080/swagger-ui/index.html

Logs estruturados: logback-dev.xml / logback-prd.xml

Jacoco Coverage: meta ≥ 80%

🧪 Testes
Executar localmente:

powershell
Copiar código
mvn clean test
Executar somente o módulo fiscal:

powershell
Copiar código
mvn clean install -pl borurio-fiscal -am -DskipTests
🚀 Deploy e Versionamento
Etapa	Descrição	Tag
DEV	Ambientes de desenvolvimento e integração local	v3.4.0-dev
HOM	Homologação SEFAZ-SP (tpAmb=2) validada	v3.4.0-homologacao-sefaz-ok
PRD	Produção real (tpAmb=1) com certificado A1	v3.4.1-producao

📚 Relatórios Técnicos
Relatórios diários de desenvolvimento e homologação disponíveis em:

bash
Copiar código
/docs/report/Relatorio_Tecnico_DD-MM.md
Exemplo:

Relatório Técnico – 28/10/2025

Relatório Técnico – 29/10/2025

🧭 Próximos Passos
Concluir validação de produção SEFAZ-SP (tpAmb=1)

Emitir NF-e real com certificado A1

Integrar consultas de protocolo, cancelamento e inutilização

Implementar dashboard de monitoramento fiscal

👤 Autor
Bruno Ribeiro
Desenvolvedor Fullstack / DevSecOps
📧 contato: (pode inserir seu e-mail profissional)
📍 São Paulo, Brasil

🛡️ Licença
Este projeto é de uso interno e controlado.
Todos os direitos reservados © 2025 – Borurio ERP Fiscal BR