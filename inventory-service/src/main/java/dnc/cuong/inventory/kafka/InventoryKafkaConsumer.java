package dnc.cuong.inventory.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer — listen topic order.placed.
 *
 * WHY @KafkaListener thay vì manual poll loop?
 * → Spring Kafka abstract hóa consumer poll loop phức tạp.
 * → @KafkaListener tự quản lý: poll, deserialize, commit offset, error handling.
 * → Developer chỉ cần focus business logic trong method body.
 *
 * WHY groupId = "inventory-service-group"?
 * → Consumer Group đảm bảo: mỗi partition chỉ được 1 consumer trong group xử lý.
 * → Nếu chạy 3 instance inventory-service → mỗi instance xử lý 1 partition.
 * → Nếu 1 instance die → Kafka rebalance partition cho instance còn lại.
 * → Group ID khác nhau giữa services → mỗi service nhận TẤT CẢ messages.
 *
 * WHY containerFactory = "kafkaListenerContainerFactory"?
 * → Link tới bean trong KafkaConsumerConfig.
 * → Factory quyết định: concurrency, error handler, deserializer.
 * → Nếu không chỉ định → Spring dùng default factory (ít control hơn).
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
    public void onOrderPlaced(OrderEvent event) {
        log.info("Received event from [{}] | eventId={} | orderId={} | status={}",
                KafkaTopics.ORDER_PLACED, event.eventId(), event.orderId(), event.status());

        inventoryService.processOrderPlaced(event);

        log.info("Finished processing [{}] | orderId={}", KafkaTopics.ORDER_PLACED, event.orderId());
    }
}
