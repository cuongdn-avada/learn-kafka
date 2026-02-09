package dnc.cuong.order.config;

import dnc.cuong.common.avro.OrderEventAvro;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

/**
 * Kafka Producer configuration — Avro serialization với Schema Registry.
 *
 * WHY migrate từ JsonSerializer sang KafkaAvroSerializer?
 * → JSON không enforce schema — producer có thể gửi bất kỳ format nào.
 * → Avro + Schema Registry validate schema trước khi publish.
 * → Schema Registry kiểm tra compatibility (BACKWARD default) — ngăn breaking changes.
 * → Avro binary format nhỏ hơn JSON ~30-50%, parse nhanh hơn.
 *
 * WHY inject KafkaProperties thay vì tự tạo Map?
 * → KafkaProperties đã bind từ application.yml (spring.kafka.*).
 * → Merge với custom config ở đây → không duplicate cấu hình.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, OrderEventAvro> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);

        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);

        // WHY schema.registry.url ở đây thay vì chỉ application.yml?
        // → KafkaAvroSerializer cần biết Schema Registry endpoint để register + validate schema.
        // → Config này cũng có thể đặt trong application.yml via spring.kafka.producer.properties.
        // → Đặt ở đây cho tường minh, dễ thấy dependency giữa producer và Schema Registry.
        props.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
                props.getOrDefault(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG,
                        "http://localhost:8085"));

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, OrderEventAvro> kafkaTemplate(
            ProducerFactory<String, OrderEventAvro> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
