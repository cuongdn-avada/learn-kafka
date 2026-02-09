# Step 10: Production Hardening & Advanced Topics

## Vấn đề cần giải quyết

Sau 9 steps, hệ thống đã có đầy đủ business logic, error handling, observability — nhưng chỉ chạy được trên local dev machine:

1. **Không có containerization** — 4 services chạy bằng `./mvnw spring-boot:run`, phải mở 4 terminal
2. **Không có graceful shutdown** — kill process = mất in-flight messages, transaction dang dở
3. **Kafka config chưa tối ưu** — dùng default settings, không compression, không batching
4. **Không có health check thực sự** — default health chỉ check bean tồn tại, không check broker reachable
5. **Hardcoded localhost URLs** — không thể chạy trong Docker network

## Giải pháp: 5 Pillars of Production Hardening

### 1. Graceful Shutdown

**Vấn đề:**
```
# Khi kill process (Ctrl+C, docker stop):
- HTTP request đang xử lý → 500 error
- Kafka consumer đang process message → message lost (hoặc duplicate khi rebalance)
- Database transaction đang commit → data inconsistency
```

**Giải pháp:**
```yaml
server:
  shutdown: graceful               # Từ chối new requests, đợi in-flight hoàn thành

spring.lifecycle:
  timeout-per-shutdown-phase: 30s  # Max 30s trước khi force kill
```

**Tại sao 30s?**
- Kafka consumer `max.poll.interval.ms: 300000` (5 min) nhưng thực tế message process < 1s
- Database transaction timeout thường < 5s
- 30s là buffer đủ cho hầu hết trường hợp mà không delay shutdown quá lâu

### 2. Kafka Production Tuning

#### Producer Tuning

| Config | Default | Step 10 | Tại sao |
|--------|---------|---------|---------|
| `compression.type` | none | snappy | Giảm ~50% message size, CPU overhead thấp |
| `linger.ms` | 0 | 20 | Đợi 20ms để batch nhiều messages → ít network round-trip |
| `batch.size` | 16384 (16KB) | 32768 (32KB) | Batch lớn hơn → throughput cao hơn |
| `delivery.timeout.ms` | 120000 | 120000 | Total timeout = linger.ms + request.timeout.ms + retries |

**Compression comparison:**

| Algorithm | Ratio | CPU | Speed |
|-----------|-------|-----|-------|
| none | 1x | 0 | Fastest |
| **snappy** | **~2x** | **Low** | **Fast** |
| lz4 | ~2x | Low | Fast |
| gzip | ~3x | High | Slow |
| zstd | ~3.5x | Medium | Medium |

→ **Snappy** là best default choice: compression ratio tốt, CPU overhead rất thấp.

#### Consumer Tuning

| Config | Default | Step 10 | Tại sao |
|--------|---------|---------|---------|
| `max.poll.records` | 500 | 100 | Limit records/poll → predictable processing time |
| `max.poll.interval.ms` | 300000 | 300000 | 5 min max giữa 2 poll calls |
| `session.timeout.ms` | 45000 | 45000 | Broker detect consumer dead sau 45s |
| `heartbeat.interval.ms` | 3000 | 15000 | 1/3 session.timeout (recommended ratio) |

**Tại sao `max.poll.records: 100` thay vì default 500?**
```
Default 500 records/poll:
  → Processing time = 500 × 10ms = 5s per batch
  → Nếu 1 record fail, cả batch bị ảnh hưởng
  → Memory usage cao hơn

100 records/poll:
  → Processing time = 100 × 10ms = 1s per batch
  → Nhanh hơn qua poll loop → heartbeat consistent
  → Ít risk timeout hơn
```

### 3. Custom Kafka Health Indicator

**Vấn đề:** Spring Boot default `KafkaHealthIndicator` chỉ check `KafkaAdmin` bean tồn tại, **không** check broker thực sự reachable.

**Giải pháp:** Custom `KafkaHealthIndicator` dùng `AdminClient.describeCluster()`:
```java
@Component("kafkaClusterHealth")
public class KafkaHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        try (AdminClient admin = AdminClient.create(...)) {
            DescribeClusterResult cluster = admin.describeCluster();
            String clusterId = cluster.clusterId().get(5, TimeUnit.SECONDS);
            int nodeCount = cluster.nodes().get(5, TimeUnit.SECONDS).size();
            return Health.up()
                    .withDetail("clusterId", clusterId)
                    .withDetail("nodeCount", nodeCount)
                    .build();
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
    }
}
```

