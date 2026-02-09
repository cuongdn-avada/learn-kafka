package dnc.cuong.payment.kafka;

import dnc.cuong.common.avro.OrderEventAvro;
import dnc.cuong.common.avro.OrderEventMapper;
import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer — listen topic order.validated (Avro format).
 *
 * WHY Payment Service consume order.validated (không phải order.placed)?
 * → Choreography Saga: payment chỉ xử lý SAU KHI stock đã validate thành công.
 * → Nếu stock thiếu → không có order.validated → payment không chạy.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentKafkaConsumer {

    private final PaymentService paymentService;

    @KafkaListener(
            topics = KafkaTopics.ORDER_VALIDATED,
            groupId = "payment-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderValidated(OrderEventAvro avroEvent) {
        OrderEvent event = OrderEventMapper.fromAvro(avroEvent);

        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.ORDER_VALIDATED, event.eventId(), event.orderId(), event.status());

        paymentService.processOrderValidated(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.ORDER_VALIDATED, event.orderId());
    }
}
