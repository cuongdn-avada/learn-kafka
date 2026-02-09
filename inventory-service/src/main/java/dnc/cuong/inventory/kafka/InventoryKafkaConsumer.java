package dnc.cuong.inventory.kafka;

import dnc.cuong.common.avro.OrderEventAvro;
import dnc.cuong.common.avro.OrderEventMapper;
import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer — listen topics order.placed và payment.failed (Avro format).
 *
 * Consumer nhận OrderEventAvro (Avro SpecificRecord) → convert sang OrderEvent (Java record)
 * → delegate cho InventoryService (service layer không biết về Avro).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryKafkaConsumer {

    private final InventoryService inventoryService;

    @KafkaListener(
            topics = KafkaTopics.ORDER_PLACED,
            groupId = "inventory-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onOrderPlaced(OrderEventAvro avroEvent) {
        OrderEvent event = OrderEventMapper.fromAvro(avroEvent);

        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.ORDER_PLACED, event.eventId(), event.orderId(), event.status());

        inventoryService.processOrderPlaced(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.ORDER_PLACED, event.orderId());
    }

    @KafkaListener(
            topics = KafkaTopics.PAYMENT_FAILED,
            groupId = "inventory-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentFailed(OrderEventAvro avroEvent) {
        OrderEvent event = OrderEventMapper.fromAvro(avroEvent);

        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.PAYMENT_FAILED, event.eventId(), event.orderId(), event.status());

        inventoryService.compensateReservation(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.PAYMENT_FAILED, event.orderId());
    }
}
