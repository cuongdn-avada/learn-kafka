package dnc.cuong.notification.service;

import dnc.cuong.common.event.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notification logic — simulate gửi email/push notification.
 *
 * Stateless service, không cần database.
 * Trong production: tích hợp SendGrid, Firebase, SNS, etc.
 * Hiện tại: log ra console để verify Saga flow hoạt động đúng.
 *
 * WHY dùng in-memory Set cho idempotency thay vì DB?
 * → Notification Service không có database (stateless).
 * → ConcurrentHashMap.newKeySet() thread-safe, O(1) lookup.
 * → Trade-off: mất deduplication khi restart. Chấp nhận được vì:
 *   - Duplicate email < lost email (at-least-once tốt hơn at-most-once).
 *   - Production: dùng Redis SET hoặc thêm lightweight DB.
 */
@Service
@Slf4j
public class NotificationService {

    private final Set<UUID> processedEventIds = ConcurrentHashMap.newKeySet();

    public void notifyOrderCompleted(OrderEvent event) {
        if (!processedEventIds.add(event.eventId())) {
            log.warn("Duplicate notification skipped | eventId={} | orderId={} | type=ORDER_COMPLETED",
                    event.eventId(), event.orderId());
            return;
        }

        log.info("=== NOTIFICATION: ORDER COMPLETED ===");
        log.info("  Customer: {}", event.customerId());
        log.info("  Order: {}", event.orderId());
        log.info("  Amount: {}", event.totalAmount());
        log.info("  Items: {}", event.items().size());
        log.info("  → Email sent: Your order has been completed successfully!");
        log.info("=====================================");
    }

    public void notifyOrderFailed(OrderEvent event) {
        if (!processedEventIds.add(event.eventId())) {
            log.warn("Duplicate notification skipped | eventId={} | orderId={} | type=ORDER_FAILED",
                    event.eventId(), event.orderId());
            return;
        }

        log.info("=== NOTIFICATION: ORDER FAILED ===");
        log.info("  Customer: {}", event.customerId());
        log.info("  Order: {}", event.orderId());
        log.info("  Reason: {}", event.reason());
        log.info("  → Email sent: Sorry, your order could not be processed.");
        log.info("==================================");
    }

    public void notifyPaymentFailed(OrderEvent event) {
        if (!processedEventIds.add(event.eventId())) {
            log.warn("Duplicate notification skipped | eventId={} | orderId={} | type=PAYMENT_FAILED",
                    event.eventId(), event.orderId());
            return;
        }

        log.info("=== NOTIFICATION: PAYMENT FAILED ===");
        log.info("  Customer: {}", event.customerId());
        log.info("  Order: {}", event.orderId());
        log.info("  Amount: {}", event.totalAmount());
        log.info("  Reason: {}", event.reason());
        log.info("  → Email sent: Payment for your order has failed. Please try again.");
        log.info("====================================");
    }
}
