# Step 8: Testing — Hướng dẫn Verify chi tiết

## Mục tiêu

Step 8 thêm **59 unit tests** cho toàn bộ business logic, đảm bảo:
- Service layer xử lý đúng logic (happy path + failure path)
- Idempotency hoạt động (duplicate event bị skip)
- Domain logic chính xác (stock reservation/release)
- REST API trả đúng status code và response format
- Avro ↔ OrderEvent conversion không mất data

---

## 1. Chạy toàn bộ test suite

```bash
./mvnw clean test
```

**Kết quả mong đợi:**
```
[INFO] Reactor Summary for learn-kafka 0.0.1-SNAPSHOT:
[INFO]
[INFO] learn-kafka ........................................ SUCCESS
[INFO] common ............................................. SUCCESS
[INFO] order-service ...................................... SUCCESS
[INFO] inventory-service .................................. SUCCESS
[INFO] payment-service .................................... SUCCESS
[INFO] notification-service ............................... SUCCESS
[INFO] BUILD SUCCESS
```

- Tổng: **59 tests**, 0 failures, 0 errors
- Thời gian: ~7 giây
- **Không cần** Docker, Kafka, PostgreSQL, Schema Registry — tất cả test chạy offline

---

## 2. Chạy test từng module riêng

Nếu muốn verify từng module:

```bash
# Common — OrderEventMapper (6 tests)
./mvnw test -pl common

# Order Service — OrderServiceTest + OrderControllerTest (17 tests)
./mvnw test -pl order-service

# Inventory Service — InventoryServiceTest + ProductTest (21 tests)
./mvnw test -pl inventory-service

# Payment Service — PaymentServiceTest (7 tests)
./mvnw test -pl payment-service

# Notification Service — NotificationServiceTest (8 tests)
./mvnw test -pl notification-service
```

---

## 3. Chạy test class cụ thể

```bash
# Chỉ chạy 1 test class
./mvnw test -pl order-service -Dtest=OrderServiceTest

# Chỉ chạy 1 test method
./mvnw test -pl order-service -Dtest="OrderServiceTest#createOrder_shouldCalculateTotalAmountAndSave"

# Chạy nhiều test class
./mvnw test -pl inventory-service -Dtest="ProductTest,InventoryServiceTest"
```

---

## 4. Chi tiết từng test class

### 4.1. OrderEventMapperTest (common — 6 tests)

**File:** `common/src/test/java/dnc/cuong/common/avro/OrderEventMapperTest.java`

**Test gì:**
- `toAvro_shouldConvertAllFieldsCorrectly` — Convert OrderEvent → OrderEventAvro, verify tất cả fields (UUID→String, BigDecimal→String, Instant→String, OrderStatus→OrderStatusAvro)
- `toAvro_shouldHandleReasonField` — Verify field `reason` (nullable) được convert đúng khi có giá trị
- `fromAvro_shouldConvertAllFieldsCorrectly` — Convert OrderEventAvro → OrderEvent, verify ngược lại (String→UUID, String→BigDecimal, etc.)
- `roundTrip_shouldPreserveAllData` — **Quan trọng nhất**: OrderEvent → Avro → OrderEvent, so sánh original vs restored — không mất data
- `toAvro_shouldMapAllStatusValues` — Loop qua tất cả 6 OrderStatus enum values, verify mỗi status đều map đúng sang Avro
- `toAvro_shouldHandleMultipleItems` — Verify order có nhiều items (3 items) convert đúng

