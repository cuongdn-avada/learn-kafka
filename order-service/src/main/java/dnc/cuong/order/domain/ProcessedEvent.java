package dnc.cuong.order.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Bảng deduplication — lưu eventId đã xử lý để tránh process duplicate message.
 *
 * WHY dùng eventId làm PK?
 * → Mỗi OrderEvent có eventId unique (UUID.randomUUID trong factory method).
 * → Cùng orderId xuất hiện trong nhiều event (placed, validated, paid, completed).
 * → eventId identify chính xác 1 message delivery — dùng để detect duplicate.
 *
 * WHY lưu trong cùng DB transaction với business logic?
 * → Atomic: nếu business logic rollback → ProcessedEvent cũng rollback.
 * → Nếu chỉ business logic commit mà ProcessedEvent không → retry sẽ duplicate.
 * → Nếu chỉ ProcessedEvent commit mà business logic không → event bị skip mà chưa xử lý.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEvent(UUID eventId, String topic) {
        this.eventId = eventId;
        this.topic = topic;
        this.processedAt = Instant.now();
    }
}
