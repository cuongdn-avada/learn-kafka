# Learning Roadmap Overview — All 10 Steps

## Project: E-Commerce Order Processing Pipeline

Event-driven order processing system using **Apache Kafka 4.0 (KRaft)** + **Spring Boot 3.3.5**.
Implements **Choreography-based Saga** pattern for distributed transactions across 4 microservices.

```
Client → Order Service → [order.placed] → Inventory Service → [order.validated] → Payment Service
                                                                                         │
              Notification Service ← [order.completed] ← Order Service ← [order.paid] ←─┘
```

---

## Step 1: Project Setup & Infrastructure

### Purpose
Xây dựng nền tảng: multi-module Maven project + Docker infrastructure cho Kafka và PostgreSQL.

### Technologies

| Technology | Version | Tại sao chọn |
|------------|---------|--------------|
| **Apache Kafka** | 4.0.0 (KRaft) | KRaft mode = no Zookeeper, metadata quản lý bằng internal Raft consensus. Giảm complexity, ít container, startup nhanh hơn |
| **PostgreSQL** | 16-alpine | ACID compliance, mature, JSON support, database-per-service pattern |
| **Docker Compose** | - | Reproduce production topology trên local. Declarative infrastructure |
| **Maven** | Multi-module | Share code (common module), centralized dependency management, tách repo dễ dàng khi lên production |
| **Kafka UI** | provectuslabs/kafka-ui | Visualize topics, messages, consumer groups, schemas qua web UI |
| **Java** | 21 LTS | Records, sealed classes, virtual threads, pattern matching |
| **Spring Boot** | 3.3.5 | Auto-configuration, opinionated defaults, massive ecosystem |

### What Was Implemented
- 5 Maven modules: `common`, `order-service`, `inventory-service`, `payment-service`, `notification-service`
- `docker-compose.yml`: Kafka (KRaft), PostgreSQL (3 databases), Kafka UI
- `infra/postgres/init-databases.sh`: Script tạo order_db, inventory_db, payment_db
- Parent POM: dependency management cho Spring Boot, Spring Kafka, Lombok

### How to Test

```bash
# Start infrastructure
docker compose up -d

# Verify all containers healthy
docker compose ps

# Verify Kafka
docker exec kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092

# Verify PostgreSQL
docker exec postgres psql -U admin -d order_db -c "SELECT 1"

# Kafka UI
open http://localhost:8088
```

---

## Step 2: Order Service — Producer Fundamentals

### Purpose
Học cách **produce messages** lên Kafka. Order Service là entry point — nhận REST request, tạo order, publish event.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **Spring Web** (REST API) | Standard HTTP API cho client communication. `@RestController` + `@RequestMapping` |
| **KafkaTemplate** | Type-safe wrapper cho Kafka Producer API. Auto-managed lifecycle, CompletableFuture cho async |
| **Spring Data JPA** | Declarative repository pattern. `@Entity` + `Repository` interface = zero SQL boilerplate |
| **Hibernate** | ORM mapping Java objects ↔ database rows. Auto DDL generation cho dev |
| **Jackson** | JSON serialization cho REST API (request/response). Auto-configured bởi Spring Boot |
| **Lombok** | `@Data`, `@Builder`, `@RequiredArgsConstructor` — giảm boilerplate code |

### What Was Implemented
- **REST API**: `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/orders?customerId=`
- **OrderService**: Tính totalAmount, save Order entity, publish `order.placed` event
- **KafkaProducerConfig**: ProducerFactory + KafkaTemplate bean configuration
- **OrderKafkaProducer**: Wrapper class gọi `kafkaTemplate.send()` với orderId as key
- **GlobalExceptionHandler**: `OrderNotFoundException` → 404 ProblemDetail (RFC 7807)
- **OrderEvent** (common): Java record — immutable, `equals()`, `hashCode()`, `toString()` built-in

### Key Concepts Learned
- **Message key** (orderId): Đảm bảo messages cùng order vào cùng partition → ordering guarantee
- **acks=all**: Leader + all replicas phải acknowledge trước khi producer nhận success
- **enable.idempotence=true**: Broker dedup by PID + sequence number → no duplicates on retry
- **CompletableFuture**: Non-blocking send, callback cho success/failure handling

### How to Test

```bash
# Start order-service
./mvnw spring-boot:run -pl order-service

# Create order
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [{"productId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "productName": "MacBook Pro", "quantity": 1, "price": 2499.99}]
  }'

# Get order
curl http://localhost:8081/api/orders/{orderId}

# Verify message in Kafka UI
open http://localhost:8088  # Check order.placed topic
```

