# JSON vs Avro + Schema Registry — So sánh chi tiết

## Tại sao cần thay đổi serialization?

Ở Step 1-6, hệ thống dùng **JSON (Jackson)** để serialize Kafka messages. JSON hoạt động tốt cho prototype và learning, nhưng khi hệ thống scale lên production với nhiều team, nhiều service, JSON bộc lộ nhiều vấn đề nghiêm trọng.

---

## 1. Schema Enforcement — Ai kiểm soát format message?

### JSON: Không ai kiểm soát

```
Producer (Order Service) gửi:
  {"eventId": "abc", "orderId": "123", "status": "PLACED", ...}

→ Không có gì ngăn developer gửi:
  {"event_id": "abc", "order_id": "123", "state": "PLACED"}

→ Hoặc quên field:
  {"eventId": "abc", "status": "PLACED"}  ← thiếu orderId!

→ Hoặc sai type:
  {"eventId": "abc", "orderId": 123, "status": "PLACED"}  ← orderId là int thay vì string
```

**Vấn đề:** JSON không có formal schema. Producer có thể gửi BẤT KỲ format nào. Consumer chỉ biết lỗi khi deserialize — lúc đó message đã nằm trong Kafka, không rollback được.

### Avro + Schema Registry: Schema được validate TRƯỚC khi publish

```
Producer gửi message → KafkaAvroSerializer → Schema Registry
                                                  ↓
                                         Validate schema ✓
                                         Register version
                                         Return schema ID
                                                  ↓
                                         Serialize → Kafka

Nếu schema không hợp lệ hoặc không compatible:
  → Schema Registry trả về 409 Conflict
  → Message KHÔNG được publish
  → Producer nhận exception NGAY LẬP TỨC
```

**Lợi ích:** Lỗi schema bị bắt ở PRODUCE time, không phải CONSUME time. Consumer luôn nhận message đúng format.

---

## 2. Schema Evolution — Thêm/xóa field an toàn

### JSON: Không có guarantee

```
Scenario: Team A thêm field "priority" vào OrderEvent

Trước đó:
  {"eventId": "abc", "orderId": "123", "status": "PLACED"}

Sau khi đổi:
  {"eventId": "abc", "orderId": "123", "status": "PLACED", "priority": "HIGH"}

Câu hỏi:
  → Consumer cũ (chưa update code) có crash không?
  → Phụ thuộc vào config Jackson:
    - @JsonIgnoreProperties(ignoreUnknown = true) → OK, skip field lạ
    - Không có annotation → CRASH! UnrecognizedPropertyException

  → Consumer mới đọc message cũ (không có "priority") thì sao?
    - Nếu priority là primitive (int) → default 0
    - Nếu priority là String → null → có thể NullPointerException

→ KHÔNG AI BIẾT trước khi deploy. Chỉ biết khi consumer crash ở production.
```

### Avro + Schema Registry: Evolution rules được enforce

```
Schema Registry có 4 compatibility modes:

┌──────────────┬──────────────────────────────────────────────┐
│ Mode         │ Rule                                          │
├──────────────┼──────────────────────────────────────────────┤
│ BACKWARD     │ New schema đọc được old data                  │
│ (default)    │ → Thêm field phải có default value            │
│              │ → Xóa field KHÔNG cần default                 │
├──────────────┼──────────────────────────────────────────────┤
│ FORWARD      │ Old schema đọc được new data                  │
│              │ → Xóa field phải có default value             │
│              │ → Thêm field KHÔNG cần default                │
├──────────────┼──────────────────────────────────────────────┤
│ FULL         │ Cả BACKWARD + FORWARD                         │
│              │ → Thêm/xóa field ĐỀU phải có default         │
├──────────────┼──────────────────────────────────────────────┤
│ NONE         │ Không check gì — nguy hiểm, không khuyến khích│
└──────────────┴──────────────────────────────────────────────┘

Ví dụ thêm field "source" (BACKWARD compatible):
  Schema V1: {eventId, orderId, ..., createdAt}
  Schema V2: {eventId, orderId, ..., createdAt, source: ["null","string"] default null}
                                                  ↑
                                         Có default value → OK ✓

  → Consumer dùng V1 đọc message V2 → skip field "source" → không crash
  → Consumer dùng V2 đọc message V1 → "source" = null (default) → không crash

Ví dụ xóa field "orderId" (BACKWARD INCOMPATIBLE):
  Schema V3: {eventId, ..., createdAt}  ← thiếu orderId

  → Schema Registry REJECT: 409 Conflict
  → "The new schema is not backward compatible"
  → Message KHÔNG THỂ publish với schema mới
  → Developer PHẢI sửa trước khi deploy
```

**Lợi ích:** Breaking changes bị chặn ở CI/CD hoặc runtime. Không bao giờ deploy schema incompatible lên production.

---

## 3. Message Size — Bandwidth và Storage

