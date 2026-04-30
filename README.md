# 🚀 **Borurio ERP Fiscal BR**

**ERP Fiscal Nacionalizado — Integração SEFAZ-SP (NF-e 4.00)**

O **Borurio ERP Fiscal BR** é um sistema modular desenvolvido em **Java 17 / Spring Boot 3.3.2**, projetado para automação fiscal, emissão e controle de **Notas Fiscais Eletrônicas (NF-e)**, 100% compatível com a **SEFAZ-SP**.

A solução segue padrões **DevSecOps**, com ênfase em **segurança, rastreabilidade e automação CI/CD** (GitHub Actions + Docker Compose), integrando múltiplos módulos de negócio, certificação digital (A1) e monitoramento de serviços fiscais.

---

## 🧬 **Arquitetura Modular**

| Módulo             | Descrição                                                                                   | Artefato                   |
| ------------------ | ------------------------------------------------------------------------------------------- | -------------------------- |
| **borurio-core**   | Núcleo compartilhado (enums, padrões, utilitários, resposta padrão `{code, message, data}`) | `borurio-core-1.0.0.jar`   |
| **borurio-app**    | Camada de negócios (usuários, permissões, cadastros básicos, entidades comuns)              | `borurio-app-1.0.0.jar`    |
| **borurio-fiscal** | Módulo fiscal — NF-e 4.00 (envio, retorno, status, logs, certificado digital, XSD SEFAZ-SP) | `borurio-fiscal-1.0.0.jar` |
| **borurio-web**    | API REST principal (autenticação JWT, controladores fiscais, Swagger/OpenAPI 3)             | `borurio-web-1.0.0.jar`    |

---

## ⚙️ **Stack Tecnológica**

* **Linguagem:** Java 17
* **Framework:** Spring Boot 3.3.2
* **Mapper:** MyBatis / MyBatis-Plus
* **Banco de Dados:** MySQL 8.4
* **Cache:** Redis 7.2
* **Storage:** MinIO
* **Migração de Banco:** Flyway 10.19
* **Documentação:** Swagger / SpringDoc OpenAPI 3
* **Segurança:** Spring Security + JWT
* **Logs:** Logback (configurações por ambiente DEV / HOM / PRD)
* **Build & Testes:** Maven + Jacoco (meta ≥ 80%)
* **CI/CD:** GitHub Actions (`ci-devsecops.yml`, `cd-docker.yml`)
* **Containerização:** Docker Compose (ambientes dev, hom, prd)

---

## 🧱 **Estrutura de Diretórios**

```plaintext
borurio-erp-br/
│
├── borurio-core/          → Núcleo comum (respostas, enums, padrões MVC)
├── borurio-app/           → Lógica de negócios e persistência base
├── borurio-fiscal/        → Integração NF-e 4.00 (SEFAZ-SP)
├── borurio-web/           → API REST principal e autenticação JWT
│
├── docker/                → Ambientes Docker (DEV, HOM, PRD)
│   ├── docker-compose.dev.yml
│   ├── docker-compose.hom.yml
│   ├── docker-compose.yml (produção)
│   ├── .env.dev / .env.hom / .env.prd
│   └── certificados/, mysql/, redis/, minio/
│
├── certificados/          → Certificados digitais ICP-Brasil (.pfx, .cer)
├── docs/                  → Relatórios técnicos e documentação
├── sql/                   → Scripts SQL e migrações (Flyway)
├── logs/                  → Logs de execução e auditoria fiscal
├── scripts/               → Automação (PowerShell, shell scripts)
└── pom.xml                → Reactor POM Maven principal
```

---

## 🧮 **Ambientes Docker**

> Todos os comandos executados a partir da **raiz do projeto** (`borurio-erp-br/`).
> Pré-requisito: criar `docker/env/.env.<ambiente>` a partir do template correspondente.

### 🔹 Desenvolvimento (DEV)

```bash
# Validar configuração antes de subir
docker compose -f docker/docker-compose.dev.yml --env-file docker/env/.env.dev config

# Subir ambiente
docker compose -f docker/docker-compose.dev.yml --env-file docker/env/.env.dev up -d --build
```

* Porta principal: **8080**
* Certificado: `docker/certs/pfx/certificado-jcho.pfx`

---

### 🔹 Homologação (HOM)

```bash
# Validar configuração antes de subir (não inicia containers)
docker compose -f docker/docker-compose.hom.yml --env-file docker/env/.env.hom config

# Subir ambiente HOM
docker compose -f docker/docker-compose.hom.yml --env-file docker/env/.env.hom up -d --build
```

