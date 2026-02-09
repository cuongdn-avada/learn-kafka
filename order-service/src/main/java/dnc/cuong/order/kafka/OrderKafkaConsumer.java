package dnc.cuong.order.kafka;

import dnc.cuong.common.avro.OrderEventAvro;
import dnc.cuong.common.avro.OrderEventMapper;
import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer cho Order Service — nhận kết quả từ Saga (Avro format).
 *
 * WHY consumer nhận OrderEventAvro rồi convert sang OrderEvent?
 * → Kafka layer dùng Avro (Schema Registry enforced).
 * → Service layer dùng OrderEvent (Java record) — không phụ thuộc Avro.
 * → Mapper convert ở đây, service không cần biết serialization format.
 *
 * Consume 3 topics:
 * - order.paid → Payment thành công → COMPLETED + publish order.completed
 * - order.failed → Stock validation thất bại → FAILED
 * - payment.failed → Payment thất bại → PAYMENT_FAILED
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderKafkaConsumer {

    private final OrderService orderService;

    @KafkaListener(
            topics = KafkaTopics.ORDER_PAID,
            groupId = "order-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderPaid(OrderEventAvro avroEvent) {
        OrderEvent event = OrderEventMapper.fromAvro(avroEvent);

        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.ORDER_PAID, event.eventId(), event.orderId(), event.status());

        orderService.completeOrder(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.ORDER_PAID, event.orderId());
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_FAILED,
            groupId = "order-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderFailed(OrderEventAvro avroEvent) {
        OrderEvent event = OrderEventMapper.fromAvro(avroEvent);

        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.ORDER_FAILED, event.eventId(), event.orderId(), event.status());

        orderService.failOrder(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.ORDER_FAILED, event.orderId());
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED,
            groupId = "order-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentFailed(OrderEventAvro avroEvent) {
        OrderEvent event = OrderEventMapper.fromAvro(avroEvent);

        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.PAYMENT_FAILED, event.eventId(), event.orderId(), event.status());

        orderService.handlePaymentFailure(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.PAYMENT_FAILED, event.orderId());
    }
}
