package dnc.cuong.order.service;

import dnc.cuong.common.dto.OrderCreateRequest;
import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderEvent.OrderItem;
import dnc.cuong.common.event.OrderStatus;
import dnc.cuong.order.domain.Order;
import dnc.cuong.order.domain.OrderRepository;
import dnc.cuong.order.domain.ProcessedEvent;
import dnc.cuong.order.domain.ProcessedEventRepository;
import dnc.cuong.order.kafka.OrderKafkaProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Business logic cho Order.
 *
 * WHY Transactional ở service layer?
 * → Controller chỉ lo nhận request và trả response.
 * → Service quản lý transaction boundary — tất cả DB operations trong 1 method
 *   thành công hoặc thất bại cùng nhau.
 * → Nếu publish Kafka fail SAU khi save DB → order ở DB nhưng event không gửi.
 *   → Đây là vấn đề dual-write, sẽ giải quyết bằng Outbox Pattern ở Step 6.
 *   → Hiện tại chấp nhận risk này, focus học Producer fundamentals trước.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OrderKafkaProducer kafkaProducer;

    /**
     * Tạo đơn hàng mới và publish event order.placed.
     *
     * Flow:
     * 1. Tính totalAmount từ items
     * 2. Tạo Order entity với status PLACED
     * 3. Persist vào DB
     * 4. Build OrderEvent từ entity
     * 5. Publish lên Kafka topic order.placed
     * 6. Trả về Order entity
     */
    @Transactional
    public Order createOrder(OrderCreateRequest request) {
        // 1. Tính tổng tiền
        BigDecimal totalAmount = request.items().stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2. Tạo Order entity
        Order order = Order.builder()
                .customerId(request.customerId())
                .totalAmount(totalAmount)
                .status(OrderStatus.PLACED)
                .build();

        // Thêm items vào order (bidirectional relationship)
        request.items().forEach(itemReq -> {
            var orderItem = dnc.cuong.order.domain.OrderItem.builder()
                    .productId(itemReq.productId())
                    .productName(itemReq.productName())
                    .quantity(itemReq.quantity())
                    .price(itemReq.price())
                    .build();
            order.addItem(orderItem);
        });

        // 3. Persist
        Order savedOrder = orderRepository.save(order);
        log.info("Order created | orderId={} | customerId={} | totalAmount={} | itemCount={}",
                savedOrder.getId(), savedOrder.getCustomerId(),
                savedOrder.getTotalAmount(), savedOrder.getOrderItems().size());

        // 4. Build event
        List<OrderItem> eventItems = savedOrder.getOrderItems().stream()
                .map(item -> new OrderItem(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getPrice()
                ))
                .toList();

        OrderEvent event = OrderEvent.create(
                savedOrder.getId(),
                savedOrder.getCustomerId(),
                eventItems,
                savedOrder.getTotalAmount(),
                OrderStatus.PLACED
        );

        // 5. Publish — async, không block response
        kafkaProducer.sendOrderPlaced(event);

        return savedOrder;
    }

    @Transactional(readOnly = true)
    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public List<Order> getOrdersByCustomer(UUID customerId) {
        return orderRepository.findByCustomerId(customerId);
    }

    /**
     * Payment thành công → cập nhật order COMPLETED + publish order.completed.
     *
     * WHY publish order.completed?
     * → Notification Service cần biết order hoàn thành để gửi email/push.
     * → Các service khác có thể subscribe để trigger post-order logic.
     */
    @Transactional
    public void completeOrder(OrderEvent event) {
        // Idempotency check: prevent duplicate status update
        if (processedEventRepository.existsById(event.eventId())) {
            log.warn("Duplicate event detected, skipping | eventId={} | orderId={} | topic={}",
                    event.eventId(), event.orderId(), KafkaTopics.ORDER_PAID);
            return;
        }

        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new OrderNotFoundException(event.orderId()));

        order.setStatus(OrderStatus.COMPLETED);
        processedEventRepository.save(new ProcessedEvent(event.eventId(), KafkaTopics.ORDER_PAID));
        log.info("Order COMPLETED | orderId={}", event.orderId());

        // Build event với item list từ incoming event
        OrderEvent completedEvent = OrderEvent.create(
                event.orderId(), event.customerId(),
                event.items(), event.totalAmount(),
                OrderStatus.COMPLETED
        );
        kafkaProducer.sendOrderCompleted(completedEvent);
    }

    /**
     * Stock validation thất bại → cập nhật order FAILED.
     */
    @Transactional
    public void failOrder(OrderEvent event) {
        // Idempotency check
        if (processedEventRepository.existsById(event.eventId())) {
            log.warn("Duplicate event detected, skipping | eventId={} | orderId={} | topic={}",
                    event.eventId(), event.orderId(), KafkaTopics.ORDER_FAILED);
            return;
        }

        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new OrderNotFoundException(event.orderId()));

        order.setStatus(OrderStatus.FAILED);
        order.setFailureReason(event.reason());
        processedEventRepository.save(new ProcessedEvent(event.eventId(), KafkaTopics.ORDER_FAILED));
        log.warn("Order FAILED | orderId={} | reason={}", event.orderId(), event.reason());
    }

    /**
     * Payment thất bại → cập nhật order PAYMENT_FAILED.
     * Inventory Service sẽ tự release stock (consume cùng payment.failed topic).
     */
    @Transactional
    public void handlePaymentFailure(OrderEvent event) {
        // Idempotency check
        if (processedEventRepository.existsById(event.eventId())) {
            log.warn("Duplicate event detected, skipping | eventId={} | orderId={} | topic={}",
                    event.eventId(), event.orderId(), KafkaTopics.PAYMENT_FAILED);
            return;
        }

        Order order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new OrderNotFoundException(event.orderId()));

        order.setStatus(OrderStatus.PAYMENT_FAILED);
        order.setFailureReason(event.reason());
        processedEventRepository.save(new ProcessedEvent(event.eventId(), KafkaTopics.PAYMENT_FAILED));
        log.warn("Order PAYMENT_FAILED | orderId={} | reason={}", event.orderId(), event.reason());
    }
}
