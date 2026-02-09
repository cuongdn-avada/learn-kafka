package dnc.cuong.payment.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Bảng deduplication — lưu eventId đã xử lý để tránh process duplicate message.
 *
 * WHY quan trọng cho Payment Service?
 * → processOrderValidated: nếu duplicate → charge customer 2 lần!
 * → Payment là service critical nhất cần idempotency.
 * → Trong production: kết hợp với payment gateway idempotency key.
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
