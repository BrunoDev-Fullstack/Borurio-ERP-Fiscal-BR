# =============================================================================
# Dockerfile - Borurio ERP Fiscal BR (Unificado / Neutro)
# =============================================================================
# Módulos: Core | App | Fiscal | Web
# Stack: Java 17 | Spring Boot 3.3.x
# Padrões: Multi-stage | DevSecOps | Least Privilege | CI/CD Ready
# Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
# Última revisão: 13/03/2026
# =============================================================================


# =============================================================================
# ETAPA 1 — BUILD MAVEN
# =============================================================================
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Cache Maven
VOLUME /root/.m2

# -----------------------------------------------------------------------------
# Copia apenas os POMs primeiro (melhor cache de dependências)
# -----------------------------------------------------------------------------
COPY pom.xml .
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

RUN mvn -B -q dependency:go-offline -DskipTests

# -----------------------------------------------------------------------------
# Copia o código
# -----------------------------------------------------------------------------
COPY . .

RUN mvn -B -am clean package -DskipTests


# =============================================================================
# ETAPA 2 — RUNTIME (IMAGEM SEGURA)
# =============================================================================
FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="Bruno Ribeiro DevSecOps"

# -----------------------------------------------------------------------------
# Instalação mínima de pacotes
# -----------------------------------------------------------------------------
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        openssl \
        tzdata && \
    update-ca-certificates && \
    rm -rf /var/lib/apt/lists/*


# -----------------------------------------------------------------------------
# Bootstrap cadeia ICP-Brasil
# -----------------------------------------------------------------------------
# OBS: usa -k apenas no bootstrap pois a cadeia ainda não está confiável
# -----------------------------------------------------------------------------
RUN set -e && \
    curl -k -L --retry 5 --retry-delay 2 \
        https://acraiz.icpbrasil.gov.br/ICP-Brasilv10.crt \
        -o /tmp/icp-brasil-v10.crt && \
    curl -k -L --retry 5 --retry-delay 2 \
        https://ccd.acsoluti.com.br/lcr/ac-soluti-ssl-ev-v10-g4.crt \
        -o /tmp/ac-soluti-g4.crt && \
    keytool -importcert \
        -alias icp-brasil-v10 \
        -file /tmp/icp-brasil-v10.crt \
        -cacerts \
        -storepass changeit \
        -noprompt && \
    keytool -importcert \
        -alias ac-soluti-ssl-ev-g4 \
        -file /tmp/ac-soluti-g4.crt \
        -cacerts \
        -storepass changeit \
        -noprompt && \
    rm -f /tmp/*.crt


# -----------------------------------------------------------------------------
# Usuário não-root (Least Privilege)
# -----------------------------------------------------------------------------
RUN groupadd -r borurio && \
    useradd -r -g borurio borurio && \
    mkdir -p /app /app/certs /var/log/borurio /opt/borurio && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio && \
    chmod 750 /app /var/log/borurio /opt/borurio


# -----------------------------------------------------------------------------
# Timezone
# -----------------------------------------------------------------------------
RUN ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone


WORKDIR /app
USER borurio


# =============================================================================
# ARTEFATO FINAL
# =============================================================================
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/borurio-web.jar


# =============================================================================
# VARIÁVEIS DE AMBIENTE
# =============================================================================
ENV TZ=America/Sao_Paulo \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=America/Sao_Paulo"


# =============================================================================
# PORTAS (DOCUMENTAÇÃO)
# =============================================================================
EXPOSE 8082
EXPOSE 8081


# =============================================================================
# HEALTHCHECK
# =============================================================================
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
CMD curl -fsS http://localhost:${ACTUATOR_PORT:-8081}/actuator/health || exit 1


# =============================================================================
# ENTRYPOINT
# =============================================================================
ENTRYPOINT ["java","-jar","/app/borurio-web.jar"]


# =============================================================================
# FIM
# =============================================================================