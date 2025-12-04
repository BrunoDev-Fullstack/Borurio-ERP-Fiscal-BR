# =============================================================================
# Dockerfile - Borurio ERP Web (Gateway / API REST Principal)
# =============================================================================
FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copia somente POMs (cache de dependências)
COPY pom.xml ./
COPY borurio-core/pom.xml borurio-core/
COPY borurio-app/pom.xml borurio-app/
COPY borurio-fiscal/pom.xml borurio-fiscal/
COPY borurio-web/pom.xml borurio-web/

# Baixa dependências (cache)
RUN mvn -B dependency:go-offline -DskipTests

# Copia o restante do código-fonte
COPY . .

# -------------------------------------------------------------------------
# COMPILA TODOS OS MÓDULOS
# -------------------------------------------------------------------------
RUN mvn -B clean install -DskipTests

# -------------------------------------------------------------------------
# REPACKAGE (garante o JAR executável do Spring Boot)
# -------------------------------------------------------------------------
RUN mvn -B -pl borurio-web -am package spring-boot:repackage -DskipTests

# O artefato executável correto SEMPRE fica em:
# /build/borurio-web/target/borurio-web-1.0.0.jar
# -------------------------------------------------------------------------


# =============================================================================
# RUNTIME — IMAGEM OTIMIZADA
# =============================================================================
FROM eclipse-temurin:17-jre-jammy AS runtime

ENV TZ=America/Sao_Paulo
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
RUN groupadd -r borurio && useradd -r -g borurio borurio

WORKDIR /app

# Copia o JAR executável (Spring Boot reempacotado)
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar ./borurio-web.jar

USER borurio

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=America/Sao_Paulo"

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar borurio-web.jar"]

# =============================================================================
# FIM DO DOCKERFILE
# =============================================================================
