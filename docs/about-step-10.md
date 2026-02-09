# About Step 10: Production Hardening & Advanced Topics

## Tại sao cần Step 10?

Sau 9 steps, hệ thống đã có **đầy đủ business logic** — nhưng chỉ chạy được trên máy developer:

```
Trước Step 10:
┌─────────────────────────────────────────────────┐
│ Developer Machine                               │
│                                                 │
│  Terminal 1: ./mvnw spring-boot:run -pl order   │  ← Manual startup
│  Terminal 2: ./mvnw spring-boot:run -pl invent  │  ← Manual startup
│  Terminal 3: ./mvnw spring-boot:run -pl payment │  ← Manual startup
│  Terminal 4: ./mvnw spring-boot:run -pl notif   │  ← Manual startup
│                                                 │
│  Ctrl+C → message lost, transaction dang dở     │  ← No graceful shutdown
│  Kafka default config → chưa tối ưu             │  ← No production tuning
│  Health check → chỉ biết bean tồn tại           │  ← No real health check
│  Deploy? → Copy JAR? SSH? CI/CD?                │  ← No containerization
└─────────────────────────────────────────────────┘
```

Step 10 biến hệ thống từ "chạy được trên máy dev" thành "sẵn sàng deploy":

```
Sau Step 10:
┌─────────────────────────────────────────────────┐
│ docker compose --profile app up -d --build      │
│                                                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │  order   │ │ inventory│ │ payment  │ ...     │  ← Containerized
│  │  512MB   │ │  512MB   │ │  512MB   │         │  ← Resource limits
│  │  G1GC    │ │  G1GC    │ │  G1GC    │         │  ← JVM tuning
│  └──────────┘ └──────────┘ └──────────┘         │
│                                                 │
│  SIGTERM → graceful shutdown (30s)              │  ← No message loss
│  Kafka → snappy + batching + tuned consumers    │  ← Production tuning
│  /actuator/health → real broker connectivity    │  ← Real health check
│  Docker image → ~200MB (JRE only)               │  ← CI/CD ready
└─────────────────────────────────────────────────┘
```

---

## 5 Components & Tại sao chọn cách implement này

### 1. Graceful Shutdown

**Vấn đề thực tế:**
```
Khi Ctrl+C hoặc docker stop:

❌ Không có graceful shutdown:
  → HTTP request đang xử lý → client nhận 500/connection reset
  → Kafka consumer đang process → message mất, rebalance xảy ra
  → DB transaction đang commit → data inconsistency
  → Kafka producer buffer chưa flush → messages mất

✅ Có graceful shutdown:
  → Từ chối new requests, đợi in-flight hoàn thành
  → Consumer commit offset xong rồi mới stop
  → Producer flush buffer (linger.ms batch) trước khi close
  → DB connection pool drain sạch
```

**Tại sao 30s timeout?**
- Message processing thực tế < 1s
- DB transaction < 5s
- 30s là buffer hợp lý: đủ cho worst case, không delay shutdown quá lâu
- Nếu > 30s → force kill (JVM exit)

**Config chỉ cần 2 dòng:**
```yaml
server:
  shutdown: graceful
spring.lifecycle:
  timeout-per-shutdown-phase: 30s
```

→ Spring Boot tự handle: stop accepting → drain in-flight → close resources.

---

### 2. Kafka Production Tuning

**Tại sao cần tune?**

Kafka default configs optimize cho **latency** (gửi ngay từng message). Trong production, ta muốn balance giữa **throughput** và **latency**:

#### Producer Tuning

| Config | Default | Step 10 | Trade-off |
|--------|---------|---------|-----------|
| `compression.type` | none | snappy | +50% bandwidth saving, +~1ms CPU per batch |
| `linger.ms` | 0 | 20 | +20ms latency, +200% throughput |
| `batch.size` | 16KB | 32KB | +memory, +throughput khi high volume |
| `delivery.timeout.ms` | 120s | 120s | Total retry budget (keep default) |

**Tại sao chọn Snappy thay vì Gzip/Zstd?**
```
Snappy:  Compression ~2x, CPU rất thấp, speed nhanh nhất
Gzip:    Compression ~3x, CPU cao, speed chậm
Zstd:    Compression ~3.5x, CPU trung bình, speed trung bình
LZ4:     Compression ~2x, CPU thấp, speed nhanh

→ Snappy = best default: balance tốt nhất giữa ratio và CPU
→ Chỉ dùng Zstd khi bandwidth là bottleneck (cross-region replication)
```

