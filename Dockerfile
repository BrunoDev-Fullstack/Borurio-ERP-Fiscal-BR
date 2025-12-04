# =============================================================================
# Dockerfile - Borurio ERP Fiscal BR (Raiz Unificado)
# =============================================================================
# Módulos: Core | App | Fiscal | Web
# Stack: Java 17 | Spring Boot 3.3.x | MyBatis | Flyway | Redis | MySQL
# Padrões: Multi-stage | DevSecOps | Least Privilege | CI/CD Ready
# Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
# Versão: 3.4.4 (Revisado)
# Última revisão: 04/12/2025
# =============================================================================


# -----------------------------------------------------------------------------
# ETAPA 1 — BUILDER (MAVEN)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
LABEL stage="builder" maintainer="Bruno Ribeiro <dev@borurio.com.br>"

WORKDIR /build

# Cache Maven
VOLUME /root/.m2

# Copia apenas os POMs para maximizar cache de dependências
COPY pom.xml ./
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

# Baixa dependências e plugins
RUN mvn -B dependency:go-offline -DskipTests -Ddependency-check.skip=true

# Copia o código completo
COPY . .

# Build completo dos módulos
RUN mvn -B -am clean package -DskipTests -Ddockerfile.skip=true -Ddependency-check.skip=true



# -----------------------------------------------------------------------------
# ETAPA 2 — RUNTIME (IMAGEM FINAL)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime
LABEL stage="runtime" maintainer="Bruno Ribeiro <dev@borurio.com.br>"

# Instala dependências essenciais
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates tzdata && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Criação do usuário e diretórios essenciais
RUN set -eux; \
    groupadd -r borurio && useradd -r -g borurio borurio && \
    mkdir -p /app /var/log/borurio /opt/borurio && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio && \
    chmod -R 770 /app /var/log/borurio /opt/borurio && \
    ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone

WORKDIR /app
USER borurio

# Copia os artefatos JAR para dentro da imagem final
COPY --from=builder /build/borurio-core/target/borurio-core-1.0.0.jar ./borurio-core.jar
COPY --from=builder /build/borurio-app/target/borurio-app-1.0.0.jar ./borurio-app.jar
COPY --from=builder /build/borurio-fiscal/target/borurio-fiscal-1.0.0.jar ./borurio-fiscal.jar
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar ./borurio-web.jar


# -----------------------------------------------------------------------------
# VARIÁVEIS DE AMBIENTE PADRÃO
# (dev → sobrescrito por compose em hom/prd)
# -----------------------------------------------------------------------------
ENV TZ=${TZ:-America/Sao_Paulo} \
    SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev} \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:mysql://borurio-mysql-dev:3306/borurio_fiscal_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo} \
    SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-borurio} \
    SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD:-H4ck3r123} \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME="com.mysql.cj.jdbc.Driver" \
    SPRING_REDIS_HOST=${SPRING_REDIS_HOST:-borurio-redis-dev} \
    SPRING_REDIS_PORT=${SPRING_REDIS_PORT:-6379} \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,info,metrics" \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="always" \
    LOG_PATH="/var/log/borurio" \
    CONFIG_PATH="/opt/borurio"


# -----------------------------------------------------------------------------
# PATCH PRD — PERMISSÕES DE LOG (compativel com Windows)
# -----------------------------------------------------------------------------
USER root
RUN mkdir -p /var/log/borurio && chmod -R 777 /var/log/borurio
USER borurio


# -----------------------------------------------------------------------------
# LIMPEZA FINAL
# -----------------------------------------------------------------------------
USER root
RUN rm -rf /tmp/* /var/tmp/* /root/.m2/repository || true && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio && \
    chmod -R 770 /app /var/log/borurio /opt/borurio
USER borurio


# -----------------------------------------------------------------------------
# HEALTHCHECK E ENTRYPOINT
# -----------------------------------------------------------------------------
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar borurio-web.jar"]


# =============================================================================
# FIM DO DOCKERFILE
# =============================================================================
