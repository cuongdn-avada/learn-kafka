# Step 8: Testing — Sequence Diagrams

## 1. Testing Strategy Overview

```mermaid
graph TB
    subgraph "Test Pyramid"
        UT["Unit Tests (Fast, Isolated)"]
        CT["Controller Tests (Web Layer)"]
        IT["Integration Tests (Full Stack)"]
    end

    subgraph "Unit Tests — @ExtendWith(MockitoExtension)"
        OS["OrderServiceTest (12 tests)"]
        IS["InventoryServiceTest (8 tests)"]
        PS["PaymentServiceTest (7 tests)"]
        NS["NotificationServiceTest (8 tests)"]
        PT["ProductTest (13 tests)"]
        MT["OrderEventMapperTest (6 tests)"]
    end

    subgraph "Controller Tests — @WebMvcTest"
        OCT["OrderControllerTest (5 tests)"]
    end

    UT --> OS
    UT --> IS
    UT --> PS
    UT --> NS
    UT --> PT
    UT --> MT
    CT --> OCT
```

## 2. Unit Test Flow — Service Layer (Mockito)

```mermaid
sequenceDiagram
    participant Test as JUnit Test
    participant Service as Service Under Test
    participant MockRepo as Mock Repository
    participant MockProducer as Mock KafkaProducer
    participant MockProcessed as Mock ProcessedEventRepo

    Note over Test,MockProcessed: @ExtendWith(MockitoExtension) — No Spring context needed

    rect rgb(200, 230, 200)
        Note over Test: Happy Path Test
        Test->>MockProcessed: when(existsById()).thenReturn(false)
        Test->>MockRepo: when(findById()).thenReturn(entity)
        Test->>Service: call business method(event)
        Service->>MockProcessed: existsById(eventId)
        MockProcessed-->>Service: false (not duplicate)
        Service->>MockRepo: findById(orderId)
        MockRepo-->>Service: Order entity
        Service->>Service: Update status
        Service->>MockProcessed: save(ProcessedEvent)
        Service->>MockProducer: publish event
        Test->>MockProducer: verify(sendXxx).called()
    end

    rect rgb(255, 220, 220)
        Note over Test: Idempotency Test
        Test->>MockProcessed: when(existsById()).thenReturn(true)
        Test->>Service: call business method(event)
        Service->>MockProcessed: existsById(eventId)
        MockProcessed-->>Service: true (DUPLICATE!)
        Service-->>Test: return (skipped)
        Test->>MockRepo: verify(findById).never()
        Test->>MockProducer: verify(send).never()
    end
```

## 3. Controller Test Flow — MockMvc

```mermaid
sequenceDiagram
    participant Test as JUnit Test
    participant MockMvc as MockMvc
    participant Controller as OrderController
    participant MockService as @MockBean OrderService
    participant Handler as GlobalExceptionHandler

    Note over Test,Handler: @WebMvcTest — Only web layer loaded

    rect rgb(200, 230, 200)
        Note over Test: POST /api/orders → 201 CREATED
        Test->>MockService: when(createOrder()).thenReturn(order)
        Test->>MockMvc: perform(post("/api/orders").content(json))
        MockMvc->>Controller: createOrder(request)
        Controller->>MockService: createOrder(request)
        MockService-->>Controller: Order entity
        Controller->>Controller: OrderResponse.from(order)
        Controller-->>MockMvc: ResponseEntity 201
        MockMvc-->>Test: status=201, body=OrderResponse
        Test->>Test: assert status, fields
    end

    rect rgb(255, 220, 220)
        Note over Test: GET /api/orders/{id} → 404 NOT FOUND
        Test->>MockService: when(getOrder()).thenThrow(OrderNotFoundException)
        Test->>MockMvc: perform(get("/api/orders/{id}"))
        MockMvc->>Controller: getOrder(id)
        Controller->>MockService: getOrder(id)
        MockService-->>Controller: throw OrderNotFoundException
        Controller-->>Handler: exception propagated
        Handler->>Handler: ProblemDetail.forStatus(404)
        Handler-->>MockMvc: ProblemDetail response
        MockMvc-->>Test: status=404, title="Order Not Found"
    end
```

## 4. Domain Logic Test Flow — Pure Unit Test

```mermaid
sequenceDiagram
    participant Test as JUnit Test
    participant Product as Product Entity

    Note over Test,Product: No mocks, no Spring — pure Java unit test

    rect rgb(200, 230, 200)
        Note over Test: reserveStock — Happy Path
        Test->>Product: new Product(available=50, reserved=0)
        Test->>Product: reserveStock(10)
        Product->>Product: available=40, reserved=10
        Test->>Test: assertEquals(40, getAvailableQuantity())
        Test->>Test: assertEquals(10, getReservedQuantity())
    end

    rect rgb(255, 220, 220)
        Note over Test: reserveStock — Insufficient Stock
        Test->>Product: new Product(available=5, reserved=0)
        Test->>Product: reserveStock(10)
        Product-->>Test: throw IllegalStateException
        Test->>Test: assertThrows(IllegalStateException)
        Test->>Test: assertEquals(5, getAvailableQuantity()) // unchanged
    end

    rect rgb(200, 220, 255)
        Note over Test: Saga Compensation — reserve + release
        Test->>Product: new Product(available=100, reserved=0)
        Test->>Product: reserveStock(30)
        Product->>Product: available=70, reserved=30
        Test->>Product: releaseStock(30)
        Product->>Product: available=100, reserved=0 // restored!
    end
```

## 5. Avro Mapper Test Flow — Round-trip Verification

```mermaid
sequenceDiagram
    participant Test as JUnit Test
    participant Mapper as OrderEventMapper
    participant Avro as OrderEventAvro

    Note over Test,Avro: Verify data integrity across serialization boundary

    rect rgb(200, 230, 200)
        Note over Test: Round-trip: OrderEvent → Avro → OrderEvent
        Test->>Test: Create original OrderEvent
        Test->>Mapper: toAvro(event, "payment-service")
        Mapper->>Mapper: UUID→String, BigDecimal→String
        Mapper->>Mapper: OrderStatus→OrderStatusAvro
        Mapper-->>Test: OrderEventAvro
        Test->>Mapper: fromAvro(avroEvent)
        Mapper->>Mapper: String→UUID, String→BigDecimal
        Mapper->>Mapper: OrderStatusAvro→OrderStatus
        Mapper-->>Test: OrderEvent (restored)
        Test->>Test: assertEquals(original, restored) ✓
    end
```

## 6. Test Coverage Summary

```mermaid
graph LR
    subgraph "common (6 tests)"
        M1["OrderEventMapperTest"]
    end

    subgraph "order-service (17 tests)"
        M2["OrderServiceTest — 12"]
        M3["OrderControllerTest — 5"]
    end

    subgraph "inventory-service (21 tests)"
        M4["InventoryServiceTest — 8"]
        M5["ProductTest — 13"]
    end

    subgraph "payment-service (7 tests)"
        M6["PaymentServiceTest — 7"]
    end

    subgraph "notification-service (8 tests)"
        M7["NotificationServiceTest — 8"]
    end

    style M1 fill:#90EE90
    style M2 fill:#90EE90
    style M3 fill:#87CEEB
    style M4 fill:#90EE90
    style M5 fill:#FFFACD
    style M6 fill:#90EE90
    style M7 fill:#90EE90
```

| Color | Test Type | Total |
|-------|-----------|-------|
| Green | Service Unit Tests (Mockito) | 35 |
| Blue | Controller Tests (MockMvc) | 5 |
| Yellow | Domain Logic Tests (Pure) | 13 |
| **Total** | | **59** |
