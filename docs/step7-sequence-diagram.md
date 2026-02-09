# Step 7: Schema Evolution & Contract Management — Diagrams

## Vấn đề: Schema Drift & Breaking Changes

Khi các service evolve độc lập, schema Kafka message có thể bị "drift":
- Producer thêm field mới → consumer không hiểu
- Producer xóa field → consumer crash vì expect field đó
- Producer đổi type (string → int) → deserialization fail
- Không có formal contract → mỗi team assume khác nhau

**Hậu quả nếu không có Schema Management:**
- Consumer crash khi gặp message format mới
- Data corruption do type mismatch
- Rollback khó vì old/new messages trộn lẫn trong topic
- Không biết service nào dùng schema version nào

## Before: JSON Serialization (No Schema Enforcement)

```mermaid
sequenceDiagram
    participant Producer as Order Service (Producer)
    participant Kafka as Kafka Broker
    participant Consumer as Inventory Service (Consumer)

    Note over Producer,Consumer: === JSON: No schema validation ===

    Producer->>Producer: Serialize OrderEvent → JSON string
    Note over Producer: {"eventId":"abc","orderId":"123",...}
    Producer->>Kafka: Send JSON bytes
    Kafka->>Consumer: Deliver JSON bytes
    Consumer->>Consumer: Deserialize JSON → OrderEvent
    Note over Consumer: Relies on __TypeId__ header<br/>+ trusted packages config

    Note over Producer,Consumer: ⚠️ Nếu Producer thêm field mới hoặc đổi type...

    Producer->>Producer: Thêm field "priority": 1 (int)
    Producer->>Kafka: Send JSON with new field
    Kafka->>Consumer: Deliver JSON with unknown field
    Consumer->>Consumer: JsonDeserializer ignores unknown field
    Note over Consumer: ✓ May work... nhưng không có guarantee!<br/>Không ai validate schema trước khi publish
```

## After: Avro + Schema Registry (Enforced Contract)

```mermaid
sequenceDiagram
    participant Producer as Order Service (Producer)
    participant SR as Schema Registry
    participant Kafka as Kafka Broker
    participant Consumer as Inventory Service (Consumer)

    Note over Producer,Consumer: === FIRST MESSAGE: Schema auto-registered ===

    Producer->>Producer: Convert OrderEvent → OrderEventAvro
    Producer->>SR: Register schema (order.placed-value)
    SR->>SR: Validate compatibility (BACKWARD default)
    SR-->>Producer: Schema ID = 1

    rect rgb(220, 245, 220)
        Note over Producer,Kafka: Avro binary = [magic byte][schema ID: 4 bytes][data]
        Producer->>Kafka: Send Avro bytes (schema ID embedded)
    end

    Kafka->>Consumer: Deliver Avro bytes
    Consumer->>SR: Fetch schema by ID = 1
    SR-->>Consumer: Return OrderEventAvro schema
    Consumer->>Consumer: Deserialize Avro → OrderEventAvro
    Consumer->>Consumer: Convert OrderEventAvro → OrderEvent
    Note over Consumer: Type-safe SpecificRecord (generated class)

    Note over Producer,Consumer: === SCHEMA EVOLUTION: Add optional field ===

    Producer->>Producer: Schema V2: add "source" field (default: null)
    Producer->>SR: Register schema V2
    SR->>SR: Check BACKWARD compatibility ✓
    Note over SR: V2 has default for new field → old consumers OK
    SR-->>Producer: Schema ID = 2

    Producer->>Kafka: Send Avro bytes (schema ID = 2)
    Kafka->>Consumer: Deliver Avro bytes (schema ID = 2)
    Consumer->>SR: Fetch schema by ID = 2
    Note over Consumer: Reader uses own schema (V1 or V2)<br/>New field has default → safely ignored or read
    Consumer->>Consumer: Deserialize OK ✓

    Note over Producer,Consumer: === BREAKING CHANGE: Rejected! ===

    Producer->>Producer: Try remove "orderId" field (no default)
    Producer->>SR: Register schema V3
    SR->>SR: Check BACKWARD compatibility ✗
    SR-->>Producer: 409 Conflict — Incompatible schema!
    Note over SR: ❌ Removing required field breaks old consumers
```

## Schema Registry — Compatibility Modes

```mermaid
flowchart TD
    A[Schema Change Submitted] --> B{Compatibility Mode?}

    B -->|BACKWARD<br/>default| C{New schema can<br/>read OLD data?}
    C -->|Yes| C1[✓ Allowed]
    C -->|No| C2[✗ Rejected]

    B -->|FORWARD| D{OLD schema can<br/>read NEW data?}
    D -->|Yes| D1[✓ Allowed]
    D -->|No| D2[✗ Rejected]

    B -->|FULL| E{Both BACKWARD<br/>AND FORWARD?}
    E -->|Yes| E1[✓ Allowed]
    E -->|No| E2[✗ Rejected]

    B -->|NONE| F[✓ Always Allowed]

    style C1 fill:#d4edda
    style D1 fill:#d4edda
    style E1 fill:#d4edda
    style F fill:#fff3cd
    style C2 fill:#f8d7da
    style D2 fill:#f8d7da
    style E2 fill:#f8d7da
```

