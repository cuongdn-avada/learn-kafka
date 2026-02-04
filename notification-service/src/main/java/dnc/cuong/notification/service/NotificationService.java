package dnc.cuong.notification.service;

import dnc.cuong.common.event.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Notification logic — simulate gửi email/push notification.
 *
 * Stateless service, không cần database.
 * Trong production: tích hợp SendGrid, Firebase, SNS, etc.
 * Hiện tại: log ra console để verify Saga flow hoạt động đúng.
 */
@Service
@Slf4j
public class NotificationService {

    public void notifyOrderCompleted(OrderEvent event) {
        log.info("=== NOTIFICATION: ORDER COMPLETED ===");
        log.info("  Customer: {}", event.customerId());
        log.info("  Order: {}", event.orderId());
        log.info("  Amount: {}", event.totalAmount());
        log.info("  Items: {}", event.items().size());
        log.info("  → Email sent: Your order has been completed successfully!");
        log.info("=====================================");
    }

    public void notifyOrderFailed(OrderEvent event) {
        log.info("=== NOTIFICATION: ORDER FAILED ===");
        log.info("  Customer: {}", event.customerId());
        log.info("  Order: {}", event.orderId());
        log.info("  Reason: {}", event.reason());
        log.info("  → Email sent: Sorry, your order could not be processed.");
        log.info("==================================");
    }

    public void notifyPaymentFailed(OrderEvent event) {
        log.info("=== NOTIFICATION: PAYMENT FAILED ===");
        log.info("  Customer: {}", event.customerId());
        log.info("  Order: {}", event.orderId());
        log.info("  Amount: {}", event.totalAmount());
        log.info("  Reason: {}", event.reason());
        log.info("  → Email sent: Payment for your order has failed. Please try again.");
        log.info("====================================");
    }
}
