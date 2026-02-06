# Step 5: Error Handling & Dead Letter Queue — Diagrams

## DLQ Retry + DLT Flow

```mermaid
sequenceDiagram
    participant Kafka as Kafka Topic
    participant Consumer as Consumer (@KafkaListener)
    participant ErrorHandler as DefaultErrorHandler
    participant DLT as Dead Letter Topic (.DLT)
    participant DltConsumer as DLT Consumer

    Kafka->>Consumer: Deliver message
    Consumer->>Consumer: Process → throws Exception

    rect rgb(255, 235, 235)
        Note over ErrorHandler: ExponentialBackOff Retries
        ErrorHandler->>Consumer: Retry 1 (after 1s)
        Consumer->>Consumer: Process → throws Exception
        ErrorHandler->>Consumer: Retry 2 (after 2s)
        Consumer->>Consumer: Process → throws Exception
        ErrorHandler->>Consumer: Retry 3 (after 4s)
        Consumer->>Consumer: Process → throws Exception
    end

    ErrorHandler->>DLT: DeadLetterPublishingRecoverer → publish to <topic>.DLT
    DLT->>DltConsumer: Consume failed message
    DltConsumer->>DltConsumer: Log ERROR [DLT] for monitoring
```

## Exponential Backoff Visualization

```
Attempt 1: Process → FAIL
            ├── Wait 1s (initialInterval)
Attempt 2: Process → FAIL
            ├── Wait 2s (1s × 2.0 multiplier)
Attempt 3: Process → FAIL
            ├── Wait 4s (2s × 2.0 multiplier)
Attempt 4: Process → FAIL
            └── All retries exhausted → Publish to .DLT topic

Max interval cap: 10s (prevents unbounded growth)
Max elapsed time: 30s (total retry window)
```

## End-to-End Error Handling Flow

```mermaid
flowchart TD
    A[Message arrives at consumer] --> B{Process successfully?}
    B -->|Yes| C[Commit offset ✓]
    B -->|No| D[DefaultErrorHandler catches exception]
    D --> E{Retries remaining?}
    E -->|Yes| F[Wait exponential delay]
    F --> G[Retry processing]
    G --> B
    E -->|No| H[DeadLetterPublishingRecoverer]
    H --> I[Publish to topic.DLT]
    I --> J[Commit original offset ✓]
    I --> K[DLT Consumer receives message]
    K --> L[Log ERROR for monitoring/alerting]
```

## DLT Topic Mapping

| Source Topic       | DLT Topic               | DLT Consumer Service(s)          |
|--------------------|--------------------------|----------------------------------|
| `order.placed`     | `order.placed.DLT`      | Inventory Service                |
| `order.validated`  | `order.validated.DLT`   | Payment Service                  |
| `order.paid`       | `order.paid.DLT`        | Order Service                    |
| `order.completed`  | `order.completed.DLT`   | Notification Service             |
| `order.failed`     | `order.failed.DLT`      | Order Service, Notification      |
| `payment.failed`   | `payment.failed.DLT`    | Inventory, Order, Notification   |
