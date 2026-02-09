package dnc.cuong.order.service;

import dnc.cuong.common.dto.OrderCreateRequest;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderStatus;
import dnc.cuong.order.domain.Order;
import dnc.cuong.order.domain.OrderRepository;
import dnc.cuong.order.domain.ProcessedEvent;
import dnc.cuong.order.domain.ProcessedEventRepository;
import dnc.cuong.order.kafka.OrderKafkaProducer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test cho OrderService — mock tất cả dependencies.
 *
 * WHY dùng @ExtendWith(MockitoExtension) thay vì @SpringBootTest?
 * -> Không cần Spring context → test chạy trong milliseconds.
 * -> Mock DB repositories + Kafka producer → isolate business logic.
 * -> Focus test đúng service layer behavior, không test infrastructure.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OrderKafkaProducer kafkaProducer;

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService.initMetrics();
    }

    // --- createOrder ---

    @Test
    void createOrder_shouldCalculateTotalAmountAndSave() {
        // Given
        UUID customerId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(customerId, List.of(
                new OrderCreateRequest.OrderItemRequest(UUID.randomUUID(), "MacBook Pro", 2, new BigDecimal("2499.99")),
                new OrderCreateRequest.OrderItemRequest(UUID.randomUUID(), "Magic Mouse", 1, new BigDecimal("99.99"))
        ));

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(UUID.randomUUID());
            return order;
        });

        // When
        Order result = orderService.createOrder(request);

        // Then
        assertNotNull(result);
        assertEquals(customerId, result.getCustomerId());
        assertEquals(OrderStatus.PLACED, result.getStatus());
        // 2 * 2499.99 + 1 * 99.99 = 5099.97
        assertEquals(new BigDecimal("5099.97"), result.getTotalAmount());
        assertEquals(2, result.getOrderItems().size());

        verify(orderRepository).save(any(Order.class));
        verify(kafkaProducer).sendOrderPlaced(any(OrderEvent.class));
    }

    @Test
    void createOrder_shouldPublishOrderPlacedEvent() {
        // Given
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(customerId, List.of(
                new OrderCreateRequest.OrderItemRequest(productId, "iPhone 15", 1, new BigDecimal("1199.00"))
        ));

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(UUID.randomUUID());
            return order;
        });

        // When
        orderService.createOrder(request);

        // Then — verify event published with correct data
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaProducer).sendOrderPlaced(eventCaptor.capture());

        OrderEvent event = eventCaptor.getValue();
        assertEquals(customerId, event.customerId());
        assertEquals(OrderStatus.PLACED, event.status());
        assertEquals(new BigDecimal("1199.00"), event.totalAmount());
        assertNotNull(event.eventId());
        assertNotNull(event.createdAt());
    }

    // --- completeOrder ---

    @Test
    void completeOrder_shouldUpdateStatusToCompleted() {
        // Given
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().id(orderId).status(OrderStatus.PAID).build();
        OrderEvent event = createEvent(orderId, OrderStatus.PAID, null);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // When
        orderService.completeOrder(event);

        // Then
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(kafkaProducer).sendOrderCompleted(any(OrderEvent.class));
    }

    @Test
    void completeOrder_shouldSkipDuplicateEvent() {
        // Given
        OrderEvent event = createEvent(UUID.randomUUID(), OrderStatus.PAID, null);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        // When
        orderService.completeOrder(event);

        // Then — no DB update, no Kafka publish
        verify(orderRepository, never()).findById(any());
        verify(kafkaProducer, never()).sendOrderCompleted(any());
    }

    @Test
    void completeOrder_shouldThrow_whenOrderNotFound() {
        // Given
        UUID orderId = UUID.randomUUID();
        OrderEvent event = createEvent(orderId, OrderStatus.PAID, null);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // When / Then
        assertThrows(OrderNotFoundException.class, () -> orderService.completeOrder(event));
    }

    // --- failOrder ---

    @Test
    void failOrder_shouldUpdateStatusToFailed() {
        // Given
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().id(orderId).status(OrderStatus.PLACED).build();
        OrderEvent event = createEvent(orderId, OrderStatus.FAILED, "Insufficient stock");

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // When
        orderService.failOrder(event);

        // Then
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals("Insufficient stock", order.getFailureReason());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void failOrder_shouldSkipDuplicateEvent() {
        // Given
        OrderEvent event = createEvent(UUID.randomUUID(), OrderStatus.FAILED, "reason");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        // When
        orderService.failOrder(event);

        // Then
        verify(orderRepository, never()).findById(any());
    }

    // --- handlePaymentFailure ---

    @Test
    void handlePaymentFailure_shouldUpdateStatusToPaymentFailed() {
        // Given
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().id(orderId).status(OrderStatus.VALIDATED).build();
        String reason = "Payment declined: amount 15000 exceeds limit 10000";
        OrderEvent event = createEvent(orderId, OrderStatus.PAYMENT_FAILED, reason);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // When
        orderService.handlePaymentFailure(event);

        // Then
        assertEquals(OrderStatus.PAYMENT_FAILED, order.getStatus());
        assertEquals(reason, order.getFailureReason());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void handlePaymentFailure_shouldSkipDuplicateEvent() {
        // Given
        OrderEvent event = createEvent(UUID.randomUUID(), OrderStatus.PAYMENT_FAILED, "reason");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        // When
        orderService.handlePaymentFailure(event);

        // Then
        verify(orderRepository, never()).findById(any());
    }

    // --- getOrder ---

    @Test
    void getOrder_shouldReturnOrder_whenExists() {
        // Given
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().id(orderId).status(OrderStatus.PLACED).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // When
        Order result = orderService.getOrder(orderId);

        // Then
        assertEquals(orderId, result.getId());
    }

    @Test
    void getOrder_shouldThrow_whenNotFound() {
        // Given
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // When / Then
        assertThrows(OrderNotFoundException.class, () -> orderService.getOrder(orderId));
    }

    // --- getOrdersByCustomer ---

    @Test
    void getOrdersByCustomer_shouldReturnOrders() {
        // Given
        UUID customerId = UUID.randomUUID();
        List<Order> orders = List.of(
                Order.builder().customerId(customerId).status(OrderStatus.PLACED).build(),
                Order.builder().customerId(customerId).status(OrderStatus.COMPLETED).build()
        );
        when(orderRepository.findByCustomerId(customerId)).thenReturn(orders);

        // When
        List<Order> result = orderService.getOrdersByCustomer(customerId);

        // Then
        assertEquals(2, result.size());
    }

    // --- Helper ---

    private OrderEvent createEvent(UUID orderId, OrderStatus status, String reason) {
        UUID eventId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        List<OrderEvent.OrderItem> items = List.of(
                new OrderEvent.OrderItem(UUID.randomUUID(), "Test Product", 1, new BigDecimal("100.00"))
        );

        if (reason != null) {
            return new OrderEvent(eventId, orderId, customerId, items,
                    new BigDecimal("100.00"), status, reason, java.time.Instant.now());
        }
        return new OrderEvent(eventId, orderId, customerId, items,
                new BigDecimal("100.00"), status, null, java.time.Instant.now());
    }
}