### Test Cases (Step 8)

| Test Class | Method | What It Tests |
|------------|--------|---------------|
| `OrderServiceTest` | `createOrder_shouldCalculateTotalAmountAndSave` | Total = sum(qty × price), status = PLACED |
| `OrderServiceTest` | `createOrder_shouldPublishOrderPlacedEvent` | Event published with correct customerId, status, totalAmount |
| `OrderControllerTest` | `createOrder_shouldReturn201WithOrderResponse` | POST → 201 Created + response body |
| `OrderControllerTest` | `getOrder_shouldReturn200WithOrderResponse` | GET → 200 OK + order data |
| `OrderControllerTest` | `getOrder_shouldReturn404_whenNotFound` | GET → 404 ProblemDetail (RFC 7807) |
| `OrderControllerTest` | `getOrdersByCustomer_shouldReturn200WithList` | GET list → 200 OK + array |
| `OrderControllerTest` | `getOrdersByCustomer_shouldReturnEmptyList_whenNoOrders` | GET list → 200 OK + empty array |

---

## Step 3: Inventory Service — Consumer Fundamentals

### Purpose
Học cách **consume messages** từ Kafka. Inventory Service nhận event, validate stock, publish kết quả.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **@KafkaListener** | Declarative consumer — Spring quản lý consumer lifecycle, offset commit, error handling |
| **ConcurrentKafkaListenerContainerFactory** | Multi-threaded consumer. `setConcurrency(3)` = 3 threads = match 3 partitions |
| **ConsumerFactory** | Factory pattern tạo Kafka Consumer instances. Centralized config |
| **DataInitializer** | `CommandLineRunner` seed 4 sample products vào DB khi startup |

### What Was Implemented
- **InventoryKafkaConsumer**: `@KafkaListener(topics = "order.placed")` → delegate to service
- **InventoryService**: `processOrderPlaced()` — check stock cho tất cả items trong order
- **Product entity**: `reserveStock()` (decrease available, increase reserved), `releaseStock()` (reverse)
- **InventoryKafkaProducer**: Publish `order.validated` (stock OK) hoặc `order.failed` (insufficient)
- **DataInitializer**: Seed 4 products (MacBook, Mouse, iPhone, Watch — Watch có stock=0 để test failure)

### Key Concepts Learned
- **Consumer Group**: `inventory-service-group` — multiple instances share partitions, each message processed once
- **auto-offset-reset: earliest**: Không miss messages khi consumer join lần đầu
- **Concurrency = partition count**: 3 partitions × 1 consumer = 3 threads, tối đa throughput
- **Batch lookup**: `findAllByIdIn()` — 1 query cho tất cả products thay vì N queries (N+1 problem)
- **Domain logic in entity**: `Product.reserveStock()` encapsulate validation + state change

### How to Test

```bash
# Start inventory-service (auto-seeds 4 products)
./mvnw spring-boot:run -pl inventory-service

# Create order (từ order-service) → check inventory-service logs
# Stock OK → logs: "Stock reserved successfully"
# Stock FAIL → logs: "Insufficient stock"

# Test stock failure (Apple Watch Ultra has stock=0)
curl -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000","items":[{"productId":"ac9e6679-7425-40de-944b-e07fc1f90af0","productName":"Apple Watch Ultra","quantity":1,"price":799.99}]}'
```

### Test Cases (Step 8)

| Test Class | Method | What It Tests |
|------------|--------|---------------|
| `ProductTest` | `hasStock_shouldReturnTrue_whenSufficientQuantity` | available >= requested |
| `ProductTest` | `hasStock_shouldReturnFalse_whenInsufficientQuantity` | available < requested |
| `ProductTest` | `hasStock_shouldReturnFalse_whenZeroAvailable` | available = 0 |
| `ProductTest` | `hasStock_shouldReturnTrue_whenZeroRequested` | request 0 items |
| `ProductTest` | `reserveStock_shouldDecreaseAvailableAndIncreaseReserved` | available -= qty, reserved += qty |
| `ProductTest` | `reserveStock_shouldHandleMultipleReservations` | 2 reservations accumulate |
| `ProductTest` | `reserveStock_shouldReserveExactlyAllAvailable` | reserve entire stock |
| `ProductTest` | `reserveStock_shouldThrow_whenInsufficientStock` | IllegalStateException |
| `ProductTest` | `releaseStock_shouldIncreaseAvailableAndDecreaseReserved` | reverse of reserve |
| `ProductTest` | `releaseStock_shouldHandlePartialRelease` | release less than reserved |
| `ProductTest` | `releaseStock_shouldThrow_whenReleasingMoreThanReserved` | IllegalStateException |
| `ProductTest` | `reserveThenRelease_shouldRestoreOriginalQuantities` | reserve + release = original |
| `InventoryServiceTest` | `processOrderPlaced_shouldReserveStockAndPublishValidated` | Happy path: reserve → order.validated |
| `InventoryServiceTest` | `processOrderPlaced_shouldHandleMultipleItems` | Multi-item order |
| `InventoryServiceTest` | `processOrderPlaced_shouldPublishFailed_whenProductNotFound` | Unknown product → order.failed |
| `InventoryServiceTest` | `processOrderPlaced_shouldPublishFailed_whenInsufficientStock` | No stock → order.failed |

