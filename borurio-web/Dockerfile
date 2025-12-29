# =============================================================================
# Dockerfile - Borurio ERP Web (Gateway / API REST Principal)
# =============================================================================
# Ambiente: Desenvolvimento | Homologação | Produção
# Stack: Java 17 | Spring Boot 3.3.x | MyBatis | Redis | NF-e | Flyway
# Padrões: Multi-stage Build | DevSecOps | Least Privilege | CI/CD Ready
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack | Cybersecurity
# Versão: 3.4.3
# =============================================================================

# -----------------------------------------------------------------------------
# ETAPA 1 — BUILD MAVEN (COMPILAÇÃO E EMPACOTAMENTO COMPLETO)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
LABEL stage="builder" maintainer="Bruno Ribeiro <dev@borurio.com.br>"

WORKDIR /build

# Copia somente POMs para aproveitar cache
COPY pom.xml ./
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

RUN mvn -B dependency:go-offline -DskipTests -D"dependency-check.skip=true"

# Copia o restante do código-fonte
COPY . .

ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# Compila módulos internos
RUN mvn -B -pl borurio-core,borurio-app,borurio-fiscal clean install \
    -DskipTests -D"dependency-check.skip=true" -Ddockerfile.skip=true

# Empacota módulo Web
RUN mvn -B -pl borurio-web -am clean package spring-boot:repackage \
    -DskipTests -D"dependency-check.skip=true" -Ddockerfile.skip=true

# -----------------------------------------------------------------------------
# ETAPA 2 — RUNTIME (IMAGEM SEGURA E LEVE)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime
LABEL stage="runtime" maintainer="Bruno Ribeiro <dev@borurio.com.br>"

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates tzdata && \
    rm -rf /var/lib/apt/lists/*

# Configura timezone + usuário restrito
RUN ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone && \
    groupadd -r borurio && useradd -r -g borurio borurio && \
    mkdir -p /app /var/log/borurio /opt/borurio/certificados && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio && \
    chmod 750 /app /var/log/borurio /opt/borurio

WORKDIR /app

# Copia JAR final
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/borurio-web.jar

# -----------------------------------------------------------------------------
# COPIA CERTIFICADOS DO PROJETO PARA DENTRO DA IMAGEM
# -----------------------------------------------------------------------------
COPY certificados /opt/borurio/certificados

# -----------------------------------------------------------------------------
# CONFIGURA TRUSTSTORE PARA SEFAZ (NF-e)
# -----------------------------------------------------------------------------
ENV JAVA_TOOL_OPTIONS="\
 -XX:+UseContainerSupport \
 -XX:MaxRAMPercentage=75.0 \
 -Dfile.encoding=UTF-8 \
 -Duser.timezone=America/Sao_Paulo \
 -Djavax.net.ssl.trustStore=/opt/borurio/certificados/truststore/sefaz-truststore.jks \
 -Djavax.net.ssl.trustStorePassword=changeit \
"

# -----------------------------------------------------------------------------
# VARIÁVEIS DE AMBIENTE (BANCO, REDIS, FLYWAY, LOGS)
# -----------------------------------------------------------------------------
ENV TZ=America/Sao_Paulo \
    SPRING_PROFILES_ACTIVE=dev \
    SPRING_DATASOURCE_URL="jdbc:mysql://borurio-mysql-dev:3306/borurio_fiscal_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_USERNAME="borurio" \
    SPRING_DATASOURCE_PASSWORD="H4ck3r123" \
    SPRING_REDIS_HOST="borurio-redis-dev" \
    SPRING_REDIS_PORT="6379" \
    SPRING_FLYWAY_ENABLED="true" \
    SPRING_FLYWAY_VALIDATE_ON_MIGRATE="true" \
    LOG_PATH="/var/log/borurio" \
    CONFIG_PATH="/opt/borurio"

# -----------------------------------------------------------------------------
# HARDENING
# -----------------------------------------------------------------------------
USER root
RUN rm -rf /tmp/* /var/tmp/* /root/.m2 || true && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio
USER borurio

# -----------------------------------------------------------------------------
# HEALTHCHECK
# -----------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1

# -----------------------------------------------------------------------------
# PORTA E ENTRYPOINT
# -----------------------------------------------------------------------------
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/borurio-web.jar"]

# =============================================================================
# FIM DO DOCKERFILE
# =============================================================================
