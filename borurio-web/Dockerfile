# =============================================================================
# Dockerfile - Borurio ERP Web (Gateway / API REST Principal)
# Ambiente: Desenvolvimento / Homologação
# Stack: Java 17 / Spring Boot 3.3.x / MyBatis / Redis / NF-e
# Padrões: Multi-stage build / DevSecOps / JVM otimizada
# Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack | Cibersecurity
# =============================================================================

# -----------------------------------------------------------------------------
# Etapa 1 - Build com Maven (compilação e empacotamento)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copia apenas os arquivos de configuração primeiro para cache eficiente
COPY pom.xml .
COPY borurio-core/pom.xml ./borurio-core/
COPY borurio-app/pom.xml ./borurio-app/
COPY borurio-fiscal/pom.xml ./borurio-fiscal/
COPY borurio-web/pom.xml ./borurio-web/

# Pré-baixa dependências Maven (melhora performance em builds futuros)
RUN mvn -B dependency:go-offline -D"dependency-check.skip=true"

# Copia o código-fonte completo após as dependências
COPY . .

# Define variáveis padrão de build
ARG BUILD_ENV=dev
ENV SPRING_PROFILES_ACTIVE=${BUILD_ENV}

# Compila e empacota somente o módulo web com cache e sem testes
RUN mvn -B -pl borurio-web -am clean package -DskipTests -D"dependency-check.skip=true"

# -----------------------------------------------------------------------------
# Etapa 2 - Runtime (imagem leve e segura)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Copia o artefato final gerado na etapa anterior
COPY --from=builder /build/borurio-web/target/borurio-web-1.0.0.jar /app/app.jar

# -----------------------------------------------------------------------------
# Variáveis de ambiente padrão
# -----------------------------------------------------------------------------
ENV SPRING_PROFILES_ACTIVE=dev \
    TZ=America/Sao_Paulo \
    JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# -----------------------------------------------------------------------------
# Exposição e execução
# -----------------------------------------------------------------------------
EXPOSE 8080

# Usa formato exec para melhor compatibilidade com Docker stop / SIGTERM
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
