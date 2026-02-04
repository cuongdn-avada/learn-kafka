package dnc.cuong.order.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer cho Order Service — nhận kết quả từ Saga.
 *
 * Order Service là orchestrator "thụ động" trong Choreography:
 * - Không điều phối trực tiếp, nhưng là nơi tổng hợp kết quả cuối cùng.
 * - Cập nhật trạng thái order dựa trên event từ các service khác.
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

    /**
     * Payment thành công → cập nhật order COMPLETED, publish order.completed.
     */
    @KafkaListener(
            topics = KafkaTopics.ORDER_PAID,
            groupId = "order-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderPaid(OrderEvent event) {
        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.ORDER_PAID, event.eventId(), event.orderId(), event.status());

        orderService.completeOrder(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.ORDER_PAID, event.orderId());
    }

    /**
     * Stock validation thất bại → cập nhật order FAILED.
     */
    @KafkaListener(
            topics = KafkaTopics.ORDER_FAILED,
            groupId = "order-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderFailed(OrderEvent event) {
        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.ORDER_FAILED, event.eventId(), event.orderId(), event.status());

        orderService.failOrder(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.ORDER_FAILED, event.orderId());
    }

    /**
     * Payment thất bại → cập nhật order PAYMENT_FAILED.
     * (Inventory Service sẽ tự release stock khi nhận payment.failed)
     */
    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED,
            groupId = "order-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentFailed(OrderEvent event) {
        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.PAYMENT_FAILED, event.eventId(), event.orderId(), event.status());

        orderService.handlePaymentFailure(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.PAYMENT_FAILED, event.orderId());
    }
}
