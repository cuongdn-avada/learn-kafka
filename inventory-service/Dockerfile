# Multi-stage build — Inventory Service

# --- Stage 1: Build ---
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml

RUN chmod +x mvnw && ./mvnw dependency:go-offline -pl inventory-service -am -q

COPY common/src common/src
COPY inventory-service/src inventory-service/src

RUN ./mvnw package -pl inventory-service -am -DskipTests -q

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/inventory-service/target/*.jar app.jar

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]

EXPOSE 8082
