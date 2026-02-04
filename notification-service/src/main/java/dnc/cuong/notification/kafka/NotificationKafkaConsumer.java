package dnc.cuong.notification.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer cho Notification Service.
 *
 * Consume 3 topics — tất cả "terminal state" của Saga:
 * - order.completed → thông báo thành công
 * - order.failed → thông báo stock thiếu
 * - payment.failed → thông báo payment thất bại
 *
 * WHY group-id khác nhau giữa các service?
 * → Mỗi service có group-id riêng → tất cả đều nhận message.
 * → Ví dụ: payment.failed được cả 3 service nhận:
 *   - inventory-service-group → release stock
 *   - order-service-group → update status
 *   - notification-service-group → send notification
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationKafkaConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics = KafkaTopics.ORDER_COMPLETED,
            groupId = "notification-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderCompleted(OrderEvent event) {
        log.info("Received event from [{}] | eventId={} | orderId={}",
                KafkaTopics.ORDER_COMPLETED, event.eventId(), event.orderId());

        notificationService.notifyOrderCompleted(event);
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_FAILED,
            groupId = "notification-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderFailed(OrderEvent event) {
        log.info("Received event from [{}] | eventId={} | orderId={}",
                KafkaTopics.ORDER_FAILED, event.eventId(), event.orderId());

        notificationService.notifyOrderFailed(event);
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED,
            groupId = "notification-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentFailed(OrderEvent event) {
        log.info("Received event from [{}] | eventId={} | orderId={}",
                KafkaTopics.PAYMENT_FAILED, event.eventId(), event.orderId());

        notificationService.notifyPaymentFailed(event);
    }
}
