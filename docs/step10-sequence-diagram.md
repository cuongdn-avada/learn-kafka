# Step 10: Production Hardening — Sequence Diagrams

## 1. Docker Compose Startup (Full Mode)

```mermaid
sequenceDiagram
    participant DC as Docker Compose
    participant K as Kafka
    participant SR as Schema Registry
    participant PG as PostgreSQL
    participant KI as kafka-init
    participant P as Prometheus
    participant G as Grafana
    participant Z as Zipkin
    participant OS as Order Service
    participant IS as Inventory Service
    participant PS as Payment Service
    participant NS as Notification Service

    Note over DC: docker compose --profile app up -d --build

    DC->>K: Start Kafka (KRaft)
    DC->>PG: Start PostgreSQL
    DC->>Z: Start Zipkin

    K-->>K: healthcheck: kafka-topics.sh --list
    PG-->>PG: healthcheck: pg_isready

    K->>DC: healthy
    PG->>DC: healthy

    DC->>SR: Start Schema Registry (depends: kafka healthy)
    DC->>KI: Start kafka-init (depends: kafka healthy)
    DC->>P: Start Prometheus

    SR-->>SR: healthcheck: curl /subjects
    KI->>K: Create topics (order.placed, order.validated, ...)
    KI-->>DC: exit(0)

    SR->>DC: healthy
    P->>DC: healthy

    DC->>G: Start Grafana (depends: prometheus healthy)
    DC->>OS: Start Order Service (depends: kafka + postgres + schema-registry)
    DC->>IS: Start Inventory Service (depends: kafka + postgres + schema-registry)
    DC->>PS: Start Payment Service (depends: kafka + postgres + schema-registry)
    DC->>NS: Start Notification Service (depends: kafka + schema-registry)

    Note over OS,NS: SPRING_PROFILES_ACTIVE=docker<br/>→ Loads application.yml + application-docker.yml

    OS-->>OS: healthcheck: wget /actuator/health
    IS-->>IS: healthcheck: wget /actuator/health
    PS-->>PS: healthcheck: wget /actuator/health
    NS-->>NS: healthcheck: wget /actuator/health
```

## 2. Graceful Shutdown Flow

```mermaid
sequenceDiagram
    participant D as Docker/SIGTERM
    participant S as Spring Boot
    participant H as HTTP Server
    participant KC as Kafka Consumer
    participant KP as Kafka Producer
    participant DB as Database

    Note over D,DB: docker stop order-service (or Ctrl+C)

    D->>S: SIGTERM signal

    S->>H: Stop accepting new HTTP requests
    S->>KC: Stop polling new messages

    Note over S: Graceful shutdown phase (max 30s)

    H->>H: Wait for in-flight HTTP requests to complete
    KC->>KC: Finish processing current message batch
    KC->>DB: Commit transaction (payment/inventory save)
    KC->>KP: Send response event (order.paid, etc.)
    KP->>KP: Flush pending messages (linger.ms buffer)

    KC->>KC: Commit consumer offsets
    KP->>KP: Close producer
    DB->>DB: Close connection pool

    S->>D: Shutdown complete (exit 0)

    Note over D,DB: If 30s timeout exceeded → force kill
```

## 3. Kafka Health Check Flow

```mermaid
sequenceDiagram
    participant C as Client/Probe
    participant A as Actuator
    participant KH as KafkaHealthIndicator
    participant AC as AdminClient
    participant K as Kafka Broker

    C->>A: GET /actuator/health
    A->>KH: health()

    KH->>AC: AdminClient.create(bootstrapServers)
    AC->>K: describeCluster()

    alt Kafka Reachable (< 5s timeout)
        K-->>AC: ClusterResult(clusterId, nodes)
        AC-->>KH: clusterId="MkU3...", nodeCount=1
        KH-->>A: Health.up()<br/>clusterId, nodeCount, bootstrapServers
        A-->>C: 200 OK {"status":"UP","components":{"kafkaClusterHealth":{"status":"UP"}}}
    else Kafka Unreachable (timeout/error)
        K--xAC: Connection refused / timeout
        AC-->>KH: Exception
        KH-->>A: Health.down()<br/>bootstrapServers, exception
        A-->>C: 503 Service Unavailable {"status":"DOWN"}
    end

    KH->>AC: close() (try-with-resources)
```

## 4. Multi-stage Docker Build

```mermaid
sequenceDiagram
    participant D as Docker Build
    participant S1 as Stage 1: JDK (build)
    participant S2 as Stage 2: JRE (runtime)
    participant R as Container Registry

    Note over D,R: docker build -f order-service/Dockerfile .

    D->>S1: FROM eclipse-temurin:21-jdk-alpine

    Note over S1: Layer 1: Maven wrapper + POMs (cached)
    S1->>S1: COPY mvnw, .mvn, pom.xml, common/pom.xml, order-service/pom.xml
    S1->>S1: RUN ./mvnw dependency:go-offline

    Note over S1: Layer 2: Source code (rebuilds on change)
    S1->>S1: COPY common/src, order-service/src
    S1->>S1: RUN ./mvnw package -pl order-service -am -DskipTests

    Note over S1: Result: order-service-0.0.1-SNAPSHOT.jar (~80MB)

    D->>S2: FROM eclipse-temurin:21-jre-alpine
    S2->>S1: COPY --from=build target/*.jar app.jar

    Note over S2: Final image: JRE + app.jar (~200MB)<br/>vs JDK + sources + .m2 (~800MB)

    S2->>S2: ENTRYPOINT java -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -jar app.jar

    D->>R: Image ready
```

## 5. Spring Profile Resolution (Docker Mode)

```mermaid
sequenceDiagram
    participant DC as Docker Compose
    participant S as Spring Boot
    participant Y1 as application.yml
    participant Y2 as application-docker.yml

    DC->>S: SPRING_PROFILES_ACTIVE=docker

    S->>Y1: Load application.yml (base config)
    Note over Y1: spring.kafka.bootstrap-servers: localhost:9094<br/>spring.datasource.url: jdbc:postgresql://localhost:5432/order_db<br/>management.zipkin.tracing.endpoint: http://localhost:9411/...<br/>management.endpoint.health.show-details: always<br/>management.tracing.sampling.probability: 1.0

    S->>Y2: Load application-docker.yml (profile override)
    Note over Y2: spring.kafka.bootstrap-servers: kafka:9092<br/>spring.datasource.url: jdbc:postgresql://postgres:5432/order_db<br/>management.zipkin.tracing.endpoint: http://zipkin:9411/...<br/>management.endpoint.health.show-details: when-authorized<br/>management.tracing.sampling.probability: 0.5

    S->>S: Merge: docker profile overrides base config

    Note over S: Final config:<br/>kafka → kafka:9092 ✓<br/>postgres → postgres:5432 ✓<br/>zipkin → zipkin:9411 ✓<br/>health details → when-authorized ✓<br/>sampling → 0.5 ✓
```