**Verify bằng tay:**
```bash
./mvnw test -pl common -Dtest=OrderEventMapperTest
```
```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

**WHY quan trọng:**
Mapper là cầu nối giữa business layer và Kafka layer. Nếu conversion sai → toàn bộ Saga flow bị ảnh hưởng (mất orderId, sai amount, etc.).

---

### 4.2. ProductTest (inventory-service — 13 tests)

**File:** `inventory-service/src/test/java/dnc/cuong/inventory/domain/ProductTest.java`

**Test gì:**

| Method | Test | Mong đợi |
|--------|------|----------|
| `hasStock` | available=50, request=50 | `true` |
| `hasStock` | available=100, request=30 | `true` |
| `hasStock` | available=5, request=10 | `false` |
| `hasStock` | available=0, request=1 | `false` |
| `hasStock` | available=10, request=0 | `true` |
| `reserveStock` | available=50, reserve=10 | available=40, reserved=10 |
| `reserveStock` | 2 lần reserve (30+20) | available=50, reserved=50 |
| `reserveStock` | reserve toàn bộ (25/25) | available=0, reserved=25 |
| `reserveStock` | available=5, reserve=10 | `IllegalStateException` + state không đổi |
| `releaseStock` | reserved=10, release=10 | available+10, reserved=0 |
| `releaseStock` | reserved=20, release=5 (partial) | available+5, reserved=15 |
| `releaseStock` | reserved=10, release=15 | `IllegalStateException` + state không đổi |
| `reserve+release` | Saga compensation flow | Quay về đúng trạng thái ban đầu |

**Verify bằng tay:**
```bash
./mvnw test -pl inventory-service -Dtest=ProductTest
```
```
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

**WHY quan trọng:**
Stock management là critical — sai stock = oversell hoặc mất tiền. Test Saga compensation (reserve → release) đảm bảo stock quay về đúng khi payment fail.

---

### 4.3. OrderServiceTest (order-service — 12 tests)

**File:** `order-service/src/test/java/dnc/cuong/order/service/OrderServiceTest.java`

**Dependencies được mock:** `OrderRepository`, `ProcessedEventRepository`, `OrderKafkaProducer`

**Test gì:**

| Method | Test | Mong đợi |
|--------|------|----------|
| `createOrder` | 2 items (2×$2499.99 + 1×$99.99) | totalAmount=$5099.97, status=PLACED, 2 items saved |
| `createOrder` | Verify event published | `sendOrderPlaced()` called with correct OrderEvent data |
| `completeOrder` | Happy path | status → COMPLETED, ProcessedEvent saved, `sendOrderCompleted()` called |
| `completeOrder` | Duplicate event | Skip (no DB update, no Kafka publish) |
| `completeOrder` | Order not found | `OrderNotFoundException` thrown |
| `failOrder` | Happy path | status → FAILED, failureReason set |
| `failOrder` | Duplicate event | Skip |
| `handlePaymentFailure` | Happy path | status → PAYMENT_FAILED, failureReason set |
| `handlePaymentFailure` | Duplicate event | Skip |
| `getOrder` | Order exists | Return order |
| `getOrder` | Not exists | `OrderNotFoundException` thrown |
| `getOrdersByCustomer` | 2 orders | Return list of 2 |

