# E-Commerce Order Processing Pipeline

## Use Case

Hệ thống xử lý đơn hàng event-driven — từ lúc khách đặt hàng → validate inventory → xử lý payment → gửi notification.
Sử dụng **Choreography-based Saga** pattern, các service giao tiếp qua Kafka topics.

## Tech Stack

| Technology        | Version | Mục đích                          |
|-------------------|---------|-----------------------------------|
| Java              | 21      | LTS, records, virtual threads     |
| Spring Boot       | 3.3.5   | Framework chính                   |
| Spring Kafka      | 3.2.4   | Kafka producer/consumer           |
| PostgreSQL        | 16      | Database per service              |
| Apache Kafka      | 4.0.0 (KRaft)     | Event broker               |
| Lombok            | 1.18.34 | Giảm boilerplate                  |
| Maven             | Multi-module | Build tool                   |
| Docker Compose    | -       | Local infrastructure              |

## Project Structure

```
learn-kafka/
├── pom.xml                     ← Parent POM (multi-module, dependency management)
├── docker-compose.yml          ← Kafka (KRaft) + PostgreSQL + Kafka UI
├── infra/
│   └── postgres/
│       └── init-databases.sh   ← Script tạo 3 databases khi PostgreSQL khởi động
├── .claude/
│   └── information.md          ← File này — context dự án
├── common/                     ← Module shared (không có Spring dependency)
├── order-service/              ← Service xử lý đơn hàng
├── inventory-service/          ← Service quản lý kho
├── payment-service/            ← Service thanh toán
└── notification-service/       ← Service gửi thông báo
```

## Module Responsibilities

### `common/`
- **Vai trò:** Chứa event classes, DTOs, constants dùng chung giữa tất cả service.
- **Không có** Spring Boot dependency — chỉ Jackson cho serialization.
- **Packages:**
  - `dnc.cuong.common.event` — `OrderEvent` (record), `OrderStatus` (enum), `KafkaTopics` (constants)
  - `dnc.cuong.common.dto` — Request/Response DTOs (sẽ thêm)

### `order-service/` — Port 8081, DB: `order_db`
- **Vai trò:** Entry point của hệ thống. Nhận request tạo đơn hàng qua REST API, publish event lên Kafka, theo dõi trạng thái order trong Saga.
- **Kafka role:** Producer (publish `order.placed`) + Consumer (consume `order.paid`, `order.failed`, `payment.failed` để cập nhật trạng thái)
- **Packages:**
  - `dnc.cuong.order.config` — Kafka config, app config
  - `dnc.cuong.order.controller` — REST API endpoints
  - `dnc.cuong.order.domain` — JPA entities, repositories
  - `dnc.cuong.order.service` — Business logic
  - `dnc.cuong.order.kafka` — Producer, consumer classes

### `inventory-service/` — Port 8082, DB: `inventory_db`
- **Vai trò:** Quản lý tồn kho. Nhận event đặt hàng → reserve stock → publish kết quả.
- **Kafka role:** Consumer (`order.placed`) + Producer (`order.validated` hoặc `order.failed`)
- **Packages:**
  - `dnc.cuong.inventory.config` — Kafka config
  - `dnc.cuong.inventory.domain` — Product entity, stock management
  - `dnc.cuong.inventory.service` — Stock reservation logic
  - `dnc.cuong.inventory.kafka` — Consumer, producer classes

### `payment-service/` — Port 8083, DB: `payment_db`
- **Vai trò:** Xử lý thanh toán. Nhận event đã validate → charge customer → publish kết quả.
- **Kafka role:** Consumer (`order.validated`) + Producer (`order.paid` hoặc `payment.failed`)
- **Packages:**
  - `dnc.cuong.payment.config` — Kafka config
  - `dnc.cuong.payment.domain` — Payment entity, transaction records
  - `dnc.cuong.payment.service` — Payment processing logic
  - `dnc.cuong.payment.kafka` — Consumer, producer classes

