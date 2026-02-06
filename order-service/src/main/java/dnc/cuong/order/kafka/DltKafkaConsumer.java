package dnc.cuong.order.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dead Letter Topic consumer cho Order Service.
 *
 * Consume messages từ DLT topics khi consumer chính fail sau tất cả retries.
 * Hiện tại chỉ log để monitoring — có thể mở rộng thêm:
 * - Alert (Slack, PagerDuty)
 * - Persist vào database cho manual replay
 * - Auto-retry sau khoảng thời gian dài hơn
 */
@Component
@Slf4j
public class DltKafkaConsumer {

    @KafkaListener(
            topics = KafkaTopics.ORDER_PAID + KafkaTopics.DLT_SUFFIX,
            groupId = "order-service-dlt-group"
    )
    public void onOrderPaidDlt(OrderEvent event) {
        log.error("[DLT] Failed to process order.paid | orderId={} | eventId={} | status={}",
                event.orderId(), event.eventId(), event.status());
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_FAILED + KafkaTopics.DLT_SUFFIX,
            groupId = "order-service-dlt-group"
    )
    public void onOrderFailedDlt(OrderEvent event) {
        log.error("[DLT] Failed to process order.failed | orderId={} | eventId={} | status={}",
                event.orderId(), event.eventId(), event.status());
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED + KafkaTopics.DLT_SUFFIX,
            groupId = "order-service-dlt-group"
    )
    public void onPaymentFailedDlt(OrderEvent event) {
        log.error("[DLT] Failed to process payment.failed | orderId={} | eventId={} | status={}",
                event.orderId(), event.eventId(), event.status());
    }
}
