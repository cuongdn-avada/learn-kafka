package dnc.cuong.inventory.service;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderStatus;
import dnc.cuong.inventory.domain.ProcessedEvent;
import dnc.cuong.inventory.domain.ProcessedEventRepository;
import dnc.cuong.inventory.domain.Product;
import dnc.cuong.inventory.domain.ProductRepository;
import dnc.cuong.inventory.kafka.InventoryKafkaProducer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic cho Inventory — validate stock và reserve.
 *
 * WHY tách logic khỏi Consumer class?
 * → Consumer chỉ lo nhận message từ Kafka → delegate cho Service.
 * → Service có thể unit test KHÔNG cần Kafka infrastructure.
 * → Sau này nếu thêm REST API để manual reserve → reuse logic.
 *
 * WHY @Transactional trên processOrderPlaced?
 * → Reserve stock = UPDATE nhiều products trong DB.
 * → Nếu reserve product A thành công nhưng product B thất bại
 *   → rollback tất cả → tránh inconsistent state.
 * → Dual-write problem (DB + Kafka) sẽ giải quyết ở Step 6.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final ProductRepository productRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final InventoryKafkaProducer kafkaProducer;
    private final MeterRegistry meterRegistry;

    private Counter inventoryValidatedCounter;
    private Counter inventoryRejectedCounter;
    private Counter inventoryCompensatedCounter;

    @PostConstruct
    void initMetrics() {
        inventoryValidatedCounter = Counter.builder("inventory.validated.total")
                .description("Total orders validated (stock reserved)").register(meterRegistry);
        inventoryRejectedCounter = Counter.builder("inventory.rejected.total")
                .description("Total orders rejected (insufficient stock)").register(meterRegistry);
        inventoryCompensatedCounter = Counter.builder("inventory.compensated.total")
                .description("Total stock compensations (payment failed)").register(meterRegistry);
    }

    /**
     * Xử lý event order.placed: validate stock → reserve hoặc reject.
     *
     * Flow:
     * 1. Batch fetch tất cả products theo productId
     * 2. Validate: product tồn tại + đủ stock
     * 3. Nếu OK → reserve stock, publish order.validated
     * 4. Nếu FAIL → publish order.failed với reason
     */
    @Transactional
    public void processOrderPlaced(OrderEvent event) {
        // Idempotency check: skip if already processed
        if (processedEventRepository.existsById(event.eventId())) {
            log.warn("Duplicate event detected, skipping | eventId={} | orderId={} | topic={}",
                    event.eventId(), event.orderId(), KafkaTopics.ORDER_PLACED);
            return;
        }

        log.info("Processing order.placed | orderId={} | itemCount={}",
                event.orderId(), event.items().size());

        // 1. Extract productIds và batch fetch
        List<UUID> productIds = event.items().stream()
                .map(OrderEvent.OrderItem::productId)
                .toList();

        Map<UUID, Product> productMap = productRepository.findAllByIdIn(productIds)
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        // 2. Validate từng item
        List<String> failureReasons = new ArrayList<>();

        for (OrderEvent.OrderItem item : event.items()) {
            Product product = productMap.get(item.productId());

            if (product == null) {
                failureReasons.add("Product not found: " + item.productId());
                continue;
            }

            if (!product.hasStock(item.quantity())) {
                failureReasons.add(String.format(
                        "Insufficient stock for '%s': available=%d, requested=%d",
                        product.getName(), product.getAvailableQuantity(), item.quantity()));
            }
        }

        // 3. Nếu có lỗi → publish order.failed
        if (!failureReasons.isEmpty()) {
            String reason = String.join("; ", failureReasons);
            log.warn("Stock validation FAILED | orderId={} | reason={}", event.orderId(), reason);

            // Save ProcessedEvent even for failure path — prevent duplicate validation
            processedEventRepository.save(new ProcessedEvent(event.eventId(), KafkaTopics.ORDER_PLACED));

            OrderEvent failedEvent = OrderEvent.withReason(
                    event.orderId(), event.customerId(),
                    event.items(), event.totalAmount(),
                    OrderStatus.FAILED, reason
            );
            kafkaProducer.sendOrderFailed(failedEvent);
            inventoryRejectedCounter.increment();
            return;
        }

        // 4. Stock đủ → reserve tất cả items
        for (OrderEvent.OrderItem item : event.items()) {
            Product product = productMap.get(item.productId());
            product.reserveStock(item.quantity());
            // WHY không gọi productRepository.save(product)?
            // → JPA dirty checking: entity đã managed trong @Transactional.
            // → Khi transaction commit → Hibernate tự detect thay đổi và flush UPDATE.
            // → Giảm boilerplate code.
        }

        log.info("Stock reserved successfully | orderId={} | itemCount={}",
                event.orderId(), event.items().size());

        // 5. Save ProcessedEvent — trong cùng transaction với reserve stock
        processedEventRepository.save(new ProcessedEvent(event.eventId(), KafkaTopics.ORDER_PLACED));

        // 6. Publish order.validated
        OrderEvent validatedEvent = OrderEvent.create(
                event.orderId(), event.customerId(),
                event.items(), event.totalAmount(),
                OrderStatus.VALIDATED
        );
        kafkaProducer.sendOrderValidated(validatedEvent);
        inventoryValidatedCounter.increment();
    }

    /**
     * Compensation: hoàn trả stock khi payment thất bại.
     *
     * Flow:
     * 1. Batch fetch products theo order items
     * 2. Release reserved stock cho từng item
     * 3. JPA dirty checking tự flush UPDATE khi transaction commit
     */
    @Transactional
    public void compensateReservation(OrderEvent event) {
        // Idempotency check: prevent double release (stock cộng thừa)
        if (processedEventRepository.existsById(event.eventId())) {
            log.warn("Duplicate compensation event detected, skipping | eventId={} | orderId={}",
                    event.eventId(), event.orderId());
            return;
        }

        log.info("Compensating reservation | orderId={} | itemCount={}",
                event.orderId(), event.items().size());

        List<UUID> productIds = event.items().stream()
                .map(OrderEvent.OrderItem::productId)
                .toList();

        Map<UUID, Product> productMap = productRepository.findAllByIdIn(productIds)
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        for (OrderEvent.OrderItem item : event.items()) {
            Product product = productMap.get(item.productId());
            if (product != null) {
                product.releaseStock(item.quantity());
                log.info("Released stock | productId={} | productName={} | quantity={}",
                        product.getId(), product.getName(), item.quantity());
            } else {
                log.warn("Product not found during compensation | productId={}", item.productId());
            }
        }

        // Save ProcessedEvent — trong cùng transaction với release stock
        processedEventRepository.save(new ProcessedEvent(event.eventId(), KafkaTopics.PAYMENT_FAILED));

        log.info("Compensation completed | orderId={}", event.orderId());
        inventoryCompensatedCounter.increment();
    }
}
