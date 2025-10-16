# =============================================================================
# Dockerfile - Borurio Fiscal (NF-e / Auditoria Fiscal)
# Ambiente: Desenvolvimento / Homologação / Produção
# Stack: Java 17 | Spring Boot 3.3.x | MyBatis | MySQL 8.4 | Redis | Flyway
# Padrões: DevSecOps / Multi-stage Build / Least Privilege / CI/CD Ready
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack | Cybersecurity
# =============================================================================

# ---------------------------------------------------------------------------
# Etapa 1 - Build com Maven (compilação e empacotamento do módulo fiscal)
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copia apenas os arquivos de definição (melhor cache de dependências)
COPY pom.xml .
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

# Baixa dependências Maven (cache offline)
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true"

# Copia o código-fonte completo após cache
COPY . .

# Define ambiente de build (DEV por padrão)
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# Compila o módulo fiscal e empacota com dependências
RUN mvn -B -pl borurio-fiscal -am clean package spring-boot:repackage \
    -DskipTests -D"dependency-check.skip=true"

# ---------------------------------------------------------------------------
# Etapa 2 - Runtime (imagem leve, segura e com variáveis de ambiente padrão)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS runtime

# Define diretórios seguros e cria usuário não-root
RUN mkdir -p /app /var/log/borurio /opt/borurio/certs && \
    groupadd -r borurio && useradd -r -g borurio borurio

WORKDIR /app

# Copia o artefato gerado no build
COPY --from=builder /build/borurio-fiscal/target/borurio-fiscal-1.0.0.jar /app/borurio-fiscal.jar

# ---------------------------------------------------------------------------
# Variáveis de ambiente padrão e seguras
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
    SPRING_FLYWAY_ENABLED="true" \
    SPRING_FLYWAY_VALIDATE_ON_MIGRATE="true" \
    SPRING_JACKSON_DEFAULT_PROPERTY_INCLUSION="non_null" \
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE="health,info" \
    MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="always"

# ---------------------------------------------------------------------------
# Segurança e boas práticas DevSecOps
# ---------------------------------------------------------------------------
# - remove cache temporário do JDK
# - define permissões restritas ao usuário borurio
RUN rm -rf /tmp/* /var/tmp/* && \
    chown -R borurio:borurio /app /var/log/borurio /opt/borurio/certs

USER borurio

# ---------------------------------------------------------------------------
# Exposição e ponto de entrada
# ---------------------------------------------------------------------------
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/borurio-fiscal.jar"]
