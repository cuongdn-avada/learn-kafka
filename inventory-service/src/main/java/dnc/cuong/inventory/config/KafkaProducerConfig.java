package dnc.cuong.inventory.config;

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
 * WHY Inventory Service cần producer?
 * → Inventory Service là CONSUMER của order.placed,
 *   nhưng cũng là PRODUCER của order.validated và order.failed.
 * → Choreography pattern: mỗi service vừa consume vừa produce events.
 */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, OrderEventAvro> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);

        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
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
