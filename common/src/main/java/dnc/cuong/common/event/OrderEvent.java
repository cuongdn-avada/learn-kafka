package dnc.cuong.common.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Event chính đại diện cho một đơn hàng trong pipeline.
 *
 * WHY dùng record?
 * → Immutable by design — event KHÔNG BAO GIỜ được modify sau khi tạo.
 * → Tự generate equals/hashCode/toString — giảm boilerplate.
 * → Java 21 record hoàn hảo cho Event/DTO pattern.
 *
 * WHY không dùng generic "Event" wrapper?
 * → Mỗi event type có payload khác nhau. Explicit class rõ ràng hơn,
 *   dễ evolve schema, dễ deserialize.
 */
public record OrderEvent(

        @JsonProperty("eventId")
        UUID eventId,

        @JsonProperty("orderId")
        UUID orderId,

        @JsonProperty("customerId")
        UUID customerId,

        @JsonProperty("items")
        List<OrderItem> items,

        @JsonProperty("totalAmount")
        BigDecimal totalAmount,

        @JsonProperty("status")
        OrderStatus status,

        @JsonProperty("reason")
        String reason,

        @JsonProperty("createdAt")
        Instant createdAt

) {
    /**
     * Factory method tạo event mới với unique eventId và timestamp.
     * WHY factory method thay vì constructor?
     * → Đảm bảo eventId luôn unique (dùng cho idempotency sau này).
     * → createdAt luôn là thời điểm tạo event.
     */
    public static OrderEvent create(UUID orderId, UUID customerId,
                                     List<OrderItem> items, BigDecimal totalAmount,
                                     OrderStatus status) {
        return new OrderEvent(
                UUID.randomUUID(), orderId, customerId,
                items, totalAmount, status, null, Instant.now()
        );
    }

    public static OrderEvent withReason(UUID orderId, UUID customerId,
                                         List<OrderItem> items, BigDecimal totalAmount,
                                         OrderStatus status, String reason) {
        return new OrderEvent(
                UUID.randomUUID(), orderId, customerId,
                items, totalAmount, status, reason, Instant.now()
        );
    }

    public record OrderItem(
            @JsonProperty("productId") UUID productId,
            @JsonProperty("productName") String productName,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("price") BigDecimal price
    ) {}
}
