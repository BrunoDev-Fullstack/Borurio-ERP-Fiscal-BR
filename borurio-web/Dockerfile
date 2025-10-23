# =============================================================================
# Dockerfile - Borurio ERP Web (Gateway / API REST Principal)
# =============================================================================
# Ambiente: Desenvolvimento / Homologação / Produção
# Stack: Java 17 | Spring Boot 3.3.x | MyBatis | Redis | Flyway | Actuator
# Padrões: Multi-stage Build | DevSecOps | Least Privilege | CI/CD Ready
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack / DevSecOps
# =============================================================================

# -----------------------------------------------------------------------------
# ETAPA 1 — Build (Maven + JDK)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copia apenas os POMs para otimizar cache de dependências
COPY pom.xml .
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

# Pré-download de dependências para builds offline
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true"

# Copia o código-fonte completo
COPY . .

# Define ambiente de build (default: dev)
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# Compila os módulos internos (Core, App, Fiscal)
RUN mvn -B -pl borurio-core,borurio-app,borurio-fiscal clean install \
    -DskipTests -D"dependency-check.skip=true" -Ddockerfile.skip=true

# Empacota o módulo Web (gera o JAR executável)
RUN mvn -B -pl borurio-web -am clean package spring-boot:repackage \
    -DskipTests -D"dependency-check.skip=true" -Ddockerfile.skip=true

# -----------------------------------------------------------------------------
# ETAPA 2 — Runtime (imagem leve, segura e otimizada)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS runtime

# Criação de diretórios, timezone e usuário não-root
RUN set -eux; \
    mkdir -p /app /var/log/borurio /opt/borurio && \
    groupadd -r borurio && useradd -r -g borurio borurio && \
    ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone

WORKDIR /app

# Copia o artefato final do builder
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/borurio-web.jar

# Ajusta permissões seguras
RUN chown -R borurio:borurio /app /var/log/borurio /opt/borurio
USER borurio

# -----------------------------------------------------------------------------
# VARIÁVEIS DE AMBIENTE (sobrescritas via Compose / CI-CD)
# -----------------------------------------------------------------------------
ENV TZ=America/Sao_Paulo \
    SPRING_PROFILES_ACTIVE=dev \
    SERVER_PORT=8080 \
    SPRING_DATASOURCE_URL="jdbc:mysql://borurio-mysql-dev:3306/borurio_fiscal_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_USERNAME="borurio" \
    SPRING_DATASOURCE_PASSWORD="H4ck3r123" \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME="com.mysql.cj.jdbc.Driver" \
    SPRING_REDIS_HOST="borurio-redis-dev" \
    SPRING_REDIS_PORT="6379" \
    SPRING_FLYWAY_ENABLED="true" \
    SPRING_FLYWAY_BASELINE_ON_MIGRATE="true" \
    SPRING_JACKSON_DEFAULT_PROPERTY_INCLUSION="non_null" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,info,metrics,env,beans" \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="always" \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

# -----------------------------------------------------------------------------
# SEGURANÇA E HARDENING DEVSECOPS
# -----------------------------------------------------------------------------
USER root
RUN rm -rf /tmp/* /var/tmp/* /root/.m2/repository || true && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio
USER borurio

# -----------------------------------------------------------------------------
# HEALTHCHECK (verifica Actuator Health via variável de porta)
# -----------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fs http://localhost:${SERVER_PORT}/actuator/health || exit 1

# -----------------------------------------------------------------------------
# EXPOSIÇÃO E ENTRYPOINT
# -----------------------------------------------------------------------------
EXPOSE ${SERVER_PORT}
ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/borurio-web.jar --server.port=${SERVER_PORT}"]
