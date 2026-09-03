# =============================================================================
# Dockerfile - Borurio ERP Fiscal BR
# =============================================================================

# =============================================================================
# ETAPA 1 — BUILD MAVEN
# =============================================================================
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build

VOLUME /root/.m2

COPY . .

RUN mvn -B clean install -DskipTests


# =============================================================================
# ETAPA 2 — RUNTIME
# =============================================================================
FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="Bruno Ribeiro DevSecOps"

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        openssl \
        tzdata && \
    update-ca-certificates && \
    rm -rf /var/lib/apt/lists/*


# =============================================================================
# CERTIFICADOS ICP-BRASIL
# =============================================================================
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


# =============================================================================
# USUÁRIO NÃO ROOT
# =============================================================================
RUN groupadd -r borurio && \
    useradd -r -g borurio borurio && \
    mkdir -p /app /var/log/borurio && \
    chown -R borurio:borurio /app /var/log/borurio

WORKDIR /app
USER borurio


# =============================================================================
# ARTEFATO
# =============================================================================
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/app.jar


# =============================================================================
# ENV
# =============================================================================
ENV TZ=America/Sao_Paulo \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=America/Sao_Paulo" \
    SERVER_PORT=8080


# =============================================================================
# PORTA DA APLICAÇÃO
# =============================================================================
EXPOSE 8080


# =============================================================================
# HEALTHCHECK
# =============================================================================
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
CMD curl -fsS http://localhost:8080/actuator/health || exit 1


# =============================================================================
# ENTRYPOINT
# =============================================================================
ENTRYPOINT ["java","-jar","/app/app.jar"]