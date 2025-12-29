# =============================================================================
# Dockerfile - Borurio ERP Fiscal BR (Raiz Unificado + SEFAZ SSL)
# =============================================================================

# -----------------------------------------------------------------------------
# ETAPA 1 — BUILD COMPLETO COM MAVEN
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
LABEL stage="builder" maintainer="Bruno Ribeiro <dev@borurio.com.br>"

WORKDIR /build
VOLUME /root/.m2

# Copia POMs para cache
COPY pom.xml ./
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

RUN mvn -B dependency:go-offline \
    -DskipTests \
    -Ddependency-check.skip=true

# Copia código completo
COPY . .

# Build completo
RUN mvn -B -am clean package \
    -DskipTests \
    -Ddependency-check.skip=true

# -----------------------------------------------------------------------------
# ETAPA 2 — RUNTIME
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy
LABEL stage="runtime" maintainer="Bruno Ribeiro <dev@borurio.com.br>"

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        tzdata && \
    rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# Usuário + diretórios
# -----------------------------------------------------------------------------
RUN set -eux; \
    groupadd -r borurio && useradd -r -g borurio borurio && \
    mkdir -p \
        /app \
        /app/certs/autoridade \
        /app/certs/pfx \
        /app/certs/keystore \
        /app/certs/truststore \
        /var/log/borurio && \
    ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone && \
    chown -R borurio:borurio /app /var/log/borurio && \
    chmod -R 750 /app /var/log/borurio

WORKDIR /app

# -----------------------------------------------------------------------------
# Certificados
# -----------------------------------------------------------------------------
COPY docker/certs/autoridade/ /app/certs/autoridade/
COPY docker/certs/pfx/        /app/certs/pfx/
COPY docker/certs/keystore/   /app/certs/keystore/

# Truststore ICP-Brasil
RUN keytool -import -alias icpbrasil-v5 \
        -file /app/certs/autoridade/ICP-Brasilv5.crt \
        -keystore /app/certs/truststore/sefaz-truststore.jks \
        -storepass changeit -noprompt && \
    keytool -import -alias soluti-v5 \
        -file /app/certs/autoridade/AC_Soluti_v5.crt \
        -keystore /app/certs/truststore/sefaz-truststore.jks \
        -storepass changeit -noprompt

# -----------------------------------------------------------------------------
# JAR EXECUTÁVEL
# -----------------------------------------------------------------------------
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/borurio-web.jar

# Fail fast se JAR não existir
RUN test -f /app/borurio-web.jar

# -----------------------------------------------------------------------------
# Variáveis JVM
# -----------------------------------------------------------------------------
ENV TZ=America/Sao_Paulo \
    SPRING_PROFILES_ACTIVE=dev \
    JAVA_TOOL_OPTIONS="\
      -XX:+UseContainerSupport \
      -XX:MaxRAMPercentage=75.0 \
      -Dfile.encoding=UTF-8 \
      -Duser.timezone=America/Sao_Paulo \
      -Djavax.net.ssl.keyStore=/app/certs/keystore/jcho-keystore.p12 \
      -Djavax.net.ssl.keyStorePassword=Jcho237888 \
      -Djavax.net.ssl.keyStoreType=PKCS12 \
      -Djavax.net.ssl.trustStore=/app/certs/truststore/sefaz-truststore.jks \
      -Djavax.net.ssl.trustStorePassword=changeit"

USER borurio

# -----------------------------------------------------------------------------
# Porta + Healthcheck (AJUSTADO)
# -----------------------------------------------------------------------------
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD /usr/bin/curl -fsS http://localhost:8080/actuator/health || exit 1

# -----------------------------------------------------------------------------
# ENTRYPOINT
# -----------------------------------------------------------------------------
ENTRYPOINT ["java", "-jar", "/app/borurio-web.jar"]