## BACKWARD Compatible Changes (Allowed)

```
✓ ADD optional field với default value
  → Old consumer ignore field mới, dùng default

✓ ADD new enum symbol ở cuối
  → Old consumer có thể nhận value mới (cần handle unknown)

✗ REMOVE field đang tồn tại (không có default)
  → Old consumer expect field, crash khi không tìm thấy

✗ CHANGE field type (string → int)
  → Deserialization fail

✗ RENAME field
  → Avro dùng field name để match, rename = remove + add
```

## Avro Serialization Flow

```mermaid
sequenceDiagram
    participant App as Application Code
    participant Mapper as OrderEventMapper
    participant Ser as KafkaAvroSerializer
    participant SR as Schema Registry
    participant Kafka as Kafka Broker

    Note over App,Kafka: === PRODUCE ===

    App->>App: Create OrderEvent (Java record)
    App->>Mapper: toAvro(event, "order-service")
    Mapper-->>App: OrderEventAvro (Avro SpecificRecord)

    App->>Ser: serialize(topic, avroEvent)
    Ser->>SR: Register/get schema ID for subject "order.placed-value"
    SR-->>Ser: Schema ID = 1
    Ser->>Ser: Encode: [0x00][ID: 4 bytes][Avro binary data]
    Ser-->>Kafka: byte[] (compact binary, ~30-50% smaller than JSON)

    Note over App,Kafka: === CONSUME ===

    participant Deser as KafkaAvroDeserializer
    participant Mapper2 as OrderEventMapper

    Kafka->>Deser: byte[] from topic
    Deser->>Deser: Read magic byte + schema ID from first 5 bytes
    Deser->>SR: Fetch writer schema by ID
    SR-->>Deser: OrderEventAvro schema
    Deser->>Deser: Deserialize Avro binary → OrderEventAvro
    Note over Deser: specific.avro.reader=true → SpecificRecord
    Deser-->>App: OrderEventAvro

    App->>Mapper2: fromAvro(avroEvent)
    Mapper2-->>App: OrderEvent (Java record)
    App->>App: Process business logic
```

## OrderEventMapper — Bridge giữa Business và Kafka Layer

```mermaid
flowchart LR
    subgraph "Business Layer"
        OE[OrderEvent<br/>Java record]
        OS[OrderService]
    end

    subgraph "Kafka Layer"
        OEA[OrderEventAvro<br/>Avro SpecificRecord]
        KP[KafkaProducer]
        KC[KafkaConsumer]
    end

    subgraph "Infrastructure"
        SR[Schema Registry]
        KB[Kafka Broker]
    end

    OS -->|create| OE
    OE -->|toAvro()| OEA
    OEA -->|serialize| KP
    KP -->|publish| KB
    KP -.->|register schema| SR

    KB -->|deliver| KC
    KC -.->|fetch schema| SR
    KC -->|deserialize| OEA
    OEA -->|fromAvro()| OE
    OE -->|delegate| OS

    style OE fill:#d4edda
    style OEA fill:#cce5ff
    style SR fill:#fff3cd
```

## Schema Subject Naming Strategy

```
Topic Name Strategy (default):
  subject = <topic>-value

┌──────────────────┬──────────────────────────┐
│ Topic            │ Schema Subject           │
├──────────────────┼──────────────────────────┤
│ order.placed     │ order.placed-value       │
│ order.validated  │ order.validated-value    │
│ order.paid       │ order.paid-value         │
│ order.completed  │ order.completed-value    │
│ order.failed     │ order.failed-value       │
│ payment.failed   │ payment.failed-value     │
└──────────────────┴──────────────────────────┘

Tất cả topics dùng cùng schema (OrderEventAvro),
nhưng Schema Registry track version riêng per subject.
→ Có thể set compatibility mode khác nhau per topic.
```

## Avro Schema Definition

```json
{
  "type": "record",
  "name": "OrderEventAvro",
  "namespace": "dnc.cuong.common.avro",
  "fields": [
    {"name": "eventId",       "type": "string"},
    {"name": "orderId",       "type": "string"},
    {"name": "customerId",    "type": "string"},
    {"name": "items",         "type": {"type": "array", "items": "OrderItemAvro"}},
    {"name": "totalAmount",   "type": "string"},
    {"name": "status",        "type": "OrderStatusAvro"},
    {"name": "reason",        "type": ["null", "string"],  "default": null},
    {"name": "createdAt",     "type": "string"},
    {"name": "schemaVersion", "type": "int",               "default": 1},
    {"name": "source",        "type": ["null", "string"],  "default": null}
  ]
}

Schema evolution: schemaVersion và source có default values
→ BACKWARD compatible: consumer dùng schema cũ vẫn đọc được
→ Nếu consumer chưa biết field "source" → Avro tự skip
```

## End-to-End Flow với Avro + Schema Registry

