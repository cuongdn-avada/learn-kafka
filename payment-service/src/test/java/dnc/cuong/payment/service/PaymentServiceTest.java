package dnc.cuong.payment.service;

import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderStatus;
import dnc.cuong.payment.domain.Payment;
import dnc.cuong.payment.domain.PaymentRepository;
import dnc.cuong.payment.domain.PaymentStatus;
import dnc.cuong.payment.domain.ProcessedEvent;
import dnc.cuong.payment.domain.ProcessedEventRepository;
import dnc.cuong.payment.kafka.PaymentKafkaProducer;
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
 * Unit test cho PaymentService — test payment threshold logic.
 *
 * Business rule:
 * - totalAmount <= 10,000 → SUCCESS → publish order.paid
 * - totalAmount > 10,000  → FAILED  → publish payment.failed
 *
 * WHY test threshold boundary?
 * -> Deterministic rule cho phép test chính xác cả happy và failure path.
 * -> Boundary testing: 10000 (success), 10000.01 (fail).
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private PaymentKafkaProducer kafkaProducer;

    @InjectMocks
    private PaymentService paymentService;

    // --- Happy path: amount <= 10,000 ---

    @Test
    void processOrderValidated_shouldSucceed_whenAmountBelowThreshold() {
        // Given
        OrderEvent event = createValidatedEvent(new BigDecimal("5000.00"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        // When
        paymentService.processOrderValidated(event);

        // Then — Payment SUCCESS saved
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());

        Payment savedPayment = paymentCaptor.getValue();
        assertEquals(event.orderId(), savedPayment.getOrderId());
        assertEquals(event.customerId(), savedPayment.getCustomerId());
        assertEquals(new BigDecimal("5000.00"), savedPayment.getAmount());
        assertEquals(PaymentStatus.SUCCESS, savedPayment.getStatus());
        assertNull(savedPayment.getFailureReason());

        // order.paid published
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaProducer).sendOrderPaid(eventCaptor.capture());
        assertEquals(OrderStatus.PAID, eventCaptor.getValue().status());

        // Idempotency saved
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void processOrderValidated_shouldSucceed_whenAmountExactlyAtThreshold() {
        // Given — boundary: exactly 10000 should succeed
        OrderEvent event = createValidatedEvent(new BigDecimal("10000"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        // When
        paymentService.processOrderValidated(event);

        // Then
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertEquals(PaymentStatus.SUCCESS, paymentCaptor.getValue().getStatus());

        verify(kafkaProducer).sendOrderPaid(any(OrderEvent.class));
        verify(kafkaProducer, never()).sendPaymentFailed(any());
    }

    // --- Failure path: amount > 10,000 ---

    @Test
    void processOrderValidated_shouldFail_whenAmountExceedsThreshold() {
        // Given
        OrderEvent event = createValidatedEvent(new BigDecimal("15000.00"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        // When
        paymentService.processOrderValidated(event);

        // Then — Payment FAILED saved
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());

        Payment savedPayment = paymentCaptor.getValue();
        assertEquals(PaymentStatus.FAILED, savedPayment.getStatus());
        assertTrue(savedPayment.getFailureReason().contains("exceeds limit"));

        // payment.failed published → trigger compensation
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaProducer).sendPaymentFailed(eventCaptor.capture());

        OrderEvent failedEvent = eventCaptor.getValue();
        assertEquals(OrderStatus.PAYMENT_FAILED, failedEvent.status());
        assertTrue(failedEvent.reason().contains("exceeds limit"));

        // order.paid NOT published
        verify(kafkaProducer, never()).sendOrderPaid(any());
    }

    @Test
    void processOrderValidated_shouldFail_whenAmountJustAboveThreshold() {
        // Given — boundary: 10000.01 should fail
        OrderEvent event = createValidatedEvent(new BigDecimal("10000.01"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        // When
        paymentService.processOrderValidated(event);

        // Then
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertEquals(PaymentStatus.FAILED, paymentCaptor.getValue().getStatus());

        verify(kafkaProducer).sendPaymentFailed(any(OrderEvent.class));
        verify(kafkaProducer, never()).sendOrderPaid(any());
    }

    // --- Idempotency ---

    @Test
    void processOrderValidated_shouldSkipDuplicateEvent() {
        // Given
        OrderEvent event = createValidatedEvent(new BigDecimal("100.00"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        // When
        paymentService.processOrderValidated(event);

        // Then — nothing happens
        verify(paymentRepository, never()).save(any());
        verify(kafkaProducer, never()).sendOrderPaid(any());
        verify(kafkaProducer, never()).sendPaymentFailed(any());
    }

    // --- Edge cases ---

    @Test
    void processOrderValidated_shouldSucceed_whenAmountIsZero() {
        // Given
        OrderEvent event = createValidatedEvent(BigDecimal.ZERO);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        // When
        paymentService.processOrderValidated(event);

        // Then
        verify(kafkaProducer).sendOrderPaid(any(OrderEvent.class));
    }

    @Test
    void processOrderValidated_shouldPreserveEventData() {
        // Given
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderEvent event = new OrderEvent(
                UUID.randomUUID(), orderId, customerId,
                List.of(new OrderEvent.OrderItem(productId, "MacBook", 1, new BigDecimal("2000.00"))),
                new BigDecimal("2000.00"), OrderStatus.VALIDATED, null, Instant.now()
        );
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        // When
        paymentService.processOrderValidated(event);

        // Then — event data preserved in published message
        ArgumentCaptor<OrderEvent> eventCaptor = ArgumentCaptor.forClass(OrderEvent.class);
        verify(kafkaProducer).sendOrderPaid(eventCaptor.capture());

        OrderEvent paidEvent = eventCaptor.getValue();
        assertEquals(orderId, paidEvent.orderId());
        assertEquals(customerId, paidEvent.customerId());
        assertEquals(1, paidEvent.items().size());
        assertEquals(new BigDecimal("2000.00"), paidEvent.totalAmount());
    }

    // --- Helper ---

    private OrderEvent createValidatedEvent(BigDecimal amount) {
        return new OrderEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new OrderEvent.OrderItem(UUID.randomUUID(), "Test Product", 1, amount)),
                amount, OrderStatus.VALIDATED, null, Instant.now()
        );
    }
}
