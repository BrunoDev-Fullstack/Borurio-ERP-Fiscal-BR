# =============================================================================
# Dockerfile - Borurio ERP Web (Gateway / API REST Principal)
# Ambiente: Desenvolvimento / Homologação / Produção
# Stack: Java 17 | Spring Boot 3.3.x | MyBatis | Redis | NF-e | Flyway
# Padrões: Multi-stage build | DevSecOps | JVM otimizada | Healthcheck
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack | Cybersecurity
# =============================================================================

# ---------------------------------------------------------------------------
# Etapa 1 - Build Maven (compilação e empacotamento completo)
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copia POMs para otimizar cache de dependências
COPY pom.xml .
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

# Pré-download de dependências (build offline e cache eficiente)
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true"

# Copia o código-fonte completo
COPY . .

# Define o ambiente de build (default: dev)
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# ---------------------------------------------------------------------------
# Etapa 1.1 - Compila os módulos internos (Core, App, Fiscal)
# ---------------------------------------------------------------------------
RUN mvn -B -pl borurio-core,borurio-app,borurio-fiscal clean install \
    -DskipTests -D"dependency-check.skip=true"

# ---------------------------------------------------------------------------
# Etapa 1.2 - Empacota o módulo Web (gera JAR final)
# ---------------------------------------------------------------------------
RUN mvn -B -pl borurio-web -am clean package spring-boot:repackage \
    -DskipTests -D"dependency-check.skip=true"

# ---------------------------------------------------------------------------
# Etapa 2 - Runtime (imagem leve e segura)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS runtime

# Criação de diretórios e usuário não-root
RUN mkdir -p /app /var/log/borurio && \
    groupadd -r borurio && useradd -r -g borurio borurio && \
    ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone

WORKDIR /app

# Copia o artefato gerado na etapa de build
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/borurio-web.jar

# Permissões seguras
RUN chown -R borurio:borurio /app /var/log/borurio
USER borurio

# ---------------------------------------------------------------------------
# Variáveis de ambiente padrão
# ---------------------------------------------------------------------------
ENV SPRING_PROFILES_ACTIVE=dev \
    TZ=America/Sao_Paulo \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8" \
    SPRING_DATASOURCE_URL="jdbc:mysql://borurio-mysql-dev:3306/borurio_fiscal_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_USERNAME="borurio" \
    SPRING_DATASOURCE_PASSWORD="H4ck3r123" \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME="com.mysql.cj.jdbc.Driver" \
    SPRING_REDIS_HOST="borurio-redis-dev" \
    SPRING_REDIS_PORT="6379" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,info,metrics" \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="always"

# ---------------------------------------------------------------------------
# Healthcheck (verifica disponibilidade do Actuator)
# ---------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1

# ---------------------------------------------------------------------------
# Exposição e execução do container
# ---------------------------------------------------------------------------
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/borurio-web.jar"]