**Verify bằng tay:**
```bash
./mvnw test -pl order-service -Dtest=OrderServiceTest
```
```
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

**Đọc log output:**
Bạn sẽ thấy log từ OrderService khi test chạy:
```
INFO  OrderService : Order created | orderId=... | totalAmount=5099.97 | itemCount=2
INFO  OrderService : Order COMPLETED | orderId=...
WARN  OrderService : Order FAILED | orderId=... | reason=Insufficient stock
WARN  OrderService : Duplicate event detected, skipping | eventId=...
```

---

### 4.4. InventoryServiceTest (inventory-service — 8 tests)

**File:** `inventory-service/src/test/java/dnc/cuong/inventory/service/InventoryServiceTest.java`

**Dependencies được mock:** `ProductRepository`, `ProcessedEventRepository`, `InventoryKafkaProducer`

**Test gì:**

| Method | Test | Mong đợi |
|--------|------|----------|
| `processOrderPlaced` | Stock đủ (1 item) | available-=qty, reserved+=qty, `sendOrderValidated()` called |
| `processOrderPlaced` | Stock đủ (2 items) | Cả 2 products đều reserve đúng |
| `processOrderPlaced` | Product not found | `sendOrderFailed()` with reason "Product not found" |
| `processOrderPlaced` | Insufficient stock | `sendOrderFailed()` with reason "Insufficient stock" |
| `processOrderPlaced` | Duplicate event | Skip everything |
| `compensateReservation` | Happy path | Stock released (available+=qty, reserved-=qty) |
| `compensateReservation` | Product missing | No exception thrown, ProcessedEvent still saved |
| `compensateReservation` | Duplicate event | Skip |

**Verify bằng tay:**
```bash
./mvnw test -pl inventory-service -Dtest=InventoryServiceTest
```

---

### 4.5. PaymentServiceTest (payment-service — 7 tests)

**File:** `payment-service/src/test/java/dnc/cuong/payment/service/PaymentServiceTest.java`

**Dependencies được mock:** `PaymentRepository`, `ProcessedEventRepository`, `PaymentKafkaProducer`

**Test gì — focus boundary testing:**

| Test | Amount | Mong đợi |
|------|--------|----------|
| Below threshold | $5,000 | Payment SUCCESS, `sendOrderPaid()` called |
| Exactly at threshold | $10,000 | Payment SUCCESS (boundary: `<=` operator) |
| Above threshold | $15,000 | Payment FAILED, `sendPaymentFailed()` called, reason contains "exceeds limit" |
| Just above threshold | $10,000.01 | Payment FAILED (boundary) |
| Zero amount | $0 | Payment SUCCESS |
| Duplicate event | any | Skip (no payment saved, no event published) |
| Preserve event data | $2,000 | orderId, customerId, items, amount preserved in published event |

**Verify bằng tay:**
```bash
./mvnw test -pl payment-service -Dtest=PaymentServiceTest
```

**WHY boundary testing quan trọng:**
Business rule: `totalAmount.compareTo(MAX_AMOUNT) <= 0`. Test verify chính xác $10,000 pass nhưng $10,000.01 fail.

---

### 4.6. NotificationServiceTest (notification-service — 8 tests)

**File:** `notification-service/src/test/java/dnc/cuong/notification/service/NotificationServiceTest.java`

**Không có mock** — NotificationService là stateless (chỉ dùng in-memory Set cho dedup).

**Test gì:**

| Test | Mong đợi |
|------|----------|
| `notifyOrderCompleted` — first event | Process thành công (log output) |
| `notifyOrderCompleted` — duplicate | Skip (no exception, nhưng không log notification) |
| `notifyOrderCompleted` — 2 different events | Cả 2 đều process (khác eventId) |
| `notifyOrderFailed` — first/duplicate | Tương tự completed |
| `notifyPaymentFailed` — first/duplicate | Tương tự completed |
| Cross-method dedup | Cùng eventId qua nhiều method → chỉ process lần đầu |

**Verify bằng tay:**
```bash
./mvnw test -pl notification-service -Dtest=NotificationServiceTest
```

**Đọc log output:**
```
INFO  NotificationService : === NOTIFICATION: ORDER COMPLETED ===
WARN  NotificationService : Duplicate notification skipped | eventId=... | type=ORDER_FAILED
```

---

### 4.7. OrderControllerTest (order-service — 5 tests)

**File:** `order-service/src/test/java/dnc/cuong/order/controller/OrderControllerTest.java`

**Dùng `@WebMvcTest`** — chỉ load web layer (controller + GlobalExceptionHandler), mock `OrderService`.

**Test gì:**

| Endpoint | Test | Status | Response |
|----------|------|--------|----------|
| `POST /api/orders` | Create order | 201 CREATED | OrderResponse with orderId, status=PLACED |
| `GET /api/orders/{id}` | Order exists | 200 OK | OrderResponse with status |
| `GET /api/orders/{id}` | Not found | 404 NOT FOUND | ProblemDetail: title="Order Not Found" |
| `GET /api/orders?customerId=` | Has orders | 200 OK | List of 2 OrderResponse |
| `GET /api/orders?customerId=` | No orders | 200 OK | Empty list `[]` |

**Verify bằng tay:**
```bash
./mvnw test -pl order-service -Dtest=OrderControllerTest
```

---

## 5. Verify test output chi tiết (verbose mode)

Nếu muốn xem chi tiết từng test pass/fail:

```bash
# Maven Surefire verbose output
./mvnw test -pl order-service -Dtest=OrderServiceTest -Dsurefire.useFile=false
```

---

## 6. Cấu trúc test directories

```
learn-kafka/
├── common/
│   └── src/test/java/dnc/cuong/common/avro/
│       └── OrderEventMapperTest.java          ← Avro conversion (6 tests)
├── order-service/
│   └── src/test/java/dnc/cuong/order/
│       ├── controller/
│       │   └── OrderControllerTest.java       ← REST API (5 tests)
│       └── service/
│           └── OrderServiceTest.java          ← Business logic (12 tests)
├── inventory-service/
│   └── src/test/java/dnc/cuong/inventory/
│       ├── domain/
│       │   └── ProductTest.java               ← Domain logic (13 tests)
│       └── service/
│           └── InventoryServiceTest.java      ← Stock validation (8 tests)
├── payment-service/
│   └── src/test/java/dnc/cuong/payment/service/
│       └── PaymentServiceTest.java            ← Payment threshold (7 tests)
└── notification-service/
    └── src/test/java/dnc/cuong/notification/service/
        └── NotificationServiceTest.java       ← Dedup logic (8 tests)