---

## Step 4: Saga Choreography — Full Happy Path

### Purpose
Hoàn thành **full Saga flow**: Order → Inventory → Payment → Order → Notification. Cả happy path và failure paths.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **Choreography Saga** | Event-driven thuần túy — không có orchestrator. Mỗi service listen events → react → publish events. Giảm single point of failure |
| **Payment threshold rule** | Amount ≤ $10,000 → SUCCESS, > $10,000 → FAILED. Deterministic rule cho phép test chính xác cả 2 paths |

### What Was Implemented
- **PaymentService**: Consume `order.validated` → check amount threshold → publish `order.paid` hoặc `payment.failed`
- **NotificationService**: Consume `order.completed`, `order.failed`, `payment.failed` → log notification (simulate email)
- **Order Service consumers**: Consume `order.paid` → update COMPLETED, consume `order.failed`/`payment.failed` → update FAILED

### Saga Flows

```
Flow 1: Happy Path (amount ≤ $10,000)
  PLACED → order.placed → VALIDATED → order.validated → PAID → order.paid → COMPLETED → order.completed → notification

Flow 2: Stock Failure
  PLACED → order.placed → order.failed → FAILED → notification

Flow 3: Payment Failure (amount > $10,000)
  PLACED → order.placed → VALIDATED → order.validated → payment.failed → PAYMENT_FAILED
                                                       → Inventory compensates (release stock)
                                                       → notification
```

### Key Concepts Learned
- **Choreography vs Orchestration**: Choreography = decentralized, mỗi service autonomous. Orchestration = central coordinator
- **Compensation**: Khi payment fail → inventory service phải release reserved stock (undo)
- **Event naming**: past tense (order.placed, order.validated) — events are facts that happened

### How to Test

```bash
# Start all 4 services

# Test Flow 1: Happy Path (amount ≤ $10,000)
curl -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000","items":[{"productId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","productName":"MacBook Pro","quantity":1,"price":2499.99}]}'
# → Check order status: COMPLETED

# Test Flow 2: Stock Failure
curl -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000","items":[{"productId":"ac9e6679-7425-40de-944b-e07fc1f90af0","productName":"Apple Watch Ultra","quantity":1,"price":799.99}]}'
# → Check order status: FAILED

# Test Flow 3: Payment Failure (5 × $2499.99 = $12,499.95 > $10,000)
curl -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000","items":[{"productId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","productName":"MacBook Pro","quantity":5,"price":2499.99}]}'
# → Check order status: PAYMENT_FAILED

# Verify order status
curl http://localhost:8081/api/orders/{orderId}
```

### Test Cases (Step 8)

| Test Class | Method | What It Tests |
|------------|--------|---------------|
| `PaymentServiceTest` | `processOrderValidated_shouldSucceed_whenAmountBelowThreshold` | $5,000 → SUCCESS → order.paid |
| `PaymentServiceTest` | `processOrderValidated_shouldSucceed_whenAmountExactlyAtThreshold` | $10,000 → SUCCESS (boundary) |
| `PaymentServiceTest` | `processOrderValidated_shouldFail_whenAmountExceedsThreshold` | $15,000 → FAILED → payment.failed |
| `PaymentServiceTest` | `processOrderValidated_shouldFail_whenAmountJustAboveThreshold` | $10,000.01 → FAILED (boundary) |
| `PaymentServiceTest` | `processOrderValidated_shouldSucceed_whenAmountIsZero` | $0 → SUCCESS (edge case) |
| `PaymentServiceTest` | `processOrderValidated_shouldPreserveEventData` | Event data preserved in published message |
| `OrderServiceTest` | `completeOrder_shouldUpdateStatusToCompleted` | order.paid → COMPLETED |
| `OrderServiceTest` | `failOrder_shouldUpdateStatusToFailed` | order.failed → FAILED + reason |
| `OrderServiceTest` | `handlePaymentFailure_shouldUpdateStatusToPaymentFailed` | payment.failed → PAYMENT_FAILED |
| `NotificationServiceTest` | `notifyOrderCompleted_shouldProcessFirstEvent` | Log notification for completed order |
| `NotificationServiceTest` | `notifyOrderFailed_shouldProcessFirstEvent` | Log notification for failed order |
| `NotificationServiceTest` | `notifyPaymentFailed_shouldProcessFirstEvent` | Log notification for payment failure |