**Tại sao linger.ms = 20 thay vì 0 (default)?**
```
linger.ms=0:
  Message 1 → send immediately → 1 network round-trip
  Message 2 → send immediately → 1 network round-trip
  Message 3 → send immediately → 1 network round-trip
  Total: 3 round-trips

linger.ms=20:
  Message 1 → wait...
  Message 2 → wait... (within 20ms window)
  Message 3 → wait...
  → Batch send all 3 → 1 network round-trip
  Total: 1 round-trip + 20ms delay

→ Trade-off: +20ms latency (negligible cho async event) → -66% network overhead
```

#### Consumer Tuning

| Config | Default | Step 10 | Tại sao |
|--------|---------|---------|---------|
| `max.poll.records` | 500 | 100 | Giảm processing time per batch → ít risk timeout |
| `session.timeout.ms` | 45s | 45s | Broker detect dead consumer sau 45s |
| `heartbeat.interval.ms` | 3s | 15s | Rule: 1/3 session.timeout (industry standard) |

**Tại sao heartbeat.interval.ms = 15s thay vì default 3s?**
```
session.timeout.ms = 45s
heartbeat.interval.ms nên = 1/3 session timeout (Kafka recommended)
→ 45s / 3 = 15s

Nếu 3 heartbeats liên tiếp bị miss → broker trigger rebalance
15s × 3 = 45s = session.timeout → consistent timing
```

---

### 3. Custom Kafka Health Indicator

**Vấn đề:** Default Spring Boot Kafka health chỉ check:
```java
// Default: KafkaHealthIndicator in spring-boot-actuator
// Chỉ check KafkaAdmin bean tồn tại → luôn UP
// KHÔNG thực sự connect tới broker
```

**Giải pháp:** Custom indicator dùng `AdminClient.describeCluster()`:
```java
AdminClient admin = AdminClient.create(bootstrapServers);
DescribeClusterResult cluster = admin.describeCluster();
String clusterId = cluster.clusterId().get(5, SECONDS);  // Real connection
int nodeCount = cluster.nodes().get(5, SECONDS).size();    // Real check
```

**Tại sao dùng AdminClient thay vì KafkaTemplate?**
- AdminClient → metadata operations (describe, list, create topics)
- KafkaTemplate → produce messages
- Health check cần check **broker connectivity**, không cần produce
- AdminClient nhẹ hơn, không cần serializer config

**Tại sao timeout 5s?**
- Normal response: < 100ms (local network)
- Docker network: < 500ms
- 5s = generous timeout cho slow network
- > 5s = broker likely down → report DOWN immediately

**Tại sao bean name `kafkaClusterHealth`?**
```json
// /actuator/health response:
{
  "components": {
    "kafkaClusterHealth": {     ← Custom health indicator
      "status": "UP",
      "details": {
        "clusterId": "MkU3OEVBNTcwNTJENDM2Qg",
        "nodeCount": 1
      }
    },
    "db": { "status": "UP" },  ← Default DB health
    "ping": { "status": "UP" } ← Default ping
  }
}
```

**Use cases:**
- **Docker health check:** `wget -qO- http://localhost:8081/actuator/health` → nếu DOWN → restart container
- **Load balancer:** Health endpoint → route traffic away from unhealthy instance
- **Prometheus alert:** `up{job="order-service"} == 0` → trigger PagerDuty

---

### 4. Multi-stage Dockerfiles

**Tại sao multi-stage thay vì single-stage?**

```dockerfile
# ❌ Single-stage (bad):
FROM eclipse-temurin:21-jdk-alpine
COPY . /app
RUN mvn package
ENTRYPOINT ["java", "-jar", "app.jar"]
# → Image: ~800MB (JDK + Maven + source + .m2 cache)
# → Contains: compiler, build tools, source code
# → Security: large attack surface

# ✅ Multi-stage (Step 10):
FROM eclipse-temurin:21-jdk-alpine AS build    # Stage 1: Build
RUN mvn package

FROM eclipse-temurin:21-jre-alpine              # Stage 2: Runtime
COPY --from=build target/*.jar app.jar
# → Image: ~200MB (JRE + JAR only)
# → Contains: only runtime + application
# → Security: minimal attack surface
```

**Tại sao eclipse-temurin:21-jre-alpine?**
- `eclipse-temurin`: Official OpenJDK distribution (Adoptium, formerly AdoptOpenJDK)
- `21`: Java 21 LTS (match project)
- `jre`: Runtime only (no compiler)
- `alpine`: Minimal Linux (~5MB base vs ~80MB Ubuntu)

