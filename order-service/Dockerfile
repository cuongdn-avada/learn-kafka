# ============================================================
# Multi-stage build — Order Service
#
# WHY multi-stage?
# → Stage 1 (build): dùng JDK + Maven để compile → JAR
# → Stage 2 (runtime): chỉ JRE → image nhỏ hơn (~200MB vs ~800MB)
# → Giảm attack surface: không có compiler, build tools trong production image
# ============================================================

# --- Stage 1: Build ---
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Copy Maven wrapper + POM files trước (cache layer)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY order-service/pom.xml order-service/pom.xml

# Download dependencies (cached nếu POM không đổi)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -pl order-service -am -q

# Copy source code
COPY common/src common/src
COPY order-service/src order-service/src

# Build JAR — skip tests (đã test ở CI)
RUN ./mvnw package -pl order-service -am -DskipTests -q

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy JAR từ build stage
COPY --from=build /app/order-service/target/*.jar app.jar

# JVM tuning cho container environment
# -XX:+UseG1GC: garbage collector phù hợp cho microservice
# -XX:MaxRAMPercentage=75.0: dùng max 75% RAM container (để OS + overhead)
ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]

EXPOSE 8081
