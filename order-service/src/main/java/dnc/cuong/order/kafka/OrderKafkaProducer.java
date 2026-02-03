package dnc.cuong.order.kafka;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Producer chịu trách nhiệm publish OrderEvent lên Kafka.
 *
 * WHY tách Producer thành class riêng thay vì inject KafkaTemplate trực tiếp vào Service?
 * → Single Responsibility — Producer chỉ lo việc gửi message.
 * → Encapsulate logic: chọn topic, chọn key, xử lý callback.
 * → Dễ mock trong unit test (mock OrderKafkaProducer thay vì mock KafkaTemplate).
 * → Sau này thêm retry, circuit breaker, metrics tại đây mà không ảnh hưởng business logic.
 *
 * WHY dùng orderId làm message key?
 * → Kafka đảm bảo ordering TRONG CÙNG PARTITION.
 * → Cùng orderId → cùng partition → tất cả event của 1 order được xử lý theo thứ tự.
 * → Ví dụ: order.placed luôn đến trước order.validated cho cùng 1 orderId.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderKafkaProducer {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    /**
     * Publish event lên topic order.placed.
     *
     * WHY dùng CompletableFuture thay vì send().get() (blocking)?
     * → Non-blocking — thread không bị block chờ Kafka broker ack.
     * → Throughput cao hơn khi publish nhiều message.
     * → Callback xử lý success/failure asynchronously.
     *
     * WHY log cả partition và offset?
     * → Debug: biết message nằm ở partition nào, offset bao nhiêu.
     * → Troubleshoot: khi consumer bị lag, trace được message cụ thể.
     */
    public CompletableFuture<SendResult<String, OrderEvent>> sendOrderPlaced(OrderEvent event) {
        String key = event.orderId().toString();

        log.info("Publishing event to [{}] | key={} | eventId={} | status={}",
                KafkaTopics.ORDER_PLACED, key, event.eventId(), event.status());

        CompletableFuture<SendResult<String, OrderEvent>> future =
                kafkaTemplate.send(KafkaTopics.ORDER_PLACED, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // WHY log error thay vì throw?
                // → Đây là async callback, exception không propagate về caller.
                // → Log để alert system (Prometheus/Grafana sẽ bắt error log).
                // → Caller đã nhận CompletableFuture, có thể handle riêng.
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

    /**
     * Publish event cập nhật trạng thái order (COMPLETED, FAILED, etc.).
     * Dùng cho consumer side khi Order Service nhận event từ service khác.
     */
    public CompletableFuture<SendResult<String, OrderEvent>> sendOrderCompleted(OrderEvent event) {
        String key = event.orderId().toString();
        log.info("Publishing event to [{}] | key={} | eventId={}", KafkaTopics.ORDER_COMPLETED, key, event.eventId());
        return kafkaTemplate.send(KafkaTopics.ORDER_COMPLETED, key, event);
    }
}