---

## Step 5: Error Handling & Dead Letter Queue

### Purpose
Xử lý **consumer failures** — khi message processing fail sau nhiều retries, chuyển vào Dead Letter Topic thay vì block queue.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **DefaultErrorHandler** | Spring Kafka built-in error handler. Configurable retry + recovery strategy |
| **ExponentialBackOff** | Tăng delay giữa các retry: 1s → 2s → 4s → 8s → 10s (max). Tránh overwhelm downstream |
| **DeadLetterPublishingRecoverer** | Sau max retries, publish failed message vào `<topic>.DLT`. Preserve message cho investigation |

### What Was Implemented
- **KafkaConsumerConfig** (all 4 services): ExponentialBackOff (1s initial, 2x multiplier, 10s max, 30s total)
- **DeadLetterPublishingRecoverer**: Failed messages → `<original-topic>.DLT`
- **DLT topics**: 6 DLT topics created bởi `kafka-init` container
- **DLT consumers** (all 4 services): `@KafkaListener(topics = "order.placed.DLT")` → log ERROR with `[DLT]` prefix

### Retry Timeline
```
Attempt 1: Process message        → FAIL
  Wait 1s (initial interval)
Attempt 2: Retry                  → FAIL
  Wait 2s (×2 multiplier)
Attempt 3: Retry                  → FAIL
  Wait 4s (×2 multiplier)
Attempt 4: Retry                  → FAIL
  → Total elapsed > 30s → give up
  → Publish to order.placed.DLT
  → DLT consumer logs ERROR
```

### Key Concepts Learned
- **At-least-once delivery**: Kafka guarantees message delivered at least once, but failures can occur during processing
- **Backoff strategy**: Exponential > Fixed — avoids thundering herd on transient failures
- **Dead Letter Queue**: Preserve problematic messages instead of dropping — can replay later
- **DLT consumer pattern**: Separate listener for DLT topics — logging, alerting, manual retry

### How to Test

```bash
# Verify DLT topics exist
docker exec kafka /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092 | grep DLT

# Expected output:
# order.completed.DLT
# order.failed.DLT
# order.paid.DLT
# order.placed.DLT
# order.validated.DLT
# payment.failed.DLT

# Check DLT consumer logs (grep for [DLT] prefix)
# DLT messages visible in Kafka UI at http://localhost:8088
```

### Test Cases
Step 5 focuses on infrastructure config (error handler, backoff, DLT). Testing done via:
- Integration verification: manually cause failures → observe retry logs → DLT message in Kafka UI
- Config verified by: service startup without errors (Spring context loads DLT config correctly)

---

## Step 6: Idempotency & Exactly-Once Semantics

### Purpose
Đảm bảo **duplicate messages** không gây duplicate side effects. Kafka at-least-once = duplicates are inevitable.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **ProcessedEvent table** | Database-level dedup: eventId as primary key. Check existsById() trước khi process |
| **@Transactional** | Check + process + save idempotency key trong cùng 1 transaction → atomic |
| **ConcurrentHashMap.newKeySet()** | In-memory dedup cho notification-service (stateless, no DB). Thread-safe Set |
| **Producer idempotence** | `enable.idempotence=true` — broker dedup by PID + sequence number |

### What Was Implemented
- **ProcessedEvent** entity (order, inventory, payment services): `eventId` (PK) + `topic` + `processedAt`
- **ProcessedEventRepository**: `existsById(eventId)` check
- **Idempotency guard** in all services:
  ```java
  if (processedEventRepository.existsById(event.eventId())) {
      log.warn("Duplicate event detected, skipping");
      return;
  }
  // ... process message ...
  processedEventRepository.save(new ProcessedEvent(event.eventId(), topic));
  ```
- **NotificationService**: `ConcurrentHashMap.newKeySet()` — best-effort dedup (no persistence needed)

