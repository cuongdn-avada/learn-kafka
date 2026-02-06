package dnc.cuong.inventory.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dead Letter Topic consumer cho Inventory Service.
 *
 * Consume messages từ DLT topics khi consumer chính fail sau tất cả retries.
 * Hiện tại chỉ log để monitoring — có thể mở rộng thêm replay logic.
 */
@Component
@Slf4j
public class DltKafkaConsumer {

    @KafkaListener(
            topics = KafkaTopics.ORDER_PLACED + KafkaTopics.DLT_SUFFIX,
            groupId = "inventory-service-dlt-group"
    )
    public void onOrderPlacedDlt(OrderEvent event) {
        log.error("[DLT] Failed to process order.placed | orderId={} | eventId={} | status={}",
                event.orderId(), event.eventId(), event.status());
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED + KafkaTopics.DLT_SUFFIX,
            groupId = "inventory-service-dlt-group"
    )
    public void onPaymentFailedDlt(OrderEvent event) {
        log.error("[DLT] Failed to process payment.failed | orderId={} | eventId={} | status={}",
                event.orderId(), event.eventId(), event.status());
    }
}
