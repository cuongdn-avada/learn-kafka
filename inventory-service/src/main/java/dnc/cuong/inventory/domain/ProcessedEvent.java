package dnc.cuong.inventory.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Bảng deduplication — lưu eventId đã xử lý để tránh process duplicate message.
 *
 * WHY quan trọng cho Inventory Service?
 * → processOrderPlaced: nếu duplicate → reserveStock 2 lần → stock bị trừ gấp đôi!
 * → compensateReservation: nếu duplicate → releaseStock 2 lần → stock bị cộng thừa!
 * → Cả 2 đều gây inconsistent stock data.
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
