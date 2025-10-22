# =============================================================================
# Dockerfile - Borurio ERP Web (Gateway / API REST Principal)
# =============================================================================
# Ambiente: Desenvolvimento / Homologação / Produção
# Stack: Java 17 | Spring Boot 3.3.x | MyBatis | Redis | NF-e | Flyway
# Padrões: Multi-stage Build | DevSecOps | Least Privilege | CI/CD Ready
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack | Cybersecurity
# =============================================================================

# -----------------------------------------------------------------------------
# Etapa 1 - Build Maven (compilação e empacotamento completo)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copia apenas os POMs para otimizar o cache de dependências
COPY pom.xml .
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

# Pré-download de dependências (cache e builds offline)
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true"

# Copia o código-fonte completo
COPY . .

# Define ambiente de build (default: dev)
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# -----------------------------------------------------------------------------
# Etapa 1.1 - Compila os módulos internos (Core, App, Fiscal)
# -----------------------------------------------------------------------------
# O parâmetro -Ddockerfile.skip=true evita build aninhado do Spotify plugin.
RUN mvn -B -pl borurio-core,borurio-app,borurio-fiscal clean install \
    -DskipTests -D"dependency-check.skip=true" -Ddockerfile.skip=true

# -----------------------------------------------------------------------------
# Etapa 1.2 - Empacota o módulo Web (gera o JAR final executável)
# -----------------------------------------------------------------------------
RUN mvn -B -pl borurio-web -am clean package spring-boot:repackage \
    -DskipTests -D"dependency-check.skip=true" -Ddockerfile.skip=true

# -----------------------------------------------------------------------------
# Etapa 2 - Runtime (imagem leve, segura e otimizada)
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

# Ajusta permissões seguras (non-root)
RUN chown -R borurio:borurio /app /var/log/borurio /opt/borurio
USER borurio

# -----------------------------------------------------------------------------
# Variáveis de ambiente padrão (sobrescritas via Compose ou CI/CD)
# -----------------------------------------------------------------------------
ENV SPRING_PROFILES_ACTIVE=dev \
    TZ=America/Sao_Paulo \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8" \
    SPRING_DATASOURCE_URL="jdbc:mysql://borurio-mysql-dev:3306/borurio_fiscal_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_USERNAME="borurio" \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME="com.mysql.cj.jdbc.Driver" \
    SPRING_REDIS_HOST="borurio-redis-dev" \
    SPRING_REDIS_PORT="6379" \
    SPRING_FLYWAY_ENABLED="true" \
    SPRING_FLYWAY_VALIDATE_ON_MIGRATE="true" \
    SPRING_JACKSON_DEFAULT_PROPERTY_INCLUSION="non_null" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,info,metrics,env,beans" \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="always"

# -----------------------------------------------------------------------------
# Segurança e Boas Práticas DevSecOps
# -----------------------------------------------------------------------------
# - remove caches temporários com permissões elevadas
# - ajusta ownership e retorna para usuário seguro
USER root
RUN rm -rf /tmp/* /var/tmp/* /root/.m2/repository || true && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio
USER borurio

# -----------------------------------------------------------------------------
# Healthcheck (verifica endpoint Actuator)
# -----------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1

# -----------------------------------------------------------------------------
# Exposição e ponto de entrada
# -----------------------------------------------------------------------------
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/borurio-web.jar"]
