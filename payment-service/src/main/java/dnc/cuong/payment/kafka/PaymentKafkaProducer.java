package dnc.cuong.payment.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Producer publish kết quả payment lên Kafka.
 *
 * Publish 2 loại event:
 * - order.paid: payment thành công → Order Service cập nhật COMPLETED
 * - payment.failed: payment thất bại → trigger compensation (Inventory release stock)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentKafkaProducer {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    public CompletableFuture<SendResult<String, OrderEvent>> sendOrderPaid(OrderEvent event) {
        String key = event.orderId().toString();

        log.info("Publishing event to [{}] | key={} | eventId={} | status={}",
                KafkaTopics.ORDER_PAID, key, event.eventId(), event.status());

        CompletableFuture<SendResult<String, OrderEvent>> future =
                kafkaTemplate.send(KafkaTopics.ORDER_PAID, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("FAILED to publish event to [{}] | key={} | eventId={} | error={}",
                        KafkaTopics.ORDER_PAID, key, event.eventId(), ex.getMessage(), ex);
            } else {
                var metadata = result.getRecordMetadata();
                log.info("SUCCESS published to [{}] | partition={} | offset={} | key={}",
                        metadata.topic(), metadata.partition(), metadata.offset(), key);
            }
        });

        return future;
    }

    public CompletableFuture<SendResult<String, OrderEvent>> sendPaymentFailed(OrderEvent event) {
        String key = event.orderId().toString();

        log.info("Publishing event to [{}] | key={} | eventId={} | status={}",
                KafkaTopics.PAYMENT_FAILED, key, event.eventId(), event.status());

        CompletableFuture<SendResult<String, OrderEvent>> future =
                kafkaTemplate.send(KafkaTopics.PAYMENT_FAILED, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("FAILED to publish event to [{}] | key={} | eventId={} | error={}",
                        KafkaTopics.PAYMENT_FAILED, key, event.eventId(), ex.getMessage(), ex);
            } else {
                var metadata = result.getRecordMetadata();
                log.info("SUCCESS published to [{}] | partition={} | offset={} | key={}",
                        metadata.topic(), metadata.partition(), metadata.offset(), key);
            }
        });

        return future;
    }
}
