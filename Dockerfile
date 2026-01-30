# =============================================================================
# Dockerfile - Borurio ERP Fiscal BR (Unificado / Neutro)
# =============================================================================
# Módulos: Core | App | Fiscal | Web
# Stack: Java 17 | Spring Boot 3.3.x
# Padrões: Multi-stage | DevSecOps | Least Privilege | CI/CD Ready
# Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
# Última revisão: 30/01/2026
# =============================================================================

# -----------------------------------------------------------------------------
# ETAPA 1 — BUILD MAVEN
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build
VOLUME /root/.m2

COPY pom.xml ./
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

RUN mvn -B dependency:go-offline -DskipTests

COPY . .

RUN mvn -B -am clean package -DskipTests

# -----------------------------------------------------------------------------
# ETAPA 2 — RUNTIME (IMAGEM SEGURA)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        tzdata && \
    rm -rf /var/lib/apt/lists/*

# Usuário não-root
RUN groupadd -r borurio && useradd -r -g borurio borurio && \
    mkdir -p /app /var/log/borurio /opt/borurio && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio && \
    chmod 750 /app /var/log/borurio /opt/borurio && \
    ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone

WORKDIR /app
USER borurio

# -----------------------------------------------------------------------------
# ARTEFATO FINAL
# -----------------------------------------------------------------------------
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/borurio-web.jar

# -----------------------------------------------------------------------------
# VARIÁVEIS DE AMBIENTE (NEUTRAS)
# -----------------------------------------------------------------------------
# JAVA_TOOL_OPTIONS é lido automaticamente pela JVM
# -----------------------------------------------------------------------------
ENV TZ=America/Sao_Paulo \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=America/Sao_Paulo"

# -----------------------------------------------------------------------------
# PORTAS (INFORMATIVAS)
# -----------------------------------------------------------------------------
EXPOSE 8082 8081

# -----------------------------------------------------------------------------
# HEALTHCHECK
# -----------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:${ACTUATOR_PORT:-8081}/actuator/health || exit 1

# -----------------------------------------------------------------------------
# ENTRYPOINT (SEM SHELL, SEM JAVA_OPTS)
# -----------------------------------------------------------------------------
ENTRYPOINT ["java", "-jar", "/app/borurio-web.jar"]

# =============================================================================
# FIM
# =============================================================================
