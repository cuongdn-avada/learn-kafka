package dnc.cuong.inventory.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Producer publish kết quả validation lên Kafka.
 *
 * WHY Inventory Service publish 2 topic khác nhau (validated vs failed)?
 * → Choreography pattern: downstream service chỉ listen topic nó quan tâm.
 * → Payment Service chỉ listen order.validated (không cần biết order.failed).
 * → Order Service và Notification Service listen order.failed để cập nhật trạng thái.
 * → Tách topic giúp decouple consumers, dễ scale independently.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryKafkaProducer {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    /**
     * Publish khi stock đủ → order đã validate thành công.
     * Consumer tiếp theo: Payment Service.
     */
    public CompletableFuture<SendResult<String, OrderEvent>> sendOrderValidated(OrderEvent event) {
        String key = event.orderId().toString();

        log.info("Publishing event to [{}] | key={} | eventId={} | status={}",
                KafkaTopics.ORDER_VALIDATED, key, event.eventId(), event.status());

        CompletableFuture<SendResult<String, OrderEvent>> future =
                kafkaTemplate.send(KafkaTopics.ORDER_VALIDATED, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("FAILED to publish event to [{}] | key={} | eventId={} | error={}",
                        KafkaTopics.ORDER_VALIDATED, key, event.eventId(), ex.getMessage(), ex);
            } else {
                var metadata = result.getRecordMetadata();
                log.info("SUCCESS published to [{}] | partition={} | offset={} | key={} | eventId={}",
                        metadata.topic(), metadata.partition(), metadata.offset(),
                        key, event.eventId());
            }
        });

        return future;
    }

    /**
     * Publish khi stock không đủ → order thất bại.
     * Consumer tiếp theo: Order Service (cập nhật FAILED), Notification Service.
     */
    public CompletableFuture<SendResult<String, OrderEvent>> sendOrderFailed(OrderEvent event) {
        String key = event.orderId().toString();

        log.info("Publishing event to [{}] | key={} | eventId={} | status={} | reason={}",
                KafkaTopics.ORDER_FAILED, key, event.eventId(), event.status(), event.reason());

        CompletableFuture<SendResult<String, OrderEvent>> future =
                kafkaTemplate.send(KafkaTopics.ORDER_FAILED, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("FAILED to publish event to [{}] | key={} | eventId={} | error={}",
                        KafkaTopics.ORDER_FAILED, key, event.eventId(), ex.getMessage(), ex);
            } else {
                var metadata = result.getRecordMetadata();
                log.info("SUCCESS published to [{}] | partition={} | offset={} | key={} | eventId={}",
                        metadata.topic(), metadata.partition(), metadata.offset(),
                        key, event.eventId());
            }
        });

        return future;
    }
}
