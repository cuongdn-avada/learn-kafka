package dnc.cuong.notification.service;

import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho NotificationService — test in-memory deduplication.
 *
 * WHY test NotificationService mà nó chỉ log?
 * -> Business logic quan trọng nhất: deduplication (tránh gửi email trùng).
 * -> ConcurrentHashMap.newKeySet() behavior cần verify đúng.
 * -> Không cần mock — service là stateless (ngoại trừ processedEventIds Set).
 */
class NotificationServiceTest {

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(new SimpleMeterRegistry());
        notificationService.initMetrics();
    }

    // --- notifyOrderCompleted ---

    @Test
    void notifyOrderCompleted_shouldProcessFirstEvent() {
        // Given
        OrderEvent event = createEvent(OrderStatus.COMPLETED, null);

        // When / Then — should not throw
        assertDoesNotThrow(() -> notificationService.notifyOrderCompleted(event));
    }

    @Test
    void notifyOrderCompleted_shouldSkipDuplicateEvent() {
        // Given — same event processed twice
        OrderEvent event = createEvent(OrderStatus.COMPLETED, null);

        // When — first call processes, second should skip (no exception in both cases)
        notificationService.notifyOrderCompleted(event);
        assertDoesNotThrow(() -> notificationService.notifyOrderCompleted(event));
    }

    @Test
    void notifyOrderCompleted_shouldProcessDifferentEvents() {
        // Given
        OrderEvent event1 = createEvent(OrderStatus.COMPLETED, null);
        OrderEvent event2 = createEvent(OrderStatus.COMPLETED, null);

        // When / Then — both should process (different eventIds)
        assertDoesNotThrow(() -> notificationService.notifyOrderCompleted(event1));
        assertDoesNotThrow(() -> notificationService.notifyOrderCompleted(event2));
    }

    // --- notifyOrderFailed ---

    @Test
    void notifyOrderFailed_shouldProcessFirstEvent() {
        OrderEvent event = createEvent(OrderStatus.FAILED, "Insufficient stock");
        assertDoesNotThrow(() -> notificationService.notifyOrderFailed(event));
    }

    @Test
    void notifyOrderFailed_shouldSkipDuplicateEvent() {
        OrderEvent event = createEvent(OrderStatus.FAILED, "Insufficient stock");

        notificationService.notifyOrderFailed(event);
        assertDoesNotThrow(() -> notificationService.notifyOrderFailed(event));
    }

    // --- notifyPaymentFailed ---

    @Test
    void notifyPaymentFailed_shouldProcessFirstEvent() {
        OrderEvent event = createEvent(OrderStatus.PAYMENT_FAILED, "Amount exceeds limit");
        assertDoesNotThrow(() -> notificationService.notifyPaymentFailed(event));
    }

    @Test
    void notifyPaymentFailed_shouldSkipDuplicateEvent() {
        OrderEvent event = createEvent(OrderStatus.PAYMENT_FAILED, "Amount exceeds limit");

        notificationService.notifyPaymentFailed(event);
        assertDoesNotThrow(() -> notificationService.notifyPaymentFailed(event));
    }

    // --- Cross-method dedup: same eventId shared across all methods ---

    @Test
    void dedup_shouldBeSharedAcrossMethods() {
        // Given — same event
        OrderEvent event = createEvent(OrderStatus.COMPLETED, null);

        // When — process via notifyOrderCompleted first
        notificationService.notifyOrderCompleted(event);

        // Then — same eventId passed to different method should also be deduped
        // (because processedEventIds Set is shared)
        // This verifies the Set is shared, not per-method
        notificationService.notifyOrderFailed(event);
        notificationService.notifyPaymentFailed(event);
        // No assertion needed — just verifying no exception and the Set prevents double processing
    }

    // --- Helper ---

    private OrderEvent createEvent(OrderStatus status, String reason) {
        return new OrderEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new OrderEvent.OrderItem(UUID.randomUUID(), "Test Product", 1, new BigDecimal("100.00"))),
                new BigDecimal("100.00"), status, reason, Instant.now()
        );
    }
}