### `notification-service/` — Port 8084, No DB
- **Vai trò:** Gửi thông báo (email/push) khi order hoàn thành hoặc thất bại.
- **Kafka role:** Consumer only (`order.completed`, `order.failed`, `payment.failed`)
- **Không cần database** — stateless, chỉ consume và gửi notification.
- **Packages:**
  - `dnc.cuong.notification.config` — Kafka config
  - `dnc.cuong.notification.service` — Notification sending logic
  - `dnc.cuong.notification.kafka` — Consumer classes

## Saga Flow (Choreography)

```
Client POST /orders
    │
    ▼
Order Service ──publish──► [order.placed]
                                │
                                ▼
                     Inventory Service
                     ├─ stock OK ──► [order.validated]
                     │                      │
                     │                      ▼
                     │              Payment Service
                     │              ├─ paid OK ──► [order.paid] ──► Order Service cập nhật COMPLETED
                     │              │                                      │
                     │              │                              [order.completed] ──► Notification Service
                     │              │
                     │              └─ paid FAIL ──► [payment.failed] ──► Inventory Service (release stock)
                     │                                                 ──► Order Service (PAYMENT_FAILED)
                     │
                     └─ stock FAIL ──► [order.failed] ──► Order Service (FAILED)
                                                       ──► Notification Service
```

## Kafka Topics

| Topic              | Partitions | Producer          | Consumer(s)                    |
|--------------------|------------|-------------------|--------------------------------|
| `order.placed`     | 3          | Order Service     | Inventory Service              |
| `order.validated`  | 3          | Inventory Service | Payment Service                |
| `order.paid`       | 3          | Payment Service   | Order Service                  |
| `order.completed`  | 3          | Order Service     | Notification Service           |
| `order.failed`     | 3          | Inventory Service | Order Service, Notification    |
| `payment.failed`   | 3          | Payment Service   | Inventory, Order, Notification |

## Infrastructure (Docker Compose)

| Service    | Image                          | Host Port | Mục đích                |
|------------|--------------------------------|-----------|-------------------------|
| Kafka      | apache/kafka:4.0.0 (KRaft)    | 9094      | Event broker            |
| PostgreSQL | postgres:16-alpine             | 5432      | 3 databases             |
| Kafka UI   | provectuslabs/kafka-ui:latest  | 8088      | Web UI cho Kafka        |
| kafka-init | apache/kafka:4.0.0 (one-shot)  | -         | Tạo topics khi startup  |

## Design Decisions

| Quyết định                    | Lựa chọn          | Lý do                                                    |
|-------------------------------|--------------------|-----------------------------------------------------------|
| Saga style                    | Choreography       | Event-driven thuần túy, giảm single point of failure      |
| Project structure             | Multi-module Maven | Share schema, dễ local dev, tách repo khi lên production  |
| Database                      | PostgreSQL         | ACID, mature, JSON support                                |
| Serialization                 | JSON (Jackson)     | Đơn giản ban đầu, migrate sang Avro ở Step 7              |
| Topic auto-create             | Disabled           | Production practice, kiểm soát partition count + config    |
| `open-in-view`                | false              | Tránh lazy loading ngoài transaction                      |
| `acks=all` + idempotent       | Enabled            | Đảm bảo message durability, tránh duplicate               |
| Kafka host port               | 9094               | Tránh conflict với process chiếm 9092                     |

## Learning Roadmap

| Step | Nội dung                                  | Trạng thái |
|------|-------------------------------------------|------------|
| 1    | Project Setup & Infrastructure            | DONE       |
| 2    | Order Service — Producer fundamentals     | DONE       |
| 3    | Inventory Service — Consumer fundamentals | DONE       |
| 4    | Saga Choreography — Full happy path       | DONE       |
| 5    | Error Handling & Dead Letter Queue        | DONE       |
| 6    | Idempotency & Exactly-Once Semantics      | DONE       |
| 7    | Schema Evolution & Contract Management    | NEXT       |
| 8    | Testing — Unit + Integration              | Pending    |
| 9    | Observability — Metrics, Tracing, Logging | Pending    |
| 10   | Production Hardening & Advanced Topics    | Pending    |
