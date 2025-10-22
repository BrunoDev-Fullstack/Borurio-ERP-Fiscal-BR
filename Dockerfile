# =============================================================================
# Dockerfile - Borurio ERP Fiscal BR (Raiz Unificado)
# =============================================================================
# Módulos: Core | App | Fiscal | Web
# Stack: Java 17 | Spring Boot 3.3.x | MyBatis | Flyway | Redis | MySQL
# Padrões: Multi-stage | DevSecOps | Least Privilege | Healthcheck
# Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
# =============================================================================

# -----------------------------------------------------------------------------
# Etapa 1 - Build Maven (monorepo completo)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Habilita cache Maven (BuildKit)
VOLUME /root/.m2

# Copia apenas os POMs para melhor cache
COPY pom.xml .
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

# Resolve plugins Maven (rápido, sem baixar tudo)
RUN mvn -B -N dependency:resolve-plugins -DskipTests

# Copia o código-fonte completo
COPY . .

# Compila e empacota todos os módulos do reator Maven (Core, App, Fiscal, Web)
RUN mvn -B -am clean package -DskipTests -Ddockerfile.skip=true

# -----------------------------------------------------------------------------
# Etapa 2 - Runtime (imagem leve e segura)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS runtime

# Cria usuário não-root e estrutura segura
RUN set -eux; \
    groupadd -r borurio && useradd -r -g borurio borurio && \
    mkdir -p /app /var/log/borurio /opt/borurio && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio && \
    ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone

WORKDIR /app
USER borurio

# Copia os artefatos compilados
COPY --from=builder /build/borurio-core/target/borurio-core-1.0.0.jar ./borurio-core.jar
COPY --from=builder /build/borurio-app/target/borurio-app-1.0.0.jar ./borurio-app.jar
COPY --from=builder /build/borurio-fiscal/target/borurio-fiscal-1.0.0.jar ./borurio-fiscal.jar
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar ./borurio-web.jar

# -----------------------------------------------------------------------------
# Variáveis de ambiente (seguras e configuráveis)
# -----------------------------------------------------------------------------
ENV SPRING_PROFILES_ACTIVE=dev \
    TZ=America/Sao_Paulo \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8" \
    SPRING_DATASOURCE_URL="jdbc:mysql://borurio-mysql-dev:3306/borurio_fiscal_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_USERNAME="borurio" \
    SPRING_DATASOURCE_PASSWORD="H4ck3r123" \
    SPRING_REDIS_HOST="borurio-redis-dev" \
    SPRING_REDIS_PORT="6379" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,info,metrics" \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="always"

# -----------------------------------------------------------------------------
# Healthcheck e Execução
# -----------------------------------------------------------------------------
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar borurio-web.jar"]
