package dnc.cuong.common.avro;

import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapper chuyển đổi giữa OrderEvent (Java record) và OrderEventAvro (Avro generated).
 *
 * WHY cần mapper?
 * → OrderEvent là Java record — dùng trong REST API, service layer, JPA mapping.
 * → OrderEventAvro là Avro SpecificRecord — dùng cho Kafka serialization với Schema Registry.
 * → Mapper tách biệt Kafka serialization layer khỏi business logic.
 * → Service layer không cần biết về Avro — chỉ Kafka producer/consumer dùng mapper.
 */
public final class OrderEventMapper {

    private OrderEventMapper() {}

    /**
     * Convert OrderEvent (Java record) → OrderEventAvro (Avro SpecificRecord).
     * Dùng trong Kafka Producer trước khi publish message.
     *
     * @param event  business event
     * @param source service name that produces this event (e.g., "order-service")
     */
    public static OrderEventAvro toAvro(OrderEvent event, String source) {
        return OrderEventAvro.newBuilder()
                .setEventId(event.eventId().toString())
                .setOrderId(event.orderId().toString())
                .setCustomerId(event.customerId().toString())
                .setItems(event.items().stream()
                        .map(item -> OrderItemAvro.newBuilder()
                                .setProductId(item.productId().toString())
                                .setProductName(item.productName())
                                .setQuantity(item.quantity())
                                .setPrice(item.price().toString())
                                .build())
                        .toList())
                .setTotalAmount(event.totalAmount().toString())
                .setStatus(OrderStatusAvro.valueOf(event.status().name()))
                .setReason(event.reason())
                .setCreatedAt(event.createdAt().toString())
                .setSchemaVersion(1)
                .setSource(source)
                .build();
    }

    /**
     * Convert OrderEventAvro (Avro SpecificRecord) → OrderEvent (Java record).
     * Dùng trong Kafka Consumer sau khi deserialize message.
     */
    public static OrderEvent fromAvro(OrderEventAvro avro) {
        return new OrderEvent(
                UUID.fromString(avro.getEventId()),
                UUID.fromString(avro.getOrderId()),
                UUID.fromString(avro.getCustomerId()),
                avro.getItems().stream()
                        .map(item -> new OrderEvent.OrderItem(
                                UUID.fromString(item.getProductId()),
                                item.getProductName(),
                                item.getQuantity(),
                                new BigDecimal(item.getPrice())))
                        .toList(),
                new BigDecimal(avro.getTotalAmount()),
                OrderStatus.valueOf(avro.getStatus().name()),
                avro.getReason(),
                Instant.parse(avro.getCreatedAt())
        );
    }
}
