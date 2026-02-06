package dnc.cuong.order.config;

import dnc.cuong.common.event.OrderEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * Kafka Consumer configuration cho Order Service.
 *
 * WHY Order Service cần consumer?
 * → Trong Saga, Order Service là starting point VÀ cũng là ending point.
 * → Cần consume kết quả từ các service khác để cập nhật trạng thái order:
 *   - order.paid → COMPLETED
 *   - order.failed → FAILED (stock insufficient)
 *   - payment.failed → PAYMENT_FAILED
 *
 * WHY DeadLetterPublishingRecoverer thay vì FixedBackOff?
 * → FixedBackOff chỉ retry rồi skip — message bị mất.
 * → DeadLetterPublishingRecoverer publish message fail vào .DLT topic.
 * → Kết hợp ExponentialBackOff: retry thông minh hơn, tránh hammering.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, OrderEvent> consumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "dnc.cuong.common.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderEvent.class.getName());

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, OrderEvent> consumerFactory,
            KafkaTemplate<String, OrderEvent> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);

        // WHY DeadLetterPublishingRecoverer + ExponentialBackOff?
        // → Sau khi retry hết → message được publish vào <topic>.DLT thay vì bị drop.
        // → ExponentialBackOff: 1s → 2s → 4s → 8s → 10s (max), tối đa 3 lần retry.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(10000L);
        backOff.setMaxElapsedTime(30000L); // ~3 retries with exponential delays

        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backOff));

        return factory;
    }
}
