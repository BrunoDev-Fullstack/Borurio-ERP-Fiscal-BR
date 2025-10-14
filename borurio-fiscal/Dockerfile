# =============================================================================
# Dockerfile - Borurio Fiscal (NF-e / Auditoria Fiscal)
# Ambiente: Desenvolvimento / Homologação
# Stack: Java 17 / Spring Boot 3.3.x / MyBatis / MySQL 8 / Redis
# Padrões: DevSecOps / Multi-stage build otimizado
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack | Cibersecurity
# =============================================================================

# -----------------------------------------------------------------------------
# Etapa 1 - Build com Maven (compilação e empacotamento do módulo fiscal)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copia apenas os arquivos de configuração (melhor aproveitamento de cache)
COPY pom.xml ./
COPY borurio-core/pom.xml ./borurio-core/
COPY borurio-app/pom.xml ./borurio-app/
COPY borurio-fiscal/pom.xml ./borurio-fiscal/
COPY borurio-web/pom.xml ./borurio-web/

# Pré-carrega dependências Maven para cache de build
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true"

# Copia todo o código-fonte após o cache
COPY . .

# Define o perfil padrão de build
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# Compila o módulo fiscal com empacotamento Spring Boot (repackage)
RUN mvn -B -pl borurio-fiscal -am clean package spring-boot:repackage -DskipTests -D"dependency-check.skip=true"

# -----------------------------------------------------------------------------
# Etapa 2 - Runtime (imagem leve e segura para execução)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Copia o JAR empacotado (Spring Boot repackage)
COPY --from=builder /build/borurio-fiscal/target/borurio-fiscal-1.0.0.jar /app/app.jar

# -----------------------------------------------------------------------------
# Variáveis de ambiente padrão
# -----------------------------------------------------------------------------
ENV SPRING_PROFILES_ACTIVE=dev \
    TZ=America/Sao_Paulo \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# -----------------------------------------------------------------------------
# Exposição de porta e execução
# -----------------------------------------------------------------------------
EXPOSE 8080

# ENTRYPOINT com formato exec para compatibilidade com sinais do Docker e CI/CD
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
