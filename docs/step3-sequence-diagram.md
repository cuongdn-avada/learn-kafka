# Step 3: Inventory Service — Consumer Fundamentals — Sequence Diagram

## Mermaid Sequence Diagram

Paste the diagram below into [mermaid.live](https://mermaid.live) or any Mermaid-compatible viewer to visualize.

### Consumer Flow — Stock Validation (Happy Path + Failure Path)

```mermaid
sequenceDiagram
    participant Kafka as Kafka Broker<br/>(order.placed topic)
    participant Factory as ConcurrentKafkaListener<br/>ContainerFactory
    participant Consumer as InventoryKafkaConsumer<br/>(@KafkaListener)
    participant Service as InventoryService<br/>(@Transactional)
    participant DB as PostgreSQL<br/>(inventory_db)
    participant Producer as InventoryKafkaProducer
    participant KT as KafkaTemplate
    participant KafkaOut as Kafka Broker<br/>(order.validated / order.failed)

    Note over Kafka,Factory: Startup: Factory creates 3 consumer threads<br/>(concurrency=3, matching 3 partitions)

    Kafka->>Factory: Poll messages from partition X

    Factory->>Factory: JsonDeserializer<br/>deserialize bytes → OrderEvent<br/>(using __TypeId__ header)

    Factory->>Consumer: onOrderPlaced(OrderEvent)

    Note over Consumer: Log: Received event from [order.placed]<br/>eventId=... | orderId=... | status=PLACED

    Consumer->>Service: processOrderPlaced(event)

    Note over Service: @Transactional begins

    Service->>Service: Extract productIds from event.items()

    Service->>DB: SELECT * FROM products<br/>WHERE id IN (uuid1, uuid2, ...)
    DB-->>Service: List<Product>

    Service->>Service: Build Map<UUID, Product><br/>for O(1) lookup

    loop For each OrderItem in event
        Service->>Service: Check product exists in map
        Service->>Service: Check product.hasStock(quantity)
    end

    alt All items have sufficient stock
        Note over Service: === HAPPY PATH ===

        loop For each OrderItem
            Service->>Service: product.reserveStock(quantity)<br/>available -= quantity<br/>reserved += quantity
        end

        Note over Service: Log: Stock reserved successfully | orderId=...

        Service->>Service: OrderEvent.create(<br/>orderId, customerId, items,<br/>totalAmount, VALIDATED)

        Service->>Producer: sendOrderValidated(event)

        Producer->>Producer: key = orderId.toString()
        Producer->>Producer: Log: Publishing event to [order.validated]

        Producer->>KT: send("order.validated", key, event)
        KT->>KafkaOut: ProducerRecord

        KafkaOut-->>KT: RecordMetadata (partition, offset)
        KT-->>Producer: CompletableFuture<SendResult>

        Note over Producer: Async callback: SUCCESS published<br/>partition=X | offset=Y

        Note over Service: @Transactional commits<br/>→ Hibernate flushes UPDATE products<br/>(dirty checking)

    else Any item fails validation
        Note over Service: === FAILURE PATH ===

        Service->>Service: Collect failure reasons:<br/>"Product not found: ..." or<br/>"Insufficient stock for '...': available=0, requested=1"

        Note over Service: Log: Stock validation FAILED | reason=...

        Service->>Service: OrderEvent.withReason(<br/>orderId, customerId, items,<br/>totalAmount, FAILED, reason)

        Service->>Producer: sendOrderFailed(event)

        Producer->>Producer: key = orderId.toString()
        Producer->>Producer: Log: Publishing event to [order.failed]

        Producer->>KT: send("order.failed", key, event)
        KT->>KafkaOut: ProducerRecord

        KafkaOut-->>KT: RecordMetadata
        KT-->>Producer: CompletableFuture<SendResult>

        Note over Producer: Async callback: SUCCESS published

        Note over Service: @Transactional commits<br/>(no DB changes — stock NOT reserved)
    end

    Consumer->>Consumer: Log: Finished processing [order.placed]

    Note over Factory: Commit offset for this message<br/>→ won't be re-delivered
```

### Error Handling — Consumer Retry Flow

```mermaid
sequenceDiagram
    participant Kafka as Kafka Broker<br/>(order.placed)
    participant Factory as ListenerContainerFactory
    participant EH as DefaultErrorHandler<br/>(FixedBackOff: 1s, 3 retries)
    participant Consumer as InventoryKafkaConsumer
    participant Service as InventoryService

    Kafka->>Factory: Deliver message

    Factory->>Consumer: onOrderPlaced(event)
    Consumer->>Service: processOrderPlaced(event)
    Service-->>Consumer: throws Exception

    Consumer-->>EH: Exception propagated

    Note over EH: Attempt 1 FAILED<br/>Wait 1000ms...

    EH->>Consumer: Retry — onOrderPlaced(event)
    Consumer->>Service: processOrderPlaced(event)
    Service-->>Consumer: throws Exception

    Note over EH: Attempt 2 FAILED<br/>Wait 1000ms...

    EH->>Consumer: Retry — onOrderPlaced(event)
    Consumer->>Service: processOrderPlaced(event)
    Service-->>Consumer: throws Exception

    Note over EH: Attempt 3 FAILED<br/>Wait 1000ms...

    EH->>Consumer: Retry — onOrderPlaced(event)
    Consumer->>Service: processOrderPlaced(event)
    Service-->>Consumer: throws Exception

    Note over EH: All 3 retries exhausted<br/>Log error + skip message<br/>(Dead Letter Queue in Step 5)

    EH->>Factory: Commit offset → move to next message
```

### End-to-End Flow (Order Service → Inventory Service)

```mermaid
sequenceDiagram
    actor Client
    participant OrderCtrl as OrderController<br/>(:8081)
    participant OrderSvc as OrderService
    participant OrderDB as PostgreSQL<br/>(order_db)
    participant OrderProd as OrderKafkaProducer
    participant Kafka1 as Kafka<br/>[order.placed]
    participant InvConsumer as InventoryKafkaConsumer<br/>(:8082)
    participant InvSvc as InventoryService
    participant InvDB as PostgreSQL<br/>(inventory_db)
    participant InvProd as InventoryKafkaProducer
    participant Kafka2 as Kafka<br/>[order.validated]

    Client->>OrderCtrl: POST /api/orders<br/>{customerId, items[]}

    OrderCtrl->>OrderSvc: createOrder(request)
    OrderSvc->>OrderDB: save(order) → status=PLACED
    OrderSvc->>OrderProd: sendOrderPlaced(event)
    OrderProd->>Kafka1: publish(key=orderId, value=OrderEvent)

    OrderCtrl-->>Client: 201 CREATED {orderId, status: PLACED}

    Note over Kafka1,InvConsumer: Async — consumer polls<br/>independently of HTTP response

    Kafka1->>InvConsumer: deliver OrderEvent

    InvConsumer->>InvSvc: processOrderPlaced(event)
    InvSvc->>InvDB: SELECT products WHERE id IN (...)
    InvSvc->>InvSvc: Validate stock for all items

    alt Stock sufficient
        InvSvc->>InvDB: UPDATE products SET<br/>available -= qty, reserved += qty
        InvSvc->>InvProd: sendOrderValidated(event)
        InvProd->>Kafka2: publish(key=orderId,<br/>value=OrderEvent{status=VALIDATED})
        Note over Kafka2: Next: Payment Service<br/>(Step 4)
    else Stock insufficient
        InvSvc->>InvProd: sendOrderFailed(event)
        InvProd->>Kafka2: publish to [order.failed]<br/>(key=orderId, status=FAILED, reason=...)
        Note over Kafka2: Next: Order Service + Notification<br/>(Step 4)
    end
```

### Component Architecture (Step 3)

```mermaid
graph TB
    subgraph "Order Service (:8081)"
        OP[OrderKafkaProducer]
    end

    subgraph "Kafka Broker"
        T1[order.placed<br/>3 partitions]
        T2[order.validated<br/>3 partitions]
        T3[order.failed<br/>3 partitions]
    end

    subgraph "inventory-service (:8082)"
        subgraph "Config Layer"
            CC[KafkaConsumerConfig<br/>ConsumerFactory +<br/>ListenerContainerFactory]
            PC[KafkaProducerConfig<br/>ProducerFactory +<br/>KafkaTemplate]
            DI[DataInitializer<br/>CommandLineRunner]
        end

        subgraph "Kafka Layer"
            IC[InventoryKafkaConsumer<br/>@KafkaListener]
            IP[InventoryKafkaProducer<br/>@Component]
        end

        subgraph "Service Layer"
            IS[InventoryService<br/>@Transactional]
        end

        subgraph "Domain Layer"
            PR[Product<br/>@Entity]
            REPO[ProductRepository<br/>JpaRepository]
        end
    end

    subgraph "Infrastructure"
        PG[(PostgreSQL<br/>inventory_db)]
    end

    OP -->|"publish"| T1
    T1 -->|"consume<br/>@KafkaListener"| IC
    CC -.->|configures| IC
    IC --> IS
    IS --> REPO
    REPO --> PG
    DI -->|"seed data"| REPO
    IS --> IP
    PC -.->|configures| IP
    IP -->|"stock OK"| T2
    IP -->|"stock FAIL"| T3

    style T1 fill:#e74c3c,color:#fff
    style T2 fill:#27ae60,color:#fff
    style T3 fill:#e67e22,color:#fff
    style PG fill:#336791,color:#fff
```

### Kafka Consumer Configuration Detail

```mermaid
graph LR
    subgraph "application.yml"
        YML["spring.kafka.consumer:<br/>  group-id: inventory-service-group<br/>  auto-offset-reset: earliest<br/>  trusted.packages: dnc.cuong.common.event"]
    end

    subgraph "KafkaConsumerConfig.java"
        CF[ConsumerFactory<br/>String, OrderEvent]
        LF[ConcurrentKafkaListener<br/>ContainerFactory]
    end

    subgraph "Deserialization"
        KD[StringDeserializer<br/>for keys]
        VD[JsonDeserializer<br/>for OrderEvent values]
    end

    subgraph "Consumer Properties"
        P1["group-id → partition assignment"]
        P2["earliest → read from beginning"]
        P3["trusted.packages → security"]
        P4["concurrency=3 → 1 thread/partition"]
    end

    subgraph "Error Handling"
        EH["DefaultErrorHandler<br/>FixedBackOff(1000ms, 3 retries)<br/>→ retry then skip"]
    end

    YML -->|KafkaProperties| CF
    CF --> LF
    CF --> KD
    CF --> VD
    LF --> P1
    LF --> P4
    CF --> P2
    CF --> P3
    LF --> EH
```

## Key Concepts in Step 3

| Concept | Where | Why |
|---------|-------|-----|
| **@KafkaListener** | `InventoryKafkaConsumer` | Declarative message consumption — Spring manages poll loop |
| **Consumer Group** | `inventory-service-group` | Partition assignment, rebalancing, parallel processing |
| **ConsumerFactory** | `KafkaConsumerConfig` | Creates consumer instances with correct deserializer config |
| **ConcurrentKafkaListenerContainerFactory** | `KafkaConsumerConfig` | Manages consumer threads, concurrency, error handling |
| **setConcurrency(3)** | `KafkaConsumerConfig` | Match partition count — 1 thread per partition for max throughput |
| **JsonDeserializer** | `KafkaConsumerConfig` | Deserialize JSON bytes → OrderEvent using __TypeId__ header |
| **TRUSTED_PACKAGES** | `KafkaConsumerConfig` | Security — only allow deserialization of known event classes |
| **DefaultErrorHandler** | `KafkaConsumerConfig` | Retry 3 times with 1s delay, then skip (DLQ in Step 5) |
| **auto-offset-reset: earliest** | `application.yml` | New consumer groups start from beginning (don't miss messages) |
| **Batch lookup** | `findAllByIdIn()` | Single SQL query instead of N+1 for stock validation |
| **Dirty checking** | `InventoryService` | JPA auto-detects entity changes — no explicit save() needed |
| **reserveStock()** | `Product` domain method | Encapsulates business rule in entity (DDD pattern) |
| **Thin consumer** | `InventoryKafkaConsumer` | Only receives + delegates — business logic in service layer |
