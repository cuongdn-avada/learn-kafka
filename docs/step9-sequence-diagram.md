# Step 9: Observability — Sequence Diagrams

## 1. Metrics Flow (Prometheus + Grafana)

```mermaid
sequenceDiagram
    participant App as Spring Boot Service
    participant Act as /actuator/prometheus
    participant Prom as Prometheus
    participant Graf as Grafana

    Note over App: MeterRegistry registers counters<br/>orders.created.total, payments.success.total, etc.

    App->>App: Business logic increments counter

    loop Every 15s (scrape_interval)
        Prom->>Act: GET /actuator/prometheus
        Act-->>Prom: Metrics in Prometheus format
        Prom->>Prom: Store in time-series DB
    end

    Graf->>Prom: PromQL query (rate, sum, histogram_quantile)
    Prom-->>Graf: Query result
    Graf->>Graf: Render dashboard panels
```

## 2. Distributed Tracing Flow (Brave + Zipkin)

```mermaid
sequenceDiagram
    participant Client
    participant OS as Order Service
    participant Kafka as Kafka Broker
    participant IS as Inventory Service
    participant PS as Payment Service
    participant NS as Notification Service
    participant Zipkin

    Client->>OS: POST /api/orders
    Note over OS: Brave creates traceId=abc123, spanId=span1

    OS->>Kafka: Produce order.placed<br/>[header: traceparent=abc123-span1]
    OS-->>Zipkin: Report span (HTTP)

    Kafka->>IS: Consume order.placed
    Note over IS: Brave extracts traceId=abc123 from header<br/>Creates child spanId=span2
    IS->>Kafka: Produce order.validated<br/>[header: traceparent=abc123-span2]
    IS-->>Zipkin: Report span

    Kafka->>PS: Consume order.validated
    Note over PS: Same traceId=abc123, new spanId=span3
    PS->>Kafka: Produce order.paid<br/>[header: traceparent=abc123-span3]
    PS-->>Zipkin: Report span

    Kafka->>OS: Consume order.paid
    Note over OS: Same traceId=abc123, new spanId=span4
    OS->>Kafka: Produce order.completed
    OS-->>Zipkin: Report span

    Kafka->>NS: Consume order.completed
    Note over NS: Same traceId=abc123, new spanId=span5
    NS-->>Zipkin: Report span

    Note over Zipkin: All spans share traceId=abc123<br/>Zipkin assembles full trace tree
```

## 3. Structured Logging Flow (MDC)

```mermaid
sequenceDiagram
    participant Brave as Brave Tracer
    participant MDC as SLF4J MDC
    participant Log as Logback
    participant Console

    Note over Brave: traceId=abc123, spanId=span1

    Brave->>MDC: Put traceId, spanId into MDC context

    Note over Log: logging.pattern.level includes<br/>%X{traceId} and %X{spanId}

    Log->>Console: INFO [order-service,abc123,span1] Order created

    Note over Console: Grep "abc123" across all service logs<br/>→ Full request journey
```

## 4. Kafka Observation (Trace Propagation)

```mermaid
sequenceDiagram
    participant Producer as KafkaTemplate
    participant Headers as Kafka Record Headers
    participant Consumer as @KafkaListener

    Note over Producer: observationEnabled=true

    Producer->>Producer: Before send: inject traceId into headers
    Producer->>Headers: Add b3/traceparent header

    Note over Consumer: containerProperties.observationEnabled=true

    Headers->>Consumer: Kafka record with trace headers
    Consumer->>Consumer: Extract traceId from headers
    Consumer->>Consumer: Create child span with same traceId

    Note over Consumer: All logs within this consumer<br/>automatically include traceId/spanId via MDC
```

## 5. Full Observability Stack

```mermaid
graph TB
    subgraph "Spring Boot Services"
        OS[Order Service :8081]
        IS[Inventory Service :8082]
        PS[Payment Service :8083]
        NS[Notification Service :8084]
    end

    subgraph "Metrics Pipeline"
        Prom[Prometheus :9090]
        Graf[Grafana :3000]
    end

    subgraph "Tracing Pipeline"
        Zip[Zipkin :9411]
    end

    subgraph "Logging"
        Console[Console with traceId/spanId]
    end

    OS -->|/actuator/prometheus| Prom
    IS -->|/actuator/prometheus| Prom
    PS -->|/actuator/prometheus| Prom
    NS -->|/actuator/prometheus| Prom
    Prom --> Graf

    OS -->|spans via HTTP| Zip
    IS -->|spans via HTTP| Zip
    PS -->|spans via HTTP| Zip
    NS -->|spans via HTTP| Zip

    OS --> Console
    IS --> Console
    PS --> Console
    NS --> Console
```
