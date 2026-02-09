# E-Commerce Order Processing Pipeline

Event-driven order processing system using **Apache Kafka 4.0 (KRaft)** and **Spring Boot 3.3.5**.
Implements **Choreography-based Saga** pattern for distributed transactions.

## Prerequisites

- Java 21+
- Docker & Docker Compose
- Maven 3.9+ (or use included `./mvnw`)

## Quick Start

### 1. Start Infrastructure

```bash
docker compose up -d
```

This starts:
| Service         | Port | Purpose                        |
|-----------------|------|--------------------------------|
| Kafka           | 9094 | Event broker (KRaft mode)      |
| Schema Registry | 8085 | Schema management (Avro)       |
| PostgreSQL      | 5432 | Databases (order, inventory, payment) |
| Kafka UI        | 8088 | Web UI for Kafka + Schemas     |
| Prometheus      | 9090 | Metrics scraping + querying    |
| Grafana         | 3000 | Dashboards (admin/admin)       |
| Zipkin          | 9411 | Distributed trace visualization|

Wait for Kafka to be healthy (topics are auto-created by `kafka-init` container):

```bash
docker compose ps
```

All services should show `healthy` or `exited (0)` (for kafka-init).

### 2. Build the Project

```bash
./mvnw clean install -DskipTests
```

### 3. Run Services

**Option A — Run locally (dev mode):**

```bash
# Terminal 1-4: each service in separate terminal
./mvnw spring-boot:run -pl order-service
./mvnw spring-boot:run -pl inventory-service
./mvnw spring-boot:run -pl payment-service
./mvnw spring-boot:run -pl notification-service
```

**Option B — Run in Docker (containerized mode):**

```bash
docker compose --profile app up -d --build
```

This builds and starts all 4 services in containers alongside infrastructure.

Inventory Service auto-seeds 4 sample products on first startup.

### 4. Test the API

**Create an order:**

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
      {
        "productId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "productName": "MacBook Pro",
        "quantity": 1,
        "price": 2499.99
      },
      {
        "productId": "8a9e6679-7425-40de-944b-e07fc1f90ae8",
        "productName": "Magic Mouse",
        "quantity": 2,
        "price": 99.99
      }
    ]
  }'
```

**Get order by ID:**

```bash
curl http://localhost:8081/api/orders/{orderId}
```

**Get orders by customer:**

```bash
curl http://localhost:8081/api/orders?customerId=550e8400-e29b-41d4-a716-446655440000
```

**Test stock failure (product with zero stock):**

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
      {
        "productId": "ac9e6679-7425-40de-944b-e07fc1f90af0",
        "productName": "Apple Watch Ultra",
        "quantity": 1,
        "price": 799.99
      }
    ]
  }'
```

**Test payment failure (total amount > $10,000):**

```bash
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
      {
        "productId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        "productName": "MacBook Pro",
        "quantity": 5,
        "price": 2499.99
      }
    ]
  }'
```

### 5. Verify Kafka Messages

