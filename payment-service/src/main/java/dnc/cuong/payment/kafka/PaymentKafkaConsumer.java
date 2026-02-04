package dnc.cuong.payment.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer — listen topic order.validated.
 *
 * WHY Payment Service consume order.validated (không phải order.placed)?
 * → Choreography Saga: payment chỉ xử lý SAU KHI stock đã validate thành công.
 * → Nếu stock thiếu → không có order.validated → payment không chạy.
 * → Đây là implicit ordering qua event chain.
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
    public void onOrderValidated(OrderEvent event) {
        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.ORDER_VALIDATED, event.eventId(), event.orderId(), event.status());

        paymentService.processOrderValidated(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.ORDER_VALIDATED, event.orderId());
    }
}