### Key Concepts Learned
- **Idempotent consumer**: Processing same message N times produces same result as processing once
- **Dedup token**: eventId (UUID) generated by producer → unique per event
- **Atomic dedup**: Check + process + save in same @Transactional → no race condition
- **Trade-off**: ProcessedEvent table grows → need cleanup job in production (TTL or scheduled delete)

### How to Test

```bash
# Unit tests verify idempotency for every service
./mvnw test

# Specific idempotency tests:
./mvnw test -pl order-service -Dtest="OrderServiceTest#completeOrder_shouldSkipDuplicateEvent"
./mvnw test -pl order-service -Dtest="OrderServiceTest#failOrder_shouldSkipDuplicateEvent"
./mvnw test -pl inventory-service -Dtest="InventoryServiceTest#processOrderPlaced_shouldSkipDuplicateEvent"
./mvnw test -pl inventory-service -Dtest="InventoryServiceTest#compensateReservation_shouldSkipDuplicateEvent"
./mvnw test -pl payment-service -Dtest="PaymentServiceTest#processOrderValidated_shouldSkipDuplicateEvent"
./mvnw test -pl notification-service -Dtest="NotificationServiceTest#notifyOrderCompleted_shouldSkipDuplicateEvent"
```

### Test Cases (Step 8)

| Test Class | Method | What It Tests |
|------------|--------|---------------|
| `OrderServiceTest` | `completeOrder_shouldSkipDuplicateEvent` | Duplicate eventId → skip, no DB update |
| `OrderServiceTest` | `failOrder_shouldSkipDuplicateEvent` | Duplicate eventId → skip |
| `OrderServiceTest` | `handlePaymentFailure_shouldSkipDuplicateEvent` | Duplicate eventId → skip |
| `InventoryServiceTest` | `processOrderPlaced_shouldSkipDuplicateEvent` | Duplicate → no stock change, no publish |
| `InventoryServiceTest` | `compensateReservation_shouldSkipDuplicateEvent` | Duplicate compensation → skip |
| `PaymentServiceTest` | `processOrderValidated_shouldSkipDuplicateEvent` | Duplicate → no payment, no publish |
| `NotificationServiceTest` | `notifyOrderCompleted_shouldSkipDuplicateEvent` | Duplicate → skip notification |
| `NotificationServiceTest` | `notifyOrderFailed_shouldSkipDuplicateEvent` | Duplicate → skip notification |
| `NotificationServiceTest` | `notifyPaymentFailed_shouldSkipDuplicateEvent` | Duplicate → skip notification |
| `NotificationServiceTest` | `dedup_shouldBeSharedAcrossMethods` | Same eventId across different methods → shared Set |

---

## Step 7: Schema Evolution & Contract Management

### Purpose
Migrate từ **JSON** sang **Avro** serialization với **Schema Registry** — enforce contracts giữa producers và consumers.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **Apache Avro** | Schema-based binary serialization. ~30-50% nhỏ hơn JSON. Type-safe, backward compatible |
| **Confluent Schema Registry** | Centralized schema management. Validate compatibility trước khi publish. Prevent breaking changes |
| **KafkaAvroSerializer** | Serialize Java objects → Avro binary + register schema with Schema Registry |
| **KafkaAvroDeserializer** | Deserialize Avro binary → Java objects using schema from Registry |
| **SpecificRecord** (code gen) | Generate Java classes from `.avsc` schema → type-safe, IDE-friendly (vs GenericRecord) |
| **avro-maven-plugin** | Maven plugin auto-generate Java classes from Avro schema during build |

### What Was Implemented
- **Avro schema**: `common/src/main/avro/order_event.avsc` — defines OrderEventAvro structure
- **OrderEventAvro**: Generated SpecificRecord class in `target/generated-sources/avro/`
- **OrderEventMapper**: Bidirectional conversion `OrderEvent` (Java record) ↔ `OrderEventAvro` (Avro)
- **Migrated all 4 services**: JsonSerializer → KafkaAvroSerializer, JsonDeserializer → KafkaAvroDeserializer
- **Schema evolution fields**: `schemaVersion` (int, default=1), `source` (nullable string, default=null)

### Schema Architecture
```
REST API Layer:     JSON (Jackson)      → Client communication
                        ↕ OrderEvent (Java record)
Kafka Layer:        Avro (Schema Registry) → Inter-service events
                        ↕ OrderEventMapper.toAvro() / fromAvro()
Avro Layer:         OrderEventAvro (SpecificRecord)
```

