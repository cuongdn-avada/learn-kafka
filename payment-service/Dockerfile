# Multi-stage build — Payment Service

# --- Stage 1: Build ---
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml

RUN chmod +x mvnw && ./mvnw dependency:go-offline -pl payment-service -am -q

COPY common/src common/src
COPY payment-service/src payment-service/src

RUN ./mvnw package -pl payment-service -am -DskipTests -q

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/payment-service/target/*.jar app.jar

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]

EXPOSE 8083