Open Kafka UI at [http://localhost:8088](http://localhost:8088) and check:
- `order.placed` — event published by Order Service
- `order.validated` — event published by Inventory Service (stock OK)
- `order.paid` — event published by Payment Service (payment OK)
- `order.completed` — event published by Order Service (saga complete)
- `order.failed` — event published by Inventory Service (insufficient stock)
- `payment.failed` — event published by Payment Service (amount > $10,000)

### Sample Product IDs (seeded by Inventory Service)

| Product ID | Name | Stock |
|------------|------|-------|
| `7c9e6679-7425-40de-944b-e07fc1f90ae7` | MacBook Pro 14 | 50 |
| `8a9e6679-7425-40de-944b-e07fc1f90ae8` | Magic Mouse | 100 |
| `9b9e6679-7425-40de-944b-e07fc1f90ae9` | iPhone 15 Pro | 30 |
| `ac9e6679-7425-40de-944b-e07fc1f90af0` | Apple Watch Ultra | 0 (for testing failure) |

## Project Structure

```
learn-kafka/
├── common/                 # Shared events, DTOs, Avro schemas, constants
│   └── src/main/avro/      # Avro schema files (.avsc)
├── order-service/          # REST API + Kafka Producer (port 8081)
├── inventory-service/      # Stock management (port 8082)
├── payment-service/        # Payment processing (port 8083)
├── notification-service/   # Notification consumer (port 8084)
├── infra/                  # Infrastructure scripts
├── docker-compose.yml      # Kafka + Schema Registry + PostgreSQL + Kafka UI
└── docs/                   # Diagrams and documentation
```

## Kafka Topics

| Topic             | Producer          | Consumer(s)                    |
|-------------------|-------------------|--------------------------------|
| `order.placed`    | Order Service     | Inventory Service              |
| `order.validated` | Inventory Service | Payment Service                |
| `order.paid`      | Payment Service   | Order Service                  |
| `order.completed` | Order Service     | Notification Service           |
| `order.failed`    | Inventory Service | Order Service, Notification    |
| `payment.failed`  | Payment Service   | Inventory, Order, Notification |
| `*.DLT`           | Error Handler     | DLT consumers (per service)    |

## Database Credentials (Local Dev)

| Database       | User  | Password | Port |
|----------------|-------|----------|------|
| `order_db`     | admin | admin123 | 5432 |
| `inventory_db` | admin | admin123 | 5432 |
| `payment_db`   | admin | admin123 | 5432 |

## Testing Dead Letter Queue

When a consumer fails to process a message after 3 retries (with exponential backoff), the message is published to a `.DLT` (Dead Letter Topic).

**Verify DLT topics exist:**

```bash
docker exec kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092 | grep DLT
```

**Monitor DLT messages in Kafka UI:**

Open [http://localhost:8088](http://localhost:8088) and check topics ending with `.DLT`.

**Check DLT consumer logs:**

DLT consumers log at ERROR level with `[DLT]` prefix. Look for messages like:
```
[DLT] Failed to process order.placed | orderId=... | eventId=... | status=...
```

## Schema Registry

Kafka messages are serialized using **Apache Avro** with **Confluent Schema Registry**.

- Schema Registry UI: integrated in Kafka UI at [http://localhost:8088](http://localhost:8088)
- Schema Registry API: [http://localhost:8085](http://localhost:8085)

**Useful Schema Registry commands:**

```bash
# List all registered schema subjects
curl http://localhost:8085/subjects

# Get latest schema for a topic
curl http://localhost:8085/subjects/order.placed-value/versions/latest

# Check global compatibility level
curl http://localhost:8085/config
```

**Serialization architecture:**
- REST API layer: JSON (Jackson) — for client communication
- Kafka layer: Avro (Schema Registry) — for inter-service events
- `OrderEventMapper` bridges between `OrderEvent` (Java record) and `OrderEventAvro` (Avro SpecificRecord)

## Testing

Run all tests:

```bash
./mvnw clean test
```

**59 unit tests** covering all modules:

| Module | Test Class | Tests | Type |
|--------|-----------|-------|------|
| common | `OrderEventMapperTest` | 6 | Avro conversion (round-trip) |
| order-service | `OrderServiceTest` | 12 | Service logic (Mockito) |
| order-service | `OrderControllerTest` | 5 | REST API (MockMvc) |
| inventory-service | `InventoryServiceTest` | 8 | Stock validation (Mockito) |
| inventory-service | `ProductTest` | 13 | Domain logic (pure unit) |
| payment-service | `PaymentServiceTest` | 7 | Payment threshold (Mockito) |
| notification-service | `NotificationServiceTest` | 8 | Dedup logic (pure unit) |

**Test patterns used:**
- `@ExtendWith(MockitoExtension)` — service layer tests with mocked dependencies
- `@WebMvcTest` — controller tests with MockMvc (only web layer loaded)
- Pure JUnit 5 — domain logic and mapper tests (no Spring context)
- Idempotency verification — every service test includes duplicate event scenarios

## Observability (Step 9)

### 3 Pillars

| Pillar | Tool | Endpoint |
|--------|------|----------|
| Metrics | Prometheus + Grafana | [http://localhost:9090](http://localhost:9090) / [http://localhost:3000](http://localhost:3000) |
| Tracing | Micrometer Tracing (Brave) + Zipkin | [http://localhost:9411](http://localhost:9411) |
| Logging | Logback with traceId/spanId via MDC | Console output |

### Custom Business Metrics

| Metric | Service |
|--------|---------|
| `orders.created.total` | Order Service |
| `orders.completed.total` | Order Service |
| `orders.failed.total` | Order Service |
| `orders.payment_failed.total` | Order Service |
| `inventory.validated.total` | Inventory Service |
| `inventory.rejected.total` | Inventory Service |
| `inventory.compensated.total` | Inventory Service |
| `payments.success.total` | Payment Service |
| `payments.failed.total` | Payment Service |
| `notifications.order_completed.total` | Notification Service |
| `notifications.order_failed.total` | Notification Service |
| `notifications.payment_failed.total` | Notification Service |

### Verify Observability

```bash
# Check Prometheus metrics endpoint
curl http://localhost:8081/actuator/prometheus | grep orders_created

# Check Prometheus targets (all 4 services should be UP)
open http://localhost:9090/targets

# Check Grafana dashboard
open http://localhost:3000  # admin/admin

# Check distributed traces in Zipkin
open http://localhost:9411
```

### Structured Log Format

Logs include traceId and spanId for cross-service correlation:
```
INFO [order-service,6a3d8f2b1c4e5a7b,9f8e7d6c5b4a3210] Order created | orderId=...
```

## Production Hardening (Step 10)

### Containerization

All 4 services have multi-stage Dockerfiles (JDK build → JRE runtime):

```bash
# Run infra only (dev mode — run services with IDE/mvnw)
docker compose up -d

# Run everything in Docker (infra + services)
docker compose --profile app up -d --build
```

### Graceful Shutdown

All services configured with `server.shutdown: graceful` and 30s timeout, ensuring in-flight Kafka messages and HTTP requests complete before shutdown.

### Kafka Production Tuning

| Config | Value | Purpose |
|--------|-------|---------|
| `compression.type` | snappy | ~50% smaller messages, low CPU |
| `linger.ms` | 20 | Batch messages 20ms before sending |
| `batch.size` | 32768 | 32KB batch (2x default) |
| `max.poll.records` | 100 | Limit records per consumer poll |
| `session.timeout.ms` | 45000 | 45s heartbeat timeout |

### Custom Kafka Health Indicator

Each service has `KafkaHealthIndicator` using `AdminClient.describeCluster()`:

```bash
curl http://localhost:8081/actuator/health | jq '.components.kafkaClusterHealth'
# {"status":"UP","details":{"clusterId":"...","nodeCount":1}}
```

### Docker Profiles

| Profile | application.yml | Purpose |
|---------|-----------------|---------|
| default | `application.yml` | Local dev (localhost URLs) |
| docker | `application-docker.yml` | Docker environment (service name URLs) |

## Useful Commands

```bash
# Stop infrastructure
docker compose down

# Stop everything (infra + services)
docker compose --profile app down

# Stop and remove volumes (reset data)
docker compose down -v

# View Order Service logs
./mvnw spring-boot:run -pl order-service | grep -E "Order|Kafka|Publishing|SUCCESS|FAILED"

# List Kafka topics
docker exec kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092

# Read messages from a topic (note: Avro binary, use Kafka UI for readable view)
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic order.placed \
  --from-beginning
```

## Learning Roadmap

| Step | Content                                   | Status  |
|------|-------------------------------------------|---------|
| 1    | Project Setup & Infrastructure            | DONE    |
| 2    | Order Service - Producer fundamentals     | DONE    |
| 3    | Inventory Service - Consumer fundamentals | DONE    |
| 4    | Saga Choreography - Full happy path       | DONE    |
| 5    | Error Handling & Dead Letter Queue        | DONE    |
| 6    | Idempotency & Exactly-Once Semantics      | DONE    |
| 7    | Schema Evolution & Contract Management    | DONE    |
| 8    | Testing - Unit + Integration              | DONE    |
| 9    | Observability - Metrics, Tracing, Logging | DONE    |
| 10   | Production Hardening & Advanced Topics    | DONE    |
