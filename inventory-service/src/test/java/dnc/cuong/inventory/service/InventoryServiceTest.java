package dnc.cuong.inventory.service;

import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderStatus;
import dnc.cuong.inventory.domain.ProcessedEvent;
import dnc.cuong.inventory.domain.ProcessedEventRepository;
import dnc.cuong.inventory.domain.Product;
import dnc.cuong.inventory.domain.ProductRepository;
import dnc.cuong.inventory.kafka.InventoryKafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test cho InventoryService — test stock validation, reservation, và compensation.
 *
 * WHY test InventoryService kỹ?
 * -> Stock management là critical path — sai stock = mất tiền hoặc oversell.
 * -> Test cả happy path (stock đủ), failure path (stock thiếu), và compensation (release).
 * -> Idempotency behavior: duplicate event phải bị skip.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private InventoryKafkaProducer kafkaProducer;

    @InjectMocks
    private InventoryService inventoryService;

    // --- processOrderPlaced: Happy path ---

    @Test
    void processOrderPlaced_shouldReserveStockAndPublishValidated() {
        // Given
        UUID productId = UUID.randomUUID();
        Product product = Product.builder()
                .id(productId).name("MacBook Pro").skuCode("MBP-14")
                .availableQuantity(50).reservedQuantity(0).build();

        OrderEvent event = createOrderPlacedEvent(productId, 3);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(productRepository.findAllByIdIn(List.of(productId))).thenReturn(List.of(product));

        // When
        inventoryService.processOrderPlaced(event);

        // Then — stock reserved
        assertEquals(47, product.getAvailableQuantity());
        assertEquals(3, product.getReservedQuantity());

        // Idempotency record saved
        verify(processedEventRepository).save(any(ProcessedEvent.class));

        // order.validated event published
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaProducer).sendOrderValidated(eventCaptor.capture());
        assertEquals(OrderStatus.VALIDATED, eventCaptor.getValue().status());
    }

    @Test
    void processOrderPlaced_shouldHandleMultipleItems() {
        // Given
        UUID productId1 = UUID.randomUUID();
        UUID productId2 = UUID.randomUUID();

        Product product1 = Product.builder()
                .id(productId1).name("MacBook").skuCode("MBP")
                .availableQuantity(50).reservedQuantity(0).build();
        Product product2 = Product.builder()
                .id(productId2).name("Mouse").skuCode("MM")
                .availableQuantity(100).reservedQuantity(0).build();

        OrderEvent event = createMultiItemEvent(productId1, 2, productId2, 5);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(productRepository.findAllByIdIn(List.of(productId1, productId2)))
                .thenReturn(List.of(product1, product2));

        // When
        inventoryService.processOrderPlaced(event);

        // Then
        assertEquals(48, product1.getAvailableQuantity());
        assertEquals(2, product1.getReservedQuantity());
        assertEquals(95, product2.getAvailableQuantity());
        assertEquals(5, product2.getReservedQuantity());

        verify(kafkaProducer).sendOrderValidated(any(OrderEvent.class));
    }

    // --- processOrderPlaced: Failure paths ---

    @Test
    void processOrderPlaced_shouldPublishFailed_whenProductNotFound() {
        // Given
        UUID unknownProductId = UUID.randomUUID();
        OrderEvent event = createOrderPlacedEvent(unknownProductId, 1);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(productRepository.findAllByIdIn(List.of(unknownProductId))).thenReturn(List.of());

        // When
        inventoryService.processOrderPlaced(event);

        // Then — order.failed published with reason
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaProducer).sendOrderFailed(eventCaptor.capture());

        OrderEvent failedEvent = eventCaptor.getValue();
        assertEquals(OrderStatus.FAILED, failedEvent.status());
        assertTrue(failedEvent.reason().contains("Product not found"));

        // No order.validated published
        verify(kafkaProducer, never()).sendOrderValidated(any());

        // Idempotency still saved for failure path
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void processOrderPlaced_shouldPublishFailed_whenInsufficientStock() {
        // Given
        UUID productId = UUID.randomUUID();
        Product product = Product.builder()
                .id(productId).name("Apple Watch Ultra").skuCode("AWU")
                .availableQuantity(0).reservedQuantity(0).build();

        OrderEvent event = createOrderPlacedEvent(productId, 5);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(productRepository.findAllByIdIn(List.of(productId))).thenReturn(List.of(product));

        // When
        inventoryService.processOrderPlaced(event);

        // Then
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaProducer).sendOrderFailed(eventCaptor.capture());

        assertTrue(eventCaptor.getValue().reason().contains("Insufficient stock"));
        // Stock unchanged
        assertEquals(0, product.getAvailableQuantity());
        assertEquals(0, product.getReservedQuantity());
    }

    // --- processOrderPlaced: Idempotency ---

    @Test
    void processOrderPlaced_shouldSkipDuplicateEvent() {
        // Given
        OrderEvent event = createOrderPlacedEvent(UUID.randomUUID(), 1);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        // When
        inventoryService.processOrderPlaced(event);

        // Then — nothing happens
        verify(productRepository, never()).findAllByIdIn(any());
        verify(kafkaProducer, never()).sendOrderValidated(any());
        verify(kafkaProducer, never()).sendOrderFailed(any());
    }

    // --- compensateReservation ---

    @Test
    void compensateReservation_shouldReleaseStock() {
        // Given
        UUID productId = UUID.randomUUID();
        Product product = Product.builder()
                .id(productId).name("MacBook Pro").skuCode("MBP-14")
                .availableQuantity(47).reservedQuantity(3).build();

        OrderEvent event = createPaymentFailedEvent(productId, 3);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(productRepository.findAllByIdIn(List.of(productId))).thenReturn(List.of(product));

        // When
        inventoryService.compensateReservation(event);

        // Then — stock restored
        assertEquals(50, product.getAvailableQuantity());
        assertEquals(0, product.getReservedQuantity());
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void compensateReservation_shouldSkipMissingProduct() {
        // Given — product not found (deleted or data inconsistency)
        UUID missingProductId = UUID.randomUUID();
        OrderEvent event = createPaymentFailedEvent(missingProductId, 1);

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(productRepository.findAllByIdIn(List.of(missingProductId))).thenReturn(List.of());

        // When — should not throw
        assertDoesNotThrow(() -> inventoryService.compensateReservation(event));

        // ProcessedEvent still saved
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void compensateReservation_shouldSkipDuplicateEvent() {
        // Given
        OrderEvent event = createPaymentFailedEvent(UUID.randomUUID(), 1);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        // When
        inventoryService.compensateReservation(event);

        // Then
        verify(productRepository, never()).findAllByIdIn(any());
    }

    // --- Helpers ---

    private OrderEvent createOrderPlacedEvent(UUID productId, int quantity) {
        return new OrderEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new OrderEvent.OrderItem(productId, "Test Product", quantity, new BigDecimal("100.00"))),
                new BigDecimal("100.00").multiply(BigDecimal.valueOf(quantity)),
                OrderStatus.PLACED, null, Instant.now()
        );
    }

    private OrderEvent createMultiItemEvent(UUID pid1, int qty1, UUID pid2, int qty2) {
        return new OrderEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(
                        new OrderEvent.OrderItem(pid1, "Product 1", qty1, new BigDecimal("2000.00")),
                        new OrderEvent.OrderItem(pid2, "Product 2", qty2, new BigDecimal("100.00"))
                ),
                new BigDecimal("4500.00"),
                OrderStatus.PLACED, null, Instant.now()
        );
    }

    private OrderEvent createPaymentFailedEvent(UUID productId, int quantity) {
        return new OrderEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new OrderEvent.OrderItem(productId, "Test Product", quantity, new BigDecimal("100.00"))),
                new BigDecimal("15000.00"),
                OrderStatus.PAYMENT_FAILED, "Payment declined", Instant.now()
        );
    }
}
