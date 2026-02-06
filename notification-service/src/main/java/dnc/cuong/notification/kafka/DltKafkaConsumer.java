package dnc.cuong.notification.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dead Letter Topic consumer cho Notification Service.
 *
 * Consume messages từ DLT topics khi consumer chính fail sau tất cả retries.
 */
@Component
@Slf4j
public class DltKafkaConsumer {

    @KafkaListener(
            topics = KafkaTopics.ORDER_COMPLETED + KafkaTopics.DLT_SUFFIX,
            groupId = "notification-service-dlt-group"
    )
    public void onOrderCompletedDlt(OrderEvent event) {
        log.error("[DLT] Failed to process order.completed | orderId={} | eventId={} | status={}",
                event.orderId(), event.eventId(), event.status());
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_FAILED + KafkaTopics.DLT_SUFFIX,
            groupId = "notification-service-dlt-group"
    )
    public void onOrderFailedDlt(OrderEvent event) {
        log.error("[DLT] Failed to process order.failed | orderId={} | eventId={} | status={}",
                event.orderId(), event.eventId(), event.status());
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED + KafkaTopics.DLT_SUFFIX,
            groupId = "notification-service-dlt-group"
    )
    public void onPaymentFailedDlt(OrderEvent event) {
        log.error("[DLT] Failed to process payment.failed | orderId={} | eventId={} | status={}",
                event.orderId(), event.eventId(), event.status());
    }
}
