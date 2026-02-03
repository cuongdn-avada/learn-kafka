package dnc.cuong.common.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DTO cho request tạo đơn hàng từ client.
 *
 * WHY tách DTO khỏi Event?
 * → DTO đại diện cho contract với client (REST API).
 * → Event đại diện cho contract giữa các service (Kafka).
 * → Hai thứ này evolve độc lập — client có thể gửi thêm field mà không ảnh hưởng event schema.
 * → Tránh leak internal domain ra ngoài API.
 */
public record OrderCreateRequest(
        UUID customerId,
        List<OrderItemRequest> items
) {
    public record OrderItemRequest(
            UUID productId,
            String productName,
            int quantity,
            BigDecimal price
    ) {}
}