### JSON: Verbose, lặp field names

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "customerId": "8a9e6679-7425-40de-944b-e07fc1f90ae8",
  "items": [
    {
      "productId": "9b9e6679-7425-40de-944b-e07fc1f90ae9",
      "productName": "MacBook Pro",
      "quantity": 1,
      "price": 2499.99
    }
  ],
  "totalAmount": 2499.99,
  "status": "PLACED",
  "reason": null,
  "createdAt": "2024-01-15T10:30:00Z"
}
```

**Size: ~450 bytes** (field names chiếm ~40% message size)

### Avro: Compact binary, không lặp field names

```
Avro binary format:
[0x00]                          ← magic byte (1 byte)
[00 00 00 01]                   ← schema ID (4 bytes)
[binary data without field names] ← chỉ chứa VALUES, không chứa keys

→ Field names được lưu trong Schema Registry (1 lần)
→ Mỗi message chỉ chứa giá trị, theo đúng thứ tự trong schema
```

**Size: ~250-300 bytes** (nhỏ hơn ~30-50% so với JSON)

### Impact khi scale

```
Giả sử: 10,000 messages/second, mỗi message 450 bytes (JSON) vs 270 bytes (Avro)

JSON:  10,000 × 450 = 4.5 MB/s = 388 GB/day
Avro:  10,000 × 270 = 2.7 MB/s = 233 GB/day

Tiết kiệm: 155 GB/day = 4.6 TB/month

→ Ít bandwidth hơn giữa producer ↔ broker ↔ consumer
→ Ít disk storage trên Kafka broker
→ Replication nhanh hơn (ít data hơn giữa brokers)
→ Consumer lag recover nhanh hơn (read ít data hơn)
```

---

## 4. Serialization/Deserialization Performance

### JSON: Parse text → Object (chậm)

```
JSON parsing flow:
  byte[] → String (UTF-8 decode)
       → Tokenize (find {, }, :, ",")
       → Parse tokens → JsonNode tree
       → Map fields by NAME (string comparison)
       → Convert types (string → UUID, string → BigDecimal, ...)
       → Construct Java object

→ String comparison cho mỗi field name
→ Type conversion từ text
→ Memory allocation cho intermediate String objects
```

### Avro: Binary → Object (nhanh)

```
Avro parsing flow:
  byte[] → Read schema ID (first 5 bytes)
       → Fetch schema from local cache (Schema Registry client cache)
       → Read binary data by POSITION (không cần field name matching)
       → Direct type mapping (int → int, long → long, ...)
       → Construct Java object

→ Không có string comparison
→ Không có text → type conversion
→ Ít memory allocation
→ Schema cached locally (không call Registry mỗi lần)
```

### Benchmark (typical numbers)

```
Operation              JSON (Jackson)    Avro (SpecificRecord)
─────────────────────────────────────────────────────────────
Serialize (1 event)    ~2-5 μs           ~0.5-1 μs
Deserialize (1 event)  ~3-8 μs           ~0.5-2 μs
Throughput             ~200K msg/s        ~500K-1M msg/s

→ Avro nhanh hơn 2-5x cho serialization
→ Quan trọng khi throughput cao (>10K msg/s)
```

---

## 5. Type Safety — Compile-time vs Runtime errors

### JSON: Runtime errors

```java
// JSON consumer — lỗi chỉ phát hiện khi runtime
@KafkaListener(topics = "order.placed")
public void onOrderPlaced(OrderEvent event) {
    // Nếu producer gửi sai format → exception ở đây
    // Nếu field bị null unexpected → NullPointerException ở đây
    // Nếu type mismatch → ClassCastException ở đây
    UUID orderId = event.orderId(); // có thể null nếu producer quên set
}
```

### Avro: Compile-time safety (SpecificRecord)

```java
// Avro generated class — compiler check tại build time
OrderEventAvro avro = OrderEventAvro.newBuilder()
    .setEventId("abc")
    .setOrderId("123")
    // .setStatus(...)  ← nếu quên set required field → BUILD FAIL
    .build();

// Avro schema guarantee:
// - Required fields PHẢI có value (build fail nếu thiếu)
// - Nullable fields được declare explicitly: ["null", "string"]
// - Enum values được validate: chỉ accept PLACED, VALIDATED, PAID, ...
// - Type mismatch → compile error (int ≠ string)
```

---

## 6. Contract Management — Ai sở hữu schema?

### JSON: Implicit contract (ngầm hiểu)

```
Team A (Order Service):
  "Tôi gửi JSON có field eventId, orderId, status..."
  → Viết trong Confluence? Slack message? README?
  → Ai update doc khi đổi schema?
  → Làm sao biết consumer nào đang dùng schema version nào?

Team B (Inventory Service):
  "Tôi expect JSON có field eventId, orderId, items..."
  → Copy-paste OrderEvent class từ Team A
  → Team A đổi class → Team B không biết → consumer crash

Vấn đề:
  → Không có single source of truth
  → Documentation outdated
  → Breaking changes phát hiện ở production
  → Rollback khó vì old + new messages trộn lẫn trong topic
