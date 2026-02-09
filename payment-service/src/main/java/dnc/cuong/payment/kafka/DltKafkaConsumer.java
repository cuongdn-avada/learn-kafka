package dnc.cuong.payment.kafka;

import dnc.cuong.common.avro.OrderEventAvro;
import dnc.cuong.common.event.KafkaTopics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Dead Letter Topic consumer cho Payment Service.
 *
 * Consume messages từ DLT topics khi consumer chính fail sau tất cả retries.
 */
@Component
@Slf4j
public class DltKafkaConsumer {

    @KafkaListener(
            topics = KafkaTopics.ORDER_VALIDATED + KafkaTopics.DLT_SUFFIX,
            groupId = "payment-service-dlt-group"
    )
    public void onOrderValidatedDlt(OrderEventAvro event) {
        log.error("[DLT] Failed to process order.validated | orderId={} | eventId={} | status={}",
                event.getOrderId(), event.getEventId(), event.getStatus());
    }
}
