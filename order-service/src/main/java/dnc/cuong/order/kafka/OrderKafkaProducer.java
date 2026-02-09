package dnc.cuong.order.kafka;

import dnc.cuong.common.avro.OrderEventAvro;
import dnc.cuong.common.avro.OrderEventMapper;
import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Producer chịu trách nhiệm publish OrderEvent lên Kafka (Avro format).
 *
 * WHY tách Producer thành class riêng thay vì inject KafkaTemplate trực tiếp vào Service?
 * → Single Responsibility — Producer chỉ lo việc gửi message.
 * → Encapsulate Avro conversion: Service layer dùng OrderEvent, Producer convert sang Avro.
 * → Dễ mock trong unit test (mock OrderKafkaProducer thay vì mock KafkaTemplate).
 *
 * WHY convert OrderEvent → OrderEventAvro ở đây?
 * → Service layer không cần biết về Avro — giữ business logic clean.
 * → Kafka layer chịu trách nhiệm serialization format.
 * → Nếu sau này đổi format (Protobuf, etc.), chỉ sửa Producer/Consumer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderKafkaProducer {

    private static final String SOURCE = "order-service";

    private final KafkaTemplate<String, OrderEventAvro> kafkaTemplate;

    public CompletableFuture<SendResult<String, OrderEventAvro>> sendOrderPlaced(OrderEvent event) {
        String key = event.orderId().toString();
        OrderEventAvro avroEvent = OrderEventMapper.toAvro(event, SOURCE);

        log.info("Publishing event to [{}] | key={} | eventId={} | status={}",
                KafkaTopics.ORDER_PLACED, key, event.eventId(), event.status());

        CompletableFuture<SendResult<String, OrderEventAvro>> future =
                kafkaTemplate.send(KafkaTopics.ORDER_PLACED, key, avroEvent);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("FAILED to publish event to [{}] | key={} | eventId={} | error={}",
                        KafkaTopics.ORDER_PLACED, key, event.eventId(), ex.getMessage(), ex);
            } else {
                var metadata = result.getRecordMetadata();
                log.info("SUCCESS published to [{}] | partition={} | offset={} | key={} | eventId={}",
                        metadata.topic(), metadata.partition(), metadata.offset(),
                        key, event.eventId());
            }
        });

        return future;
    }

    public CompletableFuture<SendResult<String, OrderEventAvro>> sendOrderCompleted(OrderEvent event) {
        String key = event.orderId().toString();
        OrderEventAvro avroEvent = OrderEventMapper.toAvro(event, SOURCE);

        log.info("Publishing event to [{}] | key={} | eventId={}", KafkaTopics.ORDER_COMPLETED, key, event.eventId());
        return kafkaTemplate.send(KafkaTopics.ORDER_COMPLETED, key, avroEvent);
    }
}
