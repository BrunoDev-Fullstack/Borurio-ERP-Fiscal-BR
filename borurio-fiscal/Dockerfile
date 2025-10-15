# =============================================================================
# Dockerfile - Borurio Fiscal (NF-e / Auditoria Fiscal)
# Ambiente: Desenvolvimento / Homologação
# Stack: Java 17 / Spring Boot 3.3.x / MyBatis / MySQL 8 / Redis
# Padrões: DevSecOps / Multi-stage build otimizado
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack | Cibersecurity
# =============================================================================

# ---------------------------------------------------------------------------
# Etapa 1 - Build com Maven (compilação e empacotamento do módulo fiscal)
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copia os POMs para cache de dependências
COPY pom.xml ./
COPY borurio-core/pom.xml ./borurio-core/
COPY borurio-app/pom.xml ./borurio-app/
COPY borurio-fiscal/pom.xml ./borurio-fiscal/
COPY borurio-web/pom.xml ./borurio-web/

# Pré-carrega dependências Maven para build offline
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true"

# Copia o restante do código-fonte
COPY . .

# Define o perfil padrão de build
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# Compila apenas o módulo fiscal (gera jar completo com dependências)
RUN mvn -B -pl borurio-fiscal -am clean package spring-boot:repackage -DskipTests -D"dependency-check.skip=true"

# ---------------------------------------------------------------------------
# Etapa 2 - Runtime (imagem leve e segura)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Copia o artefato gerado
COPY --from=builder /build/borurio-fiscal/target/borurio-fiscal-1.0.0.jar /app/app.jar

# ---------------------------------------------------------------------------
# Variáveis de ambiente padrão (com suporte explícito ao MySQL)
# ---------------------------------------------------------------------------
ENV SPRING_PROFILES_ACTIVE=dev \
    TZ=America/Sao_Paulo \
    SPRING_DATASOURCE_URL="jdbc:mysql://borurio-mysql-dev:3306/borurio_fiscal_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo" \
    SPRING_DATASOURCE_USERNAME="borurio" \
    SPRING_DATASOURCE_PASSWORD="H4ck3r123" \
    SPRING_DATASOURCE_DRIVER-CLASS-NAME="com.mysql.cj.jdbc.Driver" \
    SPRING_FLYWAY_ENABLED="false" \
    REDIS_HOST="borurio-redis-dev" \
    REDIS_PORT="6379" \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# ---------------------------------------------------------------------------
# Exposição de porta e execução
# ---------------------------------------------------------------------------
EXPOSE 8080

# ENTRYPOINT compatível com Docker / CI/CD
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
