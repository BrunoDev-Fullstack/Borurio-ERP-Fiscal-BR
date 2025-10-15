# =============================================================================
# Dockerfile - Borurio ERP Web (Gateway / API REST Principal)
# Ambiente: Desenvolvimento / Homologação
# Stack: Java 17 / Spring Boot 3.3.x / MyBatis / Redis / NF-e / Flyway
# Padrões: Multi-stage build / DevSecOps / JVM otimizada / Healthcheck
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack | Cybersecurity
# =============================================================================

# ---------------------------------------------------------------------------
# Etapa 1 - Build Maven (compilação e empacotamento)
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copia apenas arquivos de configuração (melhor cache)
COPY pom.xml ./
COPY borurio-core/pom.xml ./borurio-core/
COPY borurio-app/pom.xml ./borurio-app/
COPY borurio-fiscal/pom.xml ./borurio-fiscal/
COPY borurio-web/pom.xml ./borurio-web/

# Pré-download de dependências Maven (cache local)
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true"

# Copia todo o código-fonte
COPY . .

# Variável de ambiente para build (default: dev)
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# ---------------------------------------------------------------------------
# Etapa 1.1 - Compilação dos módulos internos (Core, App, Fiscal)
# ---------------------------------------------------------------------------
RUN mvn -B -pl borurio-core,borurio-app,borurio-fiscal clean install \
    -DskipTests -D"dependency-check.skip=true"

# ---------------------------------------------------------------------------
# Etapa 1.2 - Build do módulo Web
# ---------------------------------------------------------------------------
RUN mvn -B -pl borurio-web -am clean package \
    -DskipTests -D"dependency-check.skip=true"

# ---------------------------------------------------------------------------
# Etapa 2 - Runtime (imagem final leve e segura)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS runtime
WORKDIR /app

# Copia o artefato final do build
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/app.jar

# ---------------------------------------------------------------------------
# Diretório de logs e timezone
# ---------------------------------------------------------------------------
RUN mkdir -p /app/logs && \
    ln -snf /usr/share/zoneinfo/America/Sao_Paulo /etc/localtime && \
    echo "America/Sao_Paulo" > /etc/timezone

# ---------------------------------------------------------------------------
# Variáveis de ambiente padrão
# ---------------------------------------------------------------------------
ENV SPRING_PROFILES_ACTIVE=dev \
    TZ=America/Sao_Paulo \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Dspring.output.ansi.enabled=ALWAYS"

# ---------------------------------------------------------------------------
# Healthcheck (verifica se o Actuator está online)
# ---------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# ---------------------------------------------------------------------------
# Exposição e execução
# ---------------------------------------------------------------------------
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
