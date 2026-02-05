# =============================================================================
# Dockerfile - Borurio ERP Fiscal BR (Unificado / Neutro)
# =============================================================================
# Módulos: Core | App | Fiscal | Web
# Stack: Java 17 | Spring Boot 3.3.x
# Padrões: Multi-stage | DevSecOps | Least Privilege | CI/CD Ready
# Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
# Última revisão: 04/02/2026
# =============================================================================

# -----------------------------------------------------------------------------
# ETAPA 1 — BUILD MAVEN
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Cache Maven (compatível com Docker "clássico")
VOLUME /root/.m2

# Copia apenas POMs primeiro para maximizar cache
COPY pom.xml ./
COPY borurio-core/pom.xml borurio-core/pom.xml
COPY borurio-app/pom.xml borurio-app/pom.xml
COPY borurio-fiscal/pom.xml borurio-fiscal/pom.xml
COPY borurio-web/pom.xml borurio-web/pom.xml

# Baixa dependências (evita baixar tudo a cada build)
# Se você usar BuildKit, pode trocar por:
# RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline -DskipTests
RUN mvn -B -ntp dependency:go-offline -DskipTests

# Agora copia o restante do código
COPY . .

# Build somente do módulo web (e suas dependências via -am)
# Se você usar BuildKit:
# RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl borurio-web -am clean package -DskipTests
RUN mvn -B -ntp -pl borurio-web -am clean package -DskipTests

# -----------------------------------------------------------------------------
# ETAPA 2 — RUNTIME (IMAGEM SEGURA)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

# Metadados OCI (neutros)
LABEL org.opencontainers.image.title="borurio-erp-fiscal-br" \
      org.opencontainers.image.description="Borurio ERP Fiscal BR - Runtime" \
      org.opencontainers.image.licenses="Proprietary" \
      org.opencontainers.image.source="local"

# Dependências mínimas (curl para healthcheck)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      curl \
      ca-certificates \
      tzdata && \
    rm -rf /var/lib/apt/lists/*

# Timezone (padrão Brasil) e estrutura de diretórios com least privilege
RUN groupadd -r borurio && useradd -r -g borurio borurio && \
    mkdir -p /app /var/log/borurio /opt/borurio /tmp && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio /tmp && \
    chmod 750 /app /var/log/borurio /opt/borurio && \
    chmod 1777 /tmp && \
    ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone

WORKDIR /app
USER borurio

# -----------------------------------------------------------------------------
# ARTEFATO FINAL
# -----------------------------------------------------------------------------
# Não fixa versão do JAR para não quebrar quando mudar o version do Maven.
# Copia o JAR final do Spring Boot (repackage) e renomeia para /app/borurio-web.jar.
# Observação: pode existir um *.jar.original; por isso, filtramos o "original" abaixo.
COPY --from=builder --chown=borurio:borurio \
  /build/borurio-web/target/borurio-web-*.jar /app/

# Remove qualquer jar ".original" caso exista e normaliza o nome final
RUN rm -f /app/*.jar.original 2>/dev/null || true && \
    JAR="$(ls -1 /app/borurio-web-*.jar | head -n 1)" && \
    cp "$JAR" /app/borurio-web.jar && \
    rm -f /app/borurio-web-*.jar

# -----------------------------------------------------------------------------
# VARIÁVEIS DE AMBIENTE (NEUTRAS)
# -----------------------------------------------------------------------------
# JAVA_TOOL_OPTIONS é lido automaticamente pela JVM.
# Importante: não fixar porta aqui. Deixa o compose (hom/dev/prd) controlar.
# ACTUATOR_PORT fica default 8082 para bater com seu profile hom (você pode sobrescrever).
ENV TZ=America/Sao_Paulo \
    ACTUATOR_PORT=8082 \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=America/Sao_Paulo"

# -----------------------------------------------------------------------------
# PORTAS (INFORMATIVAS)
# -----------------------------------------------------------------------------
# Não expõe 8081 fixo. Em hom seu Spring está na 8082 (pelo log).
EXPOSE 8082

# -----------------------------------------------------------------------------
# HEALTHCHECK
# -----------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS "http://localhost:${ACTUATOR_PORT}/actuator/health" || exit 1

# -----------------------------------------------------------------------------
# ENTRYPOINT (SEM SHELL)
# -----------------------------------------------------------------------------
ENTRYPOINT ["java", "-jar", "/app/borurio-web.jar"]

# =============================================================================
# FIM
# =============================================================================