**Tại sao JVM flags `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0`?**
```
G1GC (Garbage-First Garbage Collector):
  → Default GC cho Java 11+
  → Balance throughput + latency
  → Good cho microservice (heap < 4GB)
  → Alternative: ZGC cho ultra-low latency (nhưng overkill ở đây)

MaxRAMPercentage=75.0:
  → JVM dùng max 75% RAM container
  → 25% còn lại cho: OS, native memory, metaspace, thread stacks
  → Container limit 512MB → JVM heap max ~384MB
  → KHÔNG dùng -Xmx vì hardcode → phải đổi khi scale container size
```

**Dependency caching strategy:**
```dockerfile
# Layer 1: Maven wrapper + POM files (rarely change)
COPY mvnw .mvn pom.xml common/pom.xml order-service/pom.xml
RUN ./mvnw dependency:go-offline    # Download all deps → cached layer

# Layer 2: Source code (changes every commit)
COPY common/src order-service/src
RUN ./mvnw package -DskipTests     # Only recompile, deps already cached
```
→ Khi chỉ đổi source code, Docker reuse layer 1 (cache hit) → build nhanh hơn ~3-5x.

---

### 5. Docker Compose Profiles

**Vấn đề:** Developer cần 2 mode:

| Mode | Use Case | Command |
|------|----------|---------|
| **Dev mode** | Debug với IDE, hot reload, set breakpoints | `docker compose up -d` |
| **Full mode** | Demo, testing, CI, deployment | `docker compose --profile app up -d` |

**Tại sao dùng profiles thay vì 2 docker-compose files?**
```
❌ 2 files: docker-compose.yml + docker-compose.prod.yml
  → Duplicate infrastructure config
  → Khó maintain sync giữa 2 files
  → Hay quên update 1 file

✅ 1 file + profiles:
  → Infrastructure services: không có profiles → luôn start
  → App services: profiles: [app] → chỉ start khi --profile app
  → Single source of truth
```

**application-docker.yml — tại sao cần?**
```yaml
# Dev (application.yml):
spring.kafka.bootstrap-servers: localhost:9094        # Host access
spring.datasource.url: jdbc:postgresql://localhost:5432/order_db

# Docker (application-docker.yml):
spring.kafka.bootstrap-servers: kafka:9092            # Container-to-container
spring.datasource.url: jdbc:postgresql://postgres:5432/order_db
```

→ Spring profile resolution: `SPRING_PROFILES_ACTIVE=docker` → load `application.yml` first, then `application-docker.yml` **overrides** matching keys.

**Tại sao port `kafka:9092` trong Docker thay vì `kafka:9094`?**
```
Kafka listeners:
  PLAINTEXT://0.0.0.0:9092     → Container-to-container (internal)
  EXTERNAL://0.0.0.0:9094      → Host-to-container (developer access)

Container → Container: dùng port 9092 (PLAINTEXT listener)
Host → Container: dùng port 9094 (EXTERNAL listener, mapped to host)
```

**Resource limits:**
```yaml
deploy:
  resources:
    limits:
      cpus: "1.0"      # Max 1 CPU core
      memory: 512M      # Max 512MB RAM
```

→ Tại sao 512MB?
- JVM heap (75%) = ~384MB
- Spring Boot app typical usage: 200-350MB
- 512MB = sufficient buffer + native memory overhead
- Notification service: 384MB (stateless, no DB connection pool)

---

## Tổng kết: What Step 10 Gives You

| Before | After | Impact |
|--------|-------|--------|
| 4 terminal windows | 1 command (`--profile app`) | Developer experience |
| Ctrl+C = message lost | Graceful 30s drain | Reliability |
| Default Kafka config | Snappy + batching | ~50% bandwidth, 200% throughput |
| Fake health check | Real broker connectivity | Monitoring & alerting |
| No Docker images | ~200MB production images | CI/CD ready |
| Hardcoded localhost | Profile-based config | Environment flexibility |
| No resource limits | 512MB + 1 CPU per service | Predictable resource usage |

---

## Giới hạn (Out of Scope)

Step 10 là **production hardening cho learning project**, không phải full production setup. Những thứ cần thêm cho real production:

| Missing | Tool | Lý do skip |
|---------|------|------------|
| Secrets management | Vault, K8s Secrets | Learning project dùng hardcoded creds |
| Multi-broker Kafka | 3+ brokers | Replication factor > 1 cần multiple brokers |
| Database migration | Flyway/Liquibase | ddl-auto: update đủ cho dev |
| Integration tests | Testcontainers | Focus Step 8 = unit tests |
| CI/CD pipeline | GitHub Actions | Infra setup ngoài scope |
| Rate limiting | Resilience4j | Overkill cho learning |
| API Gateway | Spring Cloud Gateway | Single entry point, routing, auth |