```

### Avro + Schema Registry: Explicit contract (tường minh)

```
Schema Registry = Single Source of Truth:
  → Schema file (.avsc) trong source code → version controlled
  → Schema Registry lưu tất cả versions
  → API để query: "topic X đang dùng schema version nào?"
  → Compatibility check tự động

curl http://localhost:8085/subjects/order.placed-value/versions
→ [1, 2]  ← 2 versions đã registered

curl http://localhost:8085/subjects/order.placed-value/versions/latest
→ {schema: "...", version: 2, id: 5}

→ Mọi team đều biết schema hiện tại
→ Schema change = code change → đi qua PR review
→ CI/CD có thể check compatibility trước khi merge
→ Rollback: consumer tự handle old + new schema versions
```

---

## 7. Multi-Language Support

### JSON: Mỗi ngôn ngữ parse khác nhau

```
Java:     Jackson ObjectMapper → OrderEvent.class
Python:   json.loads() → dict (không có type checking)
Go:       json.Unmarshal() → struct (tag-based mapping)
Node.js:  JSON.parse() → plain object (no schema)

→ Mỗi team phải tự define class/struct matching JSON format
→ Không có shared schema definition
→ Drift giữa các ngôn ngữ rất dễ xảy ra
```

### Avro: Schema file là universal contract

```
order_event.avsc (schema file) → shared giữa tất cả ngôn ngữ

Java:     avro-maven-plugin → OrderEventAvro.java (generated)
Python:   avro-python3 → OrderEventAvro class (generated)
Go:       gogen-avro → OrderEventAvro struct (generated)
Node.js:  avsc → JavaScript class (generated)

→ MỘT schema file → generate code cho TẤT CẢ ngôn ngữ
→ Type-safe ở mọi ngôn ngữ
→ Thay đổi schema 1 chỗ → regenerate everywhere
→ CI/CD ensure tất cả services dùng cùng schema version
```

---

## 8. Debugging & Monitoring

### JSON: Dễ đọc bằng mắt, khó query at scale

```
Kafka Console Consumer output (JSON):
  {"eventId":"abc-123","orderId":"def-456","status":"PLACED",...}

✓ Pros: Human-readable, copy-paste dễ
✗ Cons:
  → Kafka UI hiển thị OK
  → Nhưng khi cần query schema history? Không có
  → "Message lỗi thuộc schema version nào?" → Không biết
  → "Bao nhiêu consumer đang dùng schema cũ?" → Không biết
```

### Avro + Schema Registry: Binary nhưng tooling mạnh

```
Kafka Console Consumer output (Avro binary):
  → Không đọc được bằng mắt (binary)

NHƯNG:
  → Kafka UI + Schema Registry = decode Avro → hiển thị readable
  → Schema Registry REST API:
    - /subjects → list tất cả schemas
    - /subjects/{name}/versions → schema history
    - /schemas/ids/{id} → schema by ID
  → Mỗi message chứa schema ID → trace được:
    - Message này dùng schema version nào?
    - Schema đó có compatible với current version không?
    - Producer nào đã register schema này?
```

---

## Tổng kết

| Tiêu chí                    | JSON (Jackson)           | Avro + Schema Registry     |
|------------------------------|--------------------------|----------------------------|
| Schema enforcement           | Không có                 | Schema Registry validate   |
| Schema evolution             | Manual, không guarantee  | Automatic compatibility check |
| Message size                 | Lớn (~450 bytes)         | Nhỏ (~270 bytes, -40%)     |
| Serialization speed          | Chậm (text parsing)      | Nhanh (binary, 2-5x)      |
| Type safety                  | Runtime errors           | Compile-time errors        |
| Contract management          | Implicit (docs/Slack)    | Explicit (Registry API)    |
| Multi-language               | Mỗi team tự define      | Generate từ 1 schema file  |
| Debugging                    | Human-readable           | Cần tooling (Kafka UI)     |
| Setup complexity             | Zero                     | Cần Schema Registry        |
| Learning curve               | Thấp                     | Trung bình                 |

### Khi nào dùng JSON?
- Prototype, MVP, hackathon
- Team nhỏ (1-3 developers), 1 repo
- Throughput thấp (<1K msg/s)
- Không cần schema evolution guarantee

### Khi nào dùng Avro + Schema Registry?
- Production systems
- Multi-team, multi-service
- Throughput cao (>1K msg/s)
- Cần schema evolution safety
- Cần audit trail cho schema changes
- Multi-language environment

### Trong project này
Chúng ta migrate từ JSON → Avro ở Step 7 để:
1. **Học cách production systems quản lý schema** — đây là industry standard
2. **Thấy cách Schema Registry bảo vệ khỏi breaking changes** — thêm field `source` an toàn
3. **Hiểu trade-off** — Avro cần thêm infrastructure (Schema Registry) nhưng đổi lại safety
4. **Thực hành mapper pattern** — tách Kafka serialization khỏi business logic
