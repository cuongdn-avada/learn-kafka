# Step 2: Order Service - Producer Fundamentals — Sequence Diagram

## Mermaid Sequence Diagram

Paste the diagram below into [mermaid.live](https://mermaid.live) or any Mermaid-compatible viewer to visualize.

### Create Order Flow (Happy Path)

```mermaid
sequenceDiagram
    actor Client
    participant Controller as OrderController<br/>(REST API)
    participant Service as OrderService<br/>(Business Logic)
    participant DB as PostgreSQL<br/>(order_db)
    participant Producer as OrderKafkaProducer
    participant Template as KafkaTemplate
    participant Kafka as Kafka Broker<br/>(order.placed topic)

    Client->>Controller: POST /api/orders<br/>{customerId, items[]}

    Note over Controller: @Valid validates request

    Controller->>Service: createOrder(OrderCreateRequest)

    Note over Service: @Transactional begins

    Service->>Service: Calculate totalAmount<br/>sum(item.price * item.quantity)

    Service->>Service: Build Order entity<br/>status = PLACED

    Service->>Service: Add OrderItems<br/>(bidirectional relationship)

    Service->>DB: orderRepository.save(order)
    DB-->>Service: savedOrder (with generated UUID)

    Note over Service: Log: Order created | orderId=... | totalAmount=...

    Service->>Service: Build OrderEvent<br/>OrderEvent.create(orderId, customerId,<br/>items, totalAmount, PLACED)

    Note over Service: EventId = UUID.randomUUID()<br/>CreatedAt = Instant.now()

    Service->>Producer: sendOrderPlaced(OrderEvent)

    Note over Producer: key = orderId.toString()<br/>(ensures partition ordering)

    Producer->>Producer: Log: Publishing event to [order.placed]<br/>key=... | eventId=... | status=PLACED

    Producer->>Template: send("order.placed", key, event)

    Note over Template: JsonSerializer serializes event<br/>StringSerializer serializes key<br/>Adds __TypeId__ header

    Template->>Kafka: ProducerRecord<br/>(topic, key, value)

    Note over Kafka: Partition = hash(key) % 3<br/>acks=all (wait for all replicas)

    Kafka-->>Template: RecordMetadata<br/>(partition, offset)

    Template-->>Producer: CompletableFuture<SendResult>

    Note over Producer: Async callback: whenComplete()

    alt Success
        Producer->>Producer: Log: SUCCESS published to [order.placed]<br/>partition=X | offset=Y | key=...
    else Failure
        Producer->>Producer: Log: FAILED to publish event<br/>error=...
    end

    Note over Service: @Transactional commits

    Service-->>Controller: Order entity

    Controller->>Controller: OrderResponse.from(order)<br/>(convert entity → DTO)

    Controller-->>Client: 201 CREATED<br/>{orderId, customerId,<br/>totalAmount, status, items[]}
```

### Get Order by ID Flow

```mermaid
sequenceDiagram
    actor Client
    participant Controller as OrderController
    participant Service as OrderService
    participant DB as PostgreSQL<br/>(order_db)

    Client->>Controller: GET /api/orders/{orderId}

    Controller->>Service: getOrder(UUID orderId)

    Note over Service: @Transactional(readOnly = true)

    Service->>DB: findById(orderId)

    alt Order found
        DB-->>Service: Optional<Order>
        Service-->>Controller: Order entity
        Controller->>Controller: OrderResponse.from(order)
        Controller-->>Client: 200 OK<br/>{orderId, status, items...}
    else Order not found
        Service->>Service: throw OrderNotFoundException
        Note over Controller: GlobalExceptionHandler catches
        Controller-->>Client: 404 NOT FOUND<br/>ProblemDetail (RFC 7807)<br/>{title, status, detail, timestamp}
    end
```

### Component Architecture (Step 2)

```mermaid
graph TB
    subgraph "Client"
        REST[HTTP Client / cURL]
    end

    subgraph "order-service (port 8081)"
        subgraph "Controller Layer"
            OC[OrderController<br/>@RestController]
            GEH[GlobalExceptionHandler<br/>@RestControllerAdvice]
        end

        subgraph "Service Layer"
            OS[OrderService<br/>@Service @Transactional]
        end

        subgraph "Domain Layer"
            OR[Order<br/>@Entity]
            OI[OrderItem<br/>@Entity]
            REPO[OrderRepository<br/>JpaRepository]
        end

        subgraph "Kafka Layer"
            PROD[OrderKafkaProducer<br/>@Component]
            CFG[KafkaProducerConfig<br/>@Configuration]
            KT[KafkaTemplate<br/>String, OrderEvent]
        end
    end

    subgraph "common module"
        OE[OrderEvent<br/>record]
        OSTAT[OrderStatus<br/>enum]
        KTOPICS[KafkaTopics<br/>constants]
        DTO[OrderCreateRequest<br/>record]
    end

    subgraph "Infrastructure (Docker)"
        PG[(PostgreSQL<br/>order_db)]
        KAFKA[Kafka Broker<br/>order.placed topic<br/>3 partitions]
        KUI[Kafka UI<br/>:8088]
    end

    REST -->|POST/GET| OC
    OC --> OS
    OS --> REPO
    REPO --> PG
    OR --> OI
    OS --> PROD
    PROD --> KT
    CFG --> KT
    KT -->|"key=orderId<br/>value=OrderEvent (JSON)"| KAFKA
    KAFKA -.-> KUI

    OS -.-> OE
    OS -.-> OSTAT
    OC -.-> DTO
    PROD -.-> KTOPICS

    style KAFKA fill:#e74c3c,color:#fff
    style PG fill:#336791,color:#fff
    style KUI fill:#2ecc71,color:#fff
```

### Kafka Producer Configuration

```mermaid
graph LR
    subgraph "application.yml"
        YML["spring.kafka.bootstrap-servers: localhost:9094<br/>spring.kafka.producer.acks: all<br/>spring.kafka.producer.properties.enable.idempotence: true"]
    end

    subgraph "KafkaProducerConfig.java"
        PF[ProducerFactory]
        KT2[KafkaTemplate]
    end

    subgraph "Serialization"
        KS[StringSerializer<br/>for keys]
        VS[JsonSerializer<br/>for OrderEvent values]
    end

    subgraph "Producer Properties"
        P1["acks=all → wait all replicas"]
        P2["idempotence=true → no duplicates"]
        P3["__TypeId__ header → type info"]
    end

    YML -->|KafkaProperties| PF
    PF --> KT2
    PF --> KS
    PF --> VS
    PF --> P1
    PF --> P2
    VS --> P3
```

## Key Concepts in Step 2

| Concept | Where | Why |
|---------|-------|-----|
| **KafkaTemplate** | `KafkaProducerConfig` | Type-safe wrapper for Kafka Producer |
| **Message Key** | `OrderKafkaProducer` (orderId) | Same orderId → same partition → ordering guarantee |
| **CompletableFuture** | `sendOrderPlaced()` | Non-blocking async publish, no thread blocking |
| **acks=all** | `application.yml` | Message durability — all replicas must acknowledge |
| **Idempotent producer** | `application.yml` | Prevents duplicate messages on retry |
| **JsonSerializer** | `KafkaProducerConfig` | Serialize OrderEvent to JSON with type headers |
| **@Transactional** | `OrderService` | DB operations atomic — save + publish in one unit |
| **Record (immutable)** | `OrderEvent`, `OrderCreateRequest` | Events are immutable — never modified after creation |
| **ProblemDetail** | `GlobalExceptionHandler` | RFC 7807 standard error response format |
| **UUID as ID** | `Order` entity | Globally unique, safe for Kafka partition routing |

## Dual-Write Problem (Known Limitation)

```mermaid
graph TD
    A[OrderService.createOrder] --> B[DB: save order]
    B --> C[Kafka: publish event]
    C -->|"What if this fails?"| D["Order saved in DB<br/>but event NOT published"]
    D --> E["Solution: Outbox Pattern<br/>(Step 6)"]

    style D fill:#e74c3c,color:#fff
    style E fill:#f39c12,color:#fff
```

Currently, if Kafka publish fails after DB save, the order exists in the database but no event is sent. This is the **dual-write problem** and will be solved with the **Outbox Pattern** in Step 6.
