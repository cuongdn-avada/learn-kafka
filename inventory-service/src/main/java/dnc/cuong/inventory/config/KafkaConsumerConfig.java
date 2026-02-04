package dnc.cuong.inventory.config;

import dnc.cuong.common.event.OrderEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Kafka Consumer configuration tường minh.
 *
 * WHY tạo config class riêng?
 * → Giống lý do ở KafkaProducerConfig: full control, dễ debug, dễ test.
 * → Consumer phức tạp hơn producer — cần configure:
 *   - Deserializer và trusted packages (bảo mật)
 *   - Concurrency (số thread = số partition)
 *   - Error handler (retry strategy)
 *
 * WHY ConsumerFactory + ConcurrentKafkaListenerContainerFactory?
 * → ConsumerFactory: tạo Kafka Consumer instances với config đúng.
 * → ConcurrentKafkaListenerContainerFactory: quản lý lifecycle của @KafkaListener.
 *   - Tạo 1 thread per partition (nếu concurrency = partition count).
 *   - Handle poll loop, commit offset, error handling.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, OrderEvent> consumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);

        // Serialization config
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // WHY TRUSTED_PACKAGES?
        // → JsonDeserializer mặc định KHÔNG deserialize class lạ (bảo mật).
        // → Phải whitelist package chứa event classes.
        // → Nếu không set → "The class '...' is not in the trusted packages" error.
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "dnc.cuong.common.event");

        // WHY VALUE_DEFAULT_TYPE?
        // → Khi producer gửi message với __TypeId__ header, consumer dùng header đó.
        // → Nhưng nếu header bị thiếu hoặc type không match → cần fallback type.
        // → Đặt default type đảm bảo luôn deserialize được.
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderEvent.class.getName());

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, OrderEvent> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);

        // WHY setConcurrency(3)?
        // → Topic order.placed có 3 partitions (từ docker-compose kafka-init).
        // → Mỗi partition được 1 consumer thread xử lý.
        // → concurrency = partition count → tối ưu throughput.
        // → Nếu concurrency > partition count → thread thừa sẽ idle.
        // → Nếu concurrency < partition count → 1 thread xử lý nhiều partition.
        factory.setConcurrency(3);

        // WHY DefaultErrorHandler với FixedBackOff?
        // → Khi consumer throw exception → Kafka không auto-retry.
        // → DefaultErrorHandler retry message N lần trước khi skip (log + commit offset).
        // → FixedBackOff(1000L, 3) = retry 3 lần, mỗi lần chờ 1 giây.
        // → Sau 3 lần fail → message bị skip (sẽ thêm Dead Letter Queue ở Step 5).
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(new FixedBackOff(1000L, 3))
        );

        return factory;
    }
}