### Key Concepts Learned
- **BACKWARD compatibility** (default): New schema can read old data. Safe evolution rule
- **TopicNameStrategy**: Schema subject = `<topic>-value` (e.g., `order.placed-value`)
- **Schema evolution**: Add optional fields with defaults = backward compatible (safe)
- **SpecificRecord vs GenericRecord**: Specific = generated, type-safe. Generic = dynamic, flexible
- **Avro binary**: No field names in payload (use schema ID) → much smaller than JSON

### How to Test

```bash
# Build (generates Avro classes)
./mvnw clean install -DskipTests

# Verify Avro class generation
ls common/target/generated-sources/avro/dnc/cuong/common/avro/

# Test mapper round-trip
./mvnw test -pl common

# Verify Schema Registry (after running services)
curl http://localhost:8085/subjects
curl http://localhost:8085/subjects/order.placed-value/versions/latest
curl http://localhost:8085/config   # Check compatibility level
```

### Test Cases (Step 8)

| Test Class | Method | What It Tests |
|------------|--------|---------------|
| `OrderEventMapperTest` | `toAvro_shouldConvertAllFieldsCorrectly` | All fields mapped: orderId, customerId, items, amount, status |
| `OrderEventMapperTest` | `toAvro_shouldHandleReasonField` | Nullable reason field → Avro nullable union |
| `OrderEventMapperTest` | `fromAvro_shouldConvertAllFieldsCorrectly` | Avro → Java record: all fields preserved |
| `OrderEventMapperTest` | `roundTrip_shouldPreserveAllData` | toAvro → fromAvro = original data (lossless) |
| `OrderEventMapperTest` | `toAvro_shouldMapAllStatusValues` | All 6 OrderStatus enum values map correctly |
| `OrderEventMapperTest` | `toAvro_shouldHandleMultipleItems` | Multi-item order conversion |

---

## Step 8: Testing — Unit + Integration

### Purpose
Viết **59 unit tests** cover toàn bộ business logic. Test chạy offline — không cần Kafka, DB, Schema Registry.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **JUnit 5** | Modern test framework. `@Test`, `@BeforeEach`, `@ExtendWith`, assertions |
| **Mockito** | Mock dependencies (DB, Kafka). `@Mock`, `@InjectMocks`, `@Spy`. Isolate unit under test |
| **MockitoExtension** | `@ExtendWith(MockitoExtension.class)` — no Spring context → tests run in milliseconds |
| **MockMvc** | Test REST controllers without starting HTTP server. `@WebMvcTest` + `MockMvc` |
| **ArgumentCaptor** | Capture arguments passed to mock methods → verify exact content of published events |
| **SimpleMeterRegistry** | In-memory MeterRegistry cho test — no Prometheus needed. `@Spy` wraps real implementation |

### Test Strategy

| Layer | Pattern | Speed | What It Tests |
|-------|---------|-------|---------------|
| Domain | Pure JUnit 5 | < 1ms | Business logic (reserveStock, releaseStock) |
| Service | `@ExtendWith(MockitoExtension)` | ~5ms | Business flow + idempotency (mock DB + Kafka) |
| Controller | `@WebMvcTest` + MockMvc | ~100ms | REST API endpoints, status codes, response format |
| Mapper | Pure JUnit 5 | < 1ms | Avro ↔ OrderEvent conversion round-trip |

### What Was Implemented
- **7 test classes**, **59 tests** across all 5 modules
- **Test patterns**: Mockito mocks, ArgumentCaptor, boundary testing, idempotency verification
- **No infrastructure needed**: All tests run offline (mock DB + Kafka)
- **Runtime**: ~7 seconds total

### Test Distribution

| Module | Test Class | Tests | Focus |
|--------|-----------|-------|-------|
| common | `OrderEventMapperTest` | 6 | Avro conversion (round-trip, all statuses) |
| order-service | `OrderServiceTest` | 12 | createOrder, complete, fail, paymentFailure, getOrder |
| order-service | `OrderControllerTest` | 5 | REST API (201, 200, 404 ProblemDetail) |
| inventory-service | `ProductTest` | 13 | Domain logic (hasStock, reserve, release, boundaries) |
| inventory-service | `InventoryServiceTest` | 8 | Stock validation, compensation, idempotency |
| payment-service | `PaymentServiceTest` | 7 | Payment threshold ($10K boundary), idempotency |
| notification-service | `NotificationServiceTest` | 8 | In-memory dedup, cross-method shared Set |

### How to Test

