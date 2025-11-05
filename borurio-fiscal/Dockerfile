# =============================================================================
# Dockerfile - Borurio Fiscal (NF-e / Auditoria Fiscal)
# =============================================================================
# Módulo: borurio-fiscal
# Ambientes: Desenvolvimento | Homologação | Produção
# Stack: Java 17 | Spring Boot 3.3.x | MyBatis | MySQL 8.4 | Redis | Flyway
# Padrões: DevSecOps | Multi-stage Build | Least Privilege | CI/CD Ready
# Autor: Bruno Ribeiro – Desenvolvedor Fullstack / Cybersecurity
# Versão: 3.4.2
# =============================================================================

# -----------------------------------------------------------------------------
# ETAPA 1 — BUILD COM MAVEN (COMPILAÇÃO E EMPACOTAMENTO)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
LABEL stage="builder" maintainer="Bruno Ribeiro <dev@borurio.com.br>"

WORKDIR /build

# Copia apenas os POMs (cache de dependências Maven)
COPY pom.xml ./
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

# Pré-download de dependências (modo offline)
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true" -DskipTests

# Copia o código-fonte completo após cache
COPY . .

# Argumento de build (perfil padrão: dev)
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# Compila apenas o módulo fiscal e empacota em JAR executável
RUN mvn -B -pl borurio-fiscal -am clean package spring-boot:repackage \
    -DskipTests -D"dependency-check.skip=true"

# -----------------------------------------------------------------------------
# ETAPA 2 — RUNTIME (IMAGEM OTIMIZADA E ENDURECIDA)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime
LABEL stage="runtime" maintainer="Bruno Ribeiro <dev@borurio.com.br>"

# Atualiza pacotes essenciais e remove caches após instalação
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates tzdata && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Criação de diretórios e usuário não-root
RUN mkdir -p /app /var/log/borurio /opt/borurio/certs && \
    groupadd -r borurio && useradd -r -g borurio borurio

WORKDIR /app

# Copia o artefato JAR do builder
COPY --from=builder /build/borurio-fiscal/target/borurio-fiscal-1.0.0.jar /app/borurio-fiscal.jar

# -----------------------------------------------------------------------------
# VARIÁVEIS DE AMBIENTE — PADRÕES DE EXECUÇÃO SEGURA
# -----------------------------------------------------------------------------
ENV TZ=America/Sao_Paulo \
    SPRING_PROFILES_ACTIVE=dev \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_URL="jdbc:mysql://borurio-mysql-dev:3306/borurio_fiscal_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_USERNAME="borurio" \
    SPRING_DATASOURCE_PASSWORD="H4ck3r123" \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME="com.mysql.cj.jdbc.Driver" \
    SPRING_REDIS_HOST="borurio-redis-dev" \
    SPRING_REDIS_PORT="6379" \
    SPRING_FLYWAY_ENABLED="true" \
    SPRING_FLYWAY_VALIDATE_ON_MIGRATE="true" \
    SPRING_JACKSON_DEFAULT_PROPERTY_INCLUSION="non_null" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,info" \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="always" \
    LOG_PATH="/var/log/borurio" \
    CERTS_PATH="/opt/borurio/certs"

# -----------------------------------------------------------------------------
# HARDENING DEVSECOPS
# -----------------------------------------------------------------------------
# - Remove diretórios temporários e permissões excessivas
# - Define usuário não-root
RUN chown -R borurio:borurio /app /var/log/borurio /opt/borurio/certs && \
    chmod 750 /app /var/log/borurio /opt/borurio/certs

USER borurio

# -----------------------------------------------------------------------------
# HEALTHCHECK E PORTA PADRÃO
# -----------------------------------------------------------------------------
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1

# -----------------------------------------------------------------------------
# ENTRYPOINT — EXECUÇÃO DO MÓDULO FISCAL
# -----------------------------------------------------------------------------
ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/borurio-fiscal.jar"]

# =============================================================================
# FIM DO DOCKERFILE
# =============================================================================