```mermaid
sequenceDiagram
    participant Client
    participant OrderSvc as Order Service
    participant SR as Schema Registry
    participant Kafka as Kafka
    participant InvSvc as Inventory Service
    participant PaySvc as Payment Service
    participant NotifSvc as Notification Service

    Client->>OrderSvc: POST /api/orders (JSON via REST)

    Note over OrderSvc: REST layer dùng JSON (Jackson)<br/>Kafka layer dùng Avro (Schema Registry)

    OrderSvc->>OrderSvc: OrderEvent → OrderEventAvro (mapper)
    OrderSvc->>SR: Register schema (order.placed-value)
    OrderSvc->>Kafka: publish order.placed (Avro binary)

    Kafka->>InvSvc: deliver order.placed (Avro binary)
    InvSvc->>SR: Fetch schema
    InvSvc->>InvSvc: OrderEventAvro → OrderEvent (mapper)
    InvSvc->>InvSvc: Reserve stock

    InvSvc->>InvSvc: OrderEvent → OrderEventAvro (mapper)
    InvSvc->>Kafka: publish order.validated (Avro binary)

    Kafka->>PaySvc: deliver order.validated (Avro binary)
    PaySvc->>SR: Fetch schema
    PaySvc->>PaySvc: OrderEventAvro → OrderEvent (mapper)
    PaySvc->>PaySvc: Process payment

    PaySvc->>PaySvc: OrderEvent → OrderEventAvro (mapper)
    PaySvc->>Kafka: publish order.paid (Avro binary)

    Kafka->>OrderSvc: deliver order.paid (Avro binary)
    OrderSvc->>OrderSvc: OrderEventAvro → OrderEvent (mapper)
    OrderSvc->>OrderSvc: Update order → COMPLETED

    OrderSvc->>Kafka: publish order.completed (Avro binary)
    Kafka->>NotifSvc: deliver order.completed
    NotifSvc->>NotifSvc: Send notification ✉️
```

## Infrastructure Overview

```
┌─────────────────────────────────────────────────┐
│                  Docker Compose                    │
├─────────────────────────────────────────────────┤
│                                                    │
│  ┌──────────────┐  ┌────────────────────────┐    │
│  │ Kafka 4.0.0   │  │ Schema Registry 7.7.1  │    │
│  │ (KRaft mode)  │  │ Port: 8085 (host)      │    │
│  │ Port: 9094    │  │ Stores schemas in      │    │
│  │               │←─│ _schemas topic         │    │
│  └──────────────┘  └────────────────────────┘    │
│         ↑                    ↑                     │
│         │                    │                     │
│  ┌──────────────┐            │                     │
│  │ Kafka UI      │  ← reads schemas               │
│  │ Port: 8088    │                                 │
│  └──────────────┘                                 │
│                                                    │
│  ┌──────────────┐                                 │
│  │ PostgreSQL 16 │  order_db, inventory_db,       │
│  │ Port: 5432    │  payment_db                    │
│  └──────────────┘                                 │
└─────────────────────────────────────────────────┘

Spring Boot Services (run on host):
  Order Service      :8081 ──→ Kafka + Schema Registry
  Inventory Service  :8082 ──→ Kafka + Schema Registry
  Payment Service    :8083 ──→ Kafka + Schema Registry
  Notification Service :8084 ──→ Kafka + Schema Registry
```

## Key Concepts Learned

| Concept                         | Description                                                    |
|---------------------------------|----------------------------------------------------------------|
| Schema Registry                 | Centralized service quản lý và validate schema versions        |
| Avro Serialization              | Binary format, compact, schema embedded by ID in message       |
| SpecificRecord                  | Avro generated Java class — type-safe, có getter/setter        |
| GenericRecord                   | Alternative — access field by name (string), less type-safe    |
| BACKWARD Compatibility          | New schema can read old data (default mode)                    |
| FORWARD Compatibility           | Old schema can read new data                                   |
| FULL Compatibility              | Both BACKWARD and FORWARD                                      |
| Schema Subject                  | `<topic>-value` — tracks versions per topic                    |
| TopicNameStrategy               | Default naming: subject = topic name + "-key"/"-value"         |
| OrderEventMapper                | Bridge between business layer (record) and Kafka layer (Avro)  |
| Schema Evolution                | Add optional fields with defaults → safe, enforced by registry |
| Producer Idempotence + Avro     | `enable.idempotence=true` + Avro = exactly-once + schema safe  |

## Useful Commands — Schema Registry

```bash
# List all registered subjects
curl http://localhost:8085/subjects

# Get versions for a subject
curl http://localhost:8085/subjects/order.placed-value/versions

# Get latest schema for a subject
curl http://localhost:8085/subjects/order.placed-value/versions/latest

# Check compatibility of a new schema
curl -X POST http://localhost:8085/compatibility/subjects/order.placed-value/versions/latest \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d '{"schema": "{...}"}'

# Get global compatibility level
curl http://localhost:8085/config

# Set compatibility level for a subject
curl -X PUT http://localhost:8085/config/order.placed-value \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d '{"compatibility": "FULL"}'
```