```

---

## 7. Testing patterns sử dụng trong Step 8

### 7.1. Mockito — Mock dependencies

```java
@ExtendWith(MockitoExtension.class)  // Không cần Spring context
class OrderServiceTest {
    @Mock OrderRepository orderRepository;           // Mock DB
    @Mock OrderKafkaProducer kafkaProducer;           // Mock Kafka
    @Mock ProcessedEventRepository processedEventRepository; // Mock idempotency
    @InjectMocks OrderService orderService;           // Inject mocks vào service
}
```

**WHY:** Test chạy trong milliseconds, không cần Kafka/DB/Schema Registry.

### 7.2. ArgumentCaptor — Verify event data

```java
ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
verify(kafkaProducer).sendOrderValidated(eventCaptor.capture());

OrderEvent capturedEvent = eventCaptor.getValue();
assertEquals(OrderStatus.VALIDATED, capturedEvent.status());  // Verify đúng status
assertEquals(orderId, capturedEvent.orderId());               // Verify đúng orderId
```

**WHY:** Không chỉ verify method được gọi, mà còn verify **nội dung** event chính xác.

### 7.3. Idempotency test pattern

```java
@Test
void shouldSkipDuplicateEvent() {
    // Given — event đã process trước đó
    when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

    // When
    service.processEvent(event);

    // Then — KHÔNG làm gì cả
    verify(repository, never()).findById(any());     // Không query DB
    verify(kafkaProducer, never()).sendXxx(any());   // Không publish Kafka
}
```

**WHY:** Đảm bảo idempotency hoạt động — Kafka at-least-once delivery có thể gửi duplicate.

### 7.4. MockMvc — Test REST API

```java
@WebMvcTest(OrderController.class)  // Chỉ load web layer
class OrderControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean OrderService orderService;  // Mock service layer

    @Test
    void createOrder_shouldReturn201() throws Exception {
        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }
}
```

**WHY:** Test HTTP layer (routing, status code, JSON format) mà không cần DB/Kafka.

---

## 8. Checklist verify hoàn tất Step 8

- [ ] `./mvnw clean test` → **BUILD SUCCESS**, 59 tests, 0 failures
- [ ] Common: OrderEventMapperTest — 6 tests pass
- [ ] Order: OrderServiceTest — 12 tests pass
- [ ] Order: OrderControllerTest — 5 tests pass
- [ ] Inventory: ProductTest — 13 tests pass
- [ ] Inventory: InventoryServiceTest — 8 tests pass
- [ ] Payment: PaymentServiceTest — 7 tests pass
- [ ] Notification: NotificationServiceTest — 8 tests pass
- [ ] Không cần Docker/Kafka/PostgreSQL để chạy test
- [ ] README.md đã update Step 8 = DONE + Testing section
- [ ] `docs/step8-sequence-diagram.md` đã tạo