```bash
# Run all 59 tests
./mvnw clean test

# Run specific module
./mvnw test -pl order-service
./mvnw test -pl inventory-service
./mvnw test -pl payment-service
./mvnw test -pl notification-service
./mvnw test -pl common

# Run specific test class
./mvnw test -pl payment-service -Dtest="PaymentServiceTest"

# Run specific test method
./mvnw test -pl payment-service -Dtest="PaymentServiceTest#processOrderValidated_shouldFail_whenAmountJustAboveThreshold"
```

---

## Step 9: Observability — Metrics, Tracing, Logging

### Purpose
Add **3 pillars of observability**: biết WHAT (metrics), WHERE (tracing), WHY (logging) khi vấn đề xảy ra.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **Micrometer** | Vendor-neutral metrics facade. Counter, Gauge, Timer, Histogram |
| **Prometheus** | Pull-based metrics server. PromQL cho querying. Industry standard |
| **Grafana** | Rich dashboard UI. Auto-provision datasource + dashboard via config |
| **Micrometer Tracing (Brave)** | Distributed tracing bridge. Instrument Spring + Kafka automatically |
| **Zipkin** | Trace visualization UI. Lightweight, in-memory storage (đủ cho dev) |
| **Logback + MDC** | Structured logging. `%X{traceId}` inject trace context vào log output |

### What Was Implemented

**Metrics (12 custom counters):**
| Counter | Service | When Incremented |
|---------|---------|-----------------|
| `orders.created.total` | Order | New order created |
| `orders.completed.total` | Order | Saga completed successfully |
| `orders.failed.total` | Order | Stock validation failed |
| `orders.payment_failed.total` | Order | Payment declined |
| `inventory.validated.total` | Inventory | Stock reserved OK |
| `inventory.rejected.total` | Inventory | Insufficient stock |
| `inventory.compensated.total` | Inventory | Stock released (compensation) |
| `payments.success.total` | Payment | Payment processed OK |
| `payments.failed.total` | Payment | Payment declined |
| `notifications.order_completed.total` | Notification | Completion notification sent |
| `notifications.order_failed.total` | Notification | Failure notification sent |
| `notifications.payment_failed.total` | Notification | Payment failure notification sent |

**Tracing:**
- Kafka observation enabled → traceId propagated via Kafka headers
- All services share same traceId for single order flow

**Logging:**
- Pattern: `INFO [order-service,abc123def,span456] Order created | orderId=...`

**Infrastructure:**
- Prometheus (v2.51.0, port 9090) — scrapes 4 services every 15s
- Grafana (10.4.0, port 3000) — pre-built dashboard with 8 panels
- Zipkin (3.4, port 9411) — trace visualization

### Key Concepts Learned
- **Pull vs Push metrics**: Prometheus pulls (scrapes). Push = Datadog/InfluxDB agent
- **Counter vs Gauge**: Counter only increases (orders.created). Gauge goes up/down (active connections)
- **Trace propagation**: traceId injected in Kafka headers by producer, extracted by consumer
- **MDC (Mapped Diagnostic Context)**: Thread-local key-value store → Logback reads via `%X{traceId}`

### How to Test

```bash
# Check Prometheus metrics
curl http://localhost:8081/actuator/prometheus | grep orders_created

# Check Prometheus targets (all services UP)
open http://localhost:9090/targets

# Check Grafana dashboard
open http://localhost:3000   # admin/admin

# Check distributed traces
open http://localhost:9411   # Zipkin

# Create order → observe trace spanning all services
curl -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000","items":[{"productId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","productName":"MacBook Pro","quantity":1,"price":2499.99}]}'

# Check Zipkin for trace with 4 spans
open http://localhost:9411
```

### Test Cases
Step 9 tests are verified via existing Step 8 tests (added `@Spy MeterRegistry` to all test classes):
- All 59 tests still pass with MeterRegistry injection
- Metrics initialization tested via `@BeforeEach initMetrics()` in each test

---

## Step 10: Production Hardening & Advanced Topics

### Purpose
Biến hệ thống từ "chạy được trên máy dev" thành **sẵn sàng deploy**: containerization, graceful shutdown, tuning, health checks.

### Technologies

