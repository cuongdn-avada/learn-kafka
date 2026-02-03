package dnc.cuong.order.config;

import dnc.cuong.common.event.OrderEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Kafka Producer configuration tường minh.
 *
 * WHY tạo config class riêng thay vì chỉ dùng application.yml?
 * → application.yml cover được 80% use case, nhưng khi cần:
 *   - Custom serializer settings (type header, etc.)
 *   - Nhiều KafkaTemplate cho các topic khác nhau
 *   - Programmatic config dựa trên environment
 * → Config class cho phép full control, dễ test, dễ debug.
 *
 * WHY inject KafkaProperties thay vì tự tạo Map?
 * → KafkaProperties đã bind từ application.yml (spring.kafka.*).
 * → Merge với custom config ở đây → không duplicate cấu hình.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, OrderEvent> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);

        // Đảm bảo serializer config đúng type
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // WHY ADD_TYPE_INFO_HEADERS = true (default)?
        // → JsonSerializer tự thêm header "__TypeId__" vào message.
        // → Consumer dùng header này để biết deserialize thành class nào.
        // → Quan trọng khi 1 topic có thể chứa nhiều event types.

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, OrderEvent> kafkaTemplate(
            ProducerFactory<String, OrderEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