* Ambiente SEFAZ-SP (`tpAmb=2`)
* Porta principal: **8081**
* Certificado: `docker/certs/pfx/certificado-jcho.pfx`
* Logs: `/var/log/borurio/borurio-hom.log`

---

### 🔹 Produção (PRD)

```bash
# Validar configuração antes de subir (não inicia containers)
docker compose -f docker/docker-compose.prd.yml --env-file docker/env/.env.prd config

# Subir ambiente PRD
docker compose -f docker/docker-compose.prd.yml --env-file docker/env/.env.prd up -d --build
```

* Ambiente SEFAZ-SP (`tpAmb=1` — produção real)
* Porta principal: **8282**
* Actuator: **8281**
* Certificado: `/app/certificados/certificado-prd.pfx`
* Logs:

    * `/var/log/borurio/borurio-prd.log`
    * `/var/log/borurio/sefaz-integration.log`

---

## 🔐 **Segurança e Autenticação**

* Autenticação via **JWT (JSON Web Token)**
* Implementações: `AuthController`, `JwtUtil`, `JwtFilter`
* Usuário padrão: configurável via base de dados ou variáveis de ambiente

**Endpoints públicos:**

```
/auth/login
/swagger-ui/**
/v3/api-docs/**
/actuator/**
```

**Endpoints protegidos:**
Requerem header:

```
Authorization: Bearer <token>
```

---

## 🧲 **Integração Fiscal — SEFAZ-SP NF-e 4.00**

### WebServices suportados:

* `NFeAutorizacao4.asmx`
* `NFeRetAutorizacao4.asmx`
* `NFeStatusServico4.asmx`
* `NFeConsultaProtocolo4.asmx`
* `NFeInutilizacao4.asmx`

### Certificados Digitais:

* **Tipo:** A1 (PFX)
* **Carregamento:** dinâmico via `CertificadoServiceImpl`
* **Armazenamento:** `/app/certificados/`

### Validação XML:

Consolidada via schema oficial:

```
borurio-fiscal/src/main/resources/xsd/custom/nfe_v4.00_consolidado.xsd
```

### Logs fiscais:

* Banco: `nfe_log`
* Arquivo: `/var/log/borurio/sefaz-integration.log`

---

## 📊 **Monitoramento e Observabilidade**

* Health Check: `/actuator/health`
* Swagger UI: [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html)
* OpenAPI JSON: `/v3/api-docs`
* Logs estruturados: `logback-dev.xml` / `logback-prd.xml`
* Jacoco Coverage: meta ≥ 80%

---

## 🥪 **Testes**

Executar todos os módulos:

```powershell
mvn clean test
```

Somente o módulo fiscal:

```powershell
mvn clean install -pl borurio-fiscal -am -DskipTests
```

Smoke Test (DEV):

```bash
curl http://localhost:8080/api/test/ping
curl http://localhost:8080/actuator/health
```

---

## 🚀 **Deploy e Versionamento**

| Ambiente | Descrição                             | Tag                           |
| -------- | ------------------------------------- | ----------------------------- |
| **DEV**  | Desenvolvimento e integração local    | `v3.5.0-dev`                  |
| **HOM**  | Homologação SEFAZ-SP (tpAmb=2)        | `v3.5.0-homologacao-sefaz-ok` |
| **PRD**  | Produção (tpAmb=1) com certificado A1 | `v3.5.1-producao`             |

---

## 📚 **Relatórios Técnicos**

Relatórios de progresso e validação disponíveis em:

```
/docs/report/Relatorio_Tecnico_DD-MM.md
```

Exemplos:

* `Relatorio_Tecnico_06-11.md`
* `Relatorio_Tecnico_07-11.md`

---

## 🗾 **Próximos Passos**

1. Importar Certificado A1 ICP-Brasil para emissão real NF-e
2. Implementar cancelamento e inutilização (SEFAZ-SP)
3. Integrar consultas de protocolo e auditoria fiscal
4. Criar dashboard de monitoramento (API + logs SEFAZ)
5. Testes de carga e observabilidade (Actuator + Redis)

---

## 👤 **Autor**

**Bruno Ribeiro**
Desenvolvedor Fullstack / DevSecOps
📍 São Paulo — Brasil
📧 *(inserir e-mail profissional se desejar)*

---

## 🛡️ **Licença**

> Sistema de uso interno restrito.
> Todos os direitos reservados © 2025 — **Borurio ERP Fiscal BR**
> Repositório oficial: [github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR](https://github.com/BrunoDev-Fullstack/Borurio-ERP-Fiscal-BR)