| Technology | Tại sao chọn |
|------------|--------------|
| **Multi-stage Docker build** | JDK (build) → JRE (runtime). Image ~200MB vs ~800MB. Minimal attack surface |
| **eclipse-temurin** | Official OpenJDK distribution (Adoptium). Alpine variant cho minimal size |
| **Docker Compose profiles** | `profiles: [app]` — infra-only vs full-stack mode. Single docker-compose.yml |
| **G1GC** | Default GC cho Java 11+. Balance throughput + latency. Good cho microservice heap < 4GB |
| **Snappy compression** | Kafka message compression. ~50% size reduction, very low CPU overhead |
| **AdminClient** | Kafka admin operations. `describeCluster()` cho health check — real broker connectivity |
| **Spring profiles** | `application-docker.yml` override localhost → Docker service names |

### What Was Implemented
- **Graceful shutdown**: `server.shutdown: graceful` + 30s timeout (all 4 services)
- **Kafka tuning**: snappy compression, linger.ms=20, batch.size=32KB, consumer poll/session tuning
- **KafkaHealthIndicator**: AdminClient.describeCluster() with 5s timeout (all 4 services)
- **Dockerfiles**: Multi-stage build, JVM flags: G1GC + MaxRAMPercentage=75%
- **Docker Compose**: 4 service containers with profiles, health checks, resource limits (512MB)
- **application-docker.yml**: Override URLs for Docker network (kafka:9092, postgres:5432, etc.)

### How to Test

```bash
# Run all tests (verify nothing broken)
./mvnw clean test

# Build JARs
./mvnw clean package -DskipTests

# Dev mode (infra only → run services with IDE)
docker compose up -d
./mvnw spring-boot:run -pl order-service

# Full mode (infra + services in Docker)
docker compose --profile app up -d --build

# Verify health check
curl http://localhost:8081/actuator/health | jq '.components.kafkaClusterHealth'
# Expected: {"status":"UP","details":{"clusterId":"...","nodeCount":1}}

# Test full order flow in Docker
curl -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000","items":[{"productId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","productName":"MacBook Pro","quantity":1,"price":2499.99}]}'

# Verify order completed
curl http://localhost:8081/api/orders/{orderId}

# Stop (graceful shutdown)
docker compose --profile app down
```

### Test Cases
Step 10 changes are config-only (YAML + Dockerfile + health indicator). Verified by:
- All 59 existing tests pass (no business logic change)
- `./mvnw clean package -DskipTests` succeeds (JARs built for Docker)
- Docker health checks validate runtime correctness

---

## Complete Test Summary

### 59 Unit Tests — All Offline, ~7 seconds

```
Module              Test Class              Tests   Focus
─────────────────────────────────────────────────────────────
common              OrderEventMapperTest      6     Avro ↔ OrderEvent conversion
order-service       OrderServiceTest         12     Order lifecycle (create, complete, fail)
order-service       OrderControllerTest       5     REST API (201, 200, 404)
inventory-service   ProductTest              13     Domain logic (stock reserve/release)
inventory-service   InventoryServiceTest      8     Stock validation + compensation
payment-service     PaymentServiceTest        7     Payment threshold ($10K boundary)
notification-service NotificationServiceTest  8     In-memory dedup + notification
─────────────────────────────────────────────────────────────
TOTAL                                        59
```

### Run Commands

```bash
# All tests
./mvnw clean test

# By module
./mvnw test -pl common
./mvnw test -pl order-service
./mvnw test -pl inventory-service
./mvnw test -pl payment-service
./mvnw test -pl notification-service

# By test class
./mvnw test -pl order-service -Dtest="OrderServiceTest"
./mvnw test -pl order-service -Dtest="OrderControllerTest"

# By test method
./mvnw test -pl payment-service -Dtest="PaymentServiceTest#processOrderValidated_shouldFail_whenAmountJustAboveThreshold"
```

---

## Infrastructure Ports

| Service | Port | URL | Purpose |
|---------|------|-----|---------|
| Kafka | 9094 | localhost:9094 | Event broker (KRaft mode) |
| Order Service | 8081 | http://localhost:8081 | REST API + Kafka producer |
| Inventory Service | 8082 | http://localhost:8082 | Stock management |
| Payment Service | 8083 | http://localhost:8083 | Payment processing |
| Notification Service | 8084 | http://localhost:8084 | Notification consumer |
| Schema Registry | 8085 | http://localhost:8085 | Avro schema management |
| Kafka UI | 8088 | http://localhost:8088 | Web UI for Kafka |
| PostgreSQL | 5432 | localhost:5432 | 3 databases |
| Prometheus | 9090 | http://localhost:9090 | Metrics scraping |
| Grafana | 3000 | http://localhost:3000 | Dashboards (admin/admin) |
| Zipkin | 9411 | http://localhost:9411 | Distributed tracing |