**Response example:**
```json
{
  "status": "UP",
  "components": {
    "kafkaClusterHealth": {
      "status": "UP",
      "details": {
        "clusterId": "MkU3OEVBNTcwNTJENDM2Qg",
        "nodeCount": 1,
        "bootstrapServers": "localhost:9094"
      }
    }
  }
}
```

### 4. Dockerfiles (Multi-stage Build)

**Tại sao multi-stage?**
```
Single-stage (JDK image):
  → Image size: ~800MB
  → Chứa compiler, build tools → attack surface lớn
  → Build artifacts (source, .m2) trong image → waste

Multi-stage (JDK build → JRE runtime):
  → Image size: ~200MB
  → Chỉ có JRE + app JAR → minimal attack surface
  → Dependency caching → rebuild nhanh
```

**Dockerfile structure:**
```dockerfile
# Stage 1: Build (JDK + Maven)
FROM eclipse-temurin:21-jdk-alpine AS build
COPY mvnw .mvn pom.xml ...
RUN ./mvnw dependency:go-offline  # Cache dependencies
COPY src ...
RUN ./mvnw package -DskipTests   # Build JAR

# Stage 2: Runtime (JRE only)
FROM eclipse-temurin:21-jre-alpine
COPY --from=build app.jar
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

**JVM flags giải thích:**
- `-XX:+UseG1GC`: G1 garbage collector — good default cho microservices (balanced throughput/latency)
- `-XX:MaxRAMPercentage=75.0`: JVM dùng max 75% RAM container (để 25% cho OS, native memory, metaspace)

### 5. Docker Compose Profiles

**Vấn đề:** Developer cần 2 mode:
1. **Dev mode:** Infra chạy Docker, services chạy bằng IDE (debug, hot reload)
2. **Full mode:** Mọi thứ chạy Docker (demo, testing, CI)

**Giải pháp:** Docker Compose `profiles`:
```yaml
order-service:
  profiles: [app]   # Chỉ start khi dùng --profile app
  build:
    context: .
    dockerfile: order-service/Dockerfile
  environment:
    SPRING_PROFILES_ACTIVE: docker  # Activate application-docker.yml
```

```bash
# Dev mode (infra only)
docker compose up -d

# Full mode (infra + services)
docker compose --profile app up -d --build
```

**`application-docker.yml`** override localhost → Docker service names:
```yaml
# application.yml (dev):                  application-docker.yml (Docker):
spring.kafka.bootstrap-servers:           spring.kafka.bootstrap-servers:
  localhost:9094                            kafka:9092

spring.datasource.url:                    spring.datasource.url:
  jdbc:postgresql://localhost:5432/...      jdbc:postgresql://postgres:5432/...
```

## File Changes Summary

| Category | Files | Changes |
|----------|-------|---------|
| Graceful Shutdown | 4 application.yml | `server.shutdown: graceful`, lifecycle timeout |
| Kafka Tuning | 4 application.yml | Producer + consumer config properties |
| Health Check | 4 new KafkaHealthIndicator.java | Custom AdminClient-based health |
| Dockerfiles | 4 new Dockerfile | Multi-stage JDK→JRE build |
| Docker Compose | docker-compose.yml | 4 service containers + profiles + health checks |
| Docker Profiles | 4 new application-docker.yml | Override URLs for Docker network |
| Documentation | README.md, information.md, step-10.md, step10-sequence-diagram.md | Updated |

## Verification

```bash
# 1. Tests vẫn pass
./mvnw clean test

# 2. Build thành công
./mvnw clean package -DskipTests

# 3. Dev mode (infra only)
docker compose up -d
./mvnw spring-boot:run -pl order-service  # Vẫn hoạt động như trước

# 4. Full mode (Docker)
docker compose --profile app up -d --build

# 5. Health check
curl http://localhost:8081/actuator/health | jq '.components.kafkaClusterHealth'

# 6. Test order flow (same API, works in both modes)
curl -X POST http://localhost:8081/api/orders -H "Content-Type: application/json" \
  -d '{"customerId":"550e8400-e29b-41d4-a716-446655440000","items":[{"productId":"7c9e6679-7425-40de-944b-e07fc1f90ae7","productName":"MacBook Pro","quantity":1,"price":2499.99}]}'
```
