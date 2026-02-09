package dnc.cuong.common.avro;

import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho OrderEventMapper — verify chuyển đổi giữa OrderEvent ↔ OrderEventAvro.
 *
 * WHY test mapper riêng?
 * -> Mapper là cầu nối giữa business layer (OrderEvent) và Kafka layer (OrderEventAvro).
 * -> Nếu conversion sai (mất data, sai type) -> toàn bộ Saga flow bị ảnh hưởng.
 * -> Test nhanh, không cần infrastructure (Kafka, DB).
 */
class OrderEventMapperTest {

    @Test
    void toAvro_shouldConvertAllFieldsCorrectly() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2024-01-15T10:30:00Z");

        OrderEvent event = new OrderEvent(
                eventId, orderId, customerId,
                List.of(new OrderEvent.OrderItem(productId, "MacBook Pro", 2, new BigDecimal("2499.99"))),
                new BigDecimal("4999.98"),
                OrderStatus.PLACED,
                null,
                createdAt
        );

        // When
        OrderEventAvro avro = OrderEventMapper.toAvro(event, "order-service");

        // Then
        assertEquals(eventId.toString(), avro.getEventId());
        assertEquals(orderId.toString(), avro.getOrderId());
        assertEquals(customerId.toString(), avro.getCustomerId());
        assertEquals("4999.98", avro.getTotalAmount());
        assertEquals(OrderStatusAvro.PLACED, avro.getStatus());
        assertNull(avro.getReason());
        assertEquals(createdAt.toString(), avro.getCreatedAt());
        assertEquals(1, avro.getSchemaVersion());
        assertEquals("order-service", avro.getSource());

        // Verify items
        assertEquals(1, avro.getItems().size());
        OrderItemAvro avroItem = avro.getItems().get(0);
        assertEquals(productId.toString(), avroItem.getProductId());
        assertEquals("MacBook Pro", avroItem.getProductName());
        assertEquals(2, avroItem.getQuantity());
        assertEquals("2499.99", avroItem.getPrice());
    }

    @Test
    void toAvro_shouldHandleReasonField() {
        // Given
        OrderEvent event = new OrderEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(), new BigDecimal("100"),
                OrderStatus.FAILED,
                "Insufficient stock",
                Instant.now()
        );

        // When
        OrderEventAvro avro = OrderEventMapper.toAvro(event, "inventory-service");

        // Then
        assertEquals("Insufficient stock", avro.getReason());
        assertEquals(OrderStatusAvro.FAILED, avro.getStatus());
        assertEquals("inventory-service", avro.getSource());
    }

    @Test
    void fromAvro_shouldConvertAllFieldsCorrectly() {
        // Given
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2024-01-15T10:30:00Z");

        OrderEventAvro avro = OrderEventAvro.newBuilder()
                .setEventId(eventId.toString())
                .setOrderId(orderId.toString())
                .setCustomerId(customerId.toString())
                .setItems(List.of(OrderItemAvro.newBuilder()
                        .setProductId(productId.toString())
                        .setProductName("iPhone 15")
                        .setQuantity(1)
                        .setPrice("1199.00")
                        .build()))
                .setTotalAmount("1199.00")
                .setStatus(OrderStatusAvro.VALIDATED)
                .setReason(null)
                .setCreatedAt(createdAt.toString())
                .setSchemaVersion(1)
                .setSource("inventory-service")
                .build();

        // When
        OrderEvent event = OrderEventMapper.fromAvro(avro);

        // Then
        assertEquals(eventId, event.eventId());
        assertEquals(orderId, event.orderId());
        assertEquals(customerId, event.customerId());
        assertEquals(new BigDecimal("1199.00"), event.totalAmount());
        assertEquals(OrderStatus.VALIDATED, event.status());
        assertNull(event.reason());
        assertEquals(createdAt, event.createdAt());

        // Verify items
        assertEquals(1, event.items().size());
        OrderEvent.OrderItem item = event.items().get(0);
        assertEquals(productId, item.productId());
        assertEquals("iPhone 15", item.productName());
        assertEquals(1, item.quantity());
        assertEquals(new BigDecimal("1199.00"), item.price());
    }

    @Test
    void roundTrip_shouldPreserveAllData() {
        // Given — original event
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();
        Instant createdAt = Instant.now();

        OrderEvent original = new OrderEvent(
                eventId, orderId, customerId,
                List.of(
                        new OrderEvent.OrderItem(productId1, "Product A", 3, new BigDecimal("99.99")),
                        new OrderEvent.OrderItem(productId2, "Product B", 1, new BigDecimal("49.50"))
                ),
                new BigDecimal("349.47"),
                OrderStatus.PAID,
                null,
                createdAt
        );

        // When — round-trip: OrderEvent -> Avro -> OrderEvent
        OrderEventAvro avro = OrderEventMapper.toAvro(original, "payment-service");
        OrderEvent restored = OrderEventMapper.fromAvro(avro);

        // Then — all business fields preserved
        assertEquals(original.eventId(), restored.eventId());
        assertEquals(original.orderId(), restored.orderId());
        assertEquals(original.customerId(), restored.customerId());
        assertEquals(original.totalAmount(), restored.totalAmount());
        assertEquals(original.status(), restored.status());
        assertEquals(original.reason(), restored.reason());
        assertEquals(original.createdAt(), restored.createdAt());
        assertEquals(original.items().size(), restored.items().size());

        for (int i = 0; i < original.items().size(); i++) {
            assertEquals(original.items().get(i).productId(), restored.items().get(i).productId());
            assertEquals(original.items().get(i).productName(), restored.items().get(i).productName());
            assertEquals(original.items().get(i).quantity(), restored.items().get(i).quantity());
            assertEquals(original.items().get(i).price(), restored.items().get(i).price());
        }
    }

    @Test
    void toAvro_shouldMapAllStatusValues() {
        for (OrderStatus status : OrderStatus.values()) {
            OrderEvent event = new OrderEvent(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    List.of(), BigDecimal.ZERO, status, null, Instant.now()
            );

            OrderEventAvro avro = OrderEventMapper.toAvro(event, "test");
            assertEquals(status.name(), avro.getStatus().name());
        }
    }

    @Test
    void toAvro_shouldHandleMultipleItems() {
        // Given
        List<OrderEvent.OrderItem> items = List.of(
                new OrderEvent.OrderItem(UUID.randomUUID(), "Item 1", 1, new BigDecimal("10.00")),
                new OrderEvent.OrderItem(UUID.randomUUID(), "Item 2", 2, new BigDecimal("20.00")),
                new OrderEvent.OrderItem(UUID.randomUUID(), "Item 3", 3, new BigDecimal("30.00"))
        );

        OrderEvent event = new OrderEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                items, new BigDecimal("140.00"),
                OrderStatus.PLACED, null, Instant.now()
        );

        // When
        OrderEventAvro avro = OrderEventMapper.toAvro(event, "test");

        // Then
        assertEquals(3, avro.getItems().size());
    }
}
