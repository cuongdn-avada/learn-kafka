package dnc.cuong.order.controller;

import dnc.cuong.common.dto.OrderCreateRequest;
import dnc.cuong.order.domain.Order;
import dnc.cuong.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Tạo đơn hàng mới.
     *
     * WHY trả về 201 CREATED thay vì 200 OK?
     * → RESTful convention: POST tạo resource mới → 201.
     * → Client biết chắc resource đã được tạo, không phải chỉ "ok".
     *
     * WHY trả về OrderResponse thay vì Order entity?
     * → Không expose JPA entity ra API — tránh leak internal fields,
     *   lazy loading issues, circular reference (order ↔ orderItem).
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        Order order = orderService.createOrder(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(OrderResponse.from(order));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId) {
        Order order = orderService.getOrder(orderId);
        return ResponseEntity.ok(OrderResponse.from(order));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getOrdersByCustomer(@RequestParam UUID customerId) {
        List<OrderResponse> orders = orderService.getOrdersByCustomer(customerId)
                .stream()
                .map(OrderResponse::from)
                .toList();
        return ResponseEntity.ok(orders);
    }

    /**
     * Response DTO — tách biệt khỏi JPA entity.
     *
     * WHY dùng inner record?
     * → Response chỉ dùng trong controller context.
     * → Không cần tạo file riêng cho class nhỏ.
     * → Nếu sau này phức tạp hơn, refactor ra file riêng.
     */
    public record OrderResponse(
            UUID orderId,
            UUID customerId,
            java.math.BigDecimal totalAmount,
            String status,
            String failureReason,
            java.time.Instant createdAt,
            List<OrderItemResponse> items
    ) {
        public static OrderResponse from(Order order) {
            List<OrderItemResponse> items = order.getOrderItems().stream()
                    .map(item -> new OrderItemResponse(
                            item.getProductId(),
                            item.getProductName(),
                            item.getQuantity(),
                            item.getPrice()
                    ))
                    .toList();

            return new OrderResponse(
                    order.getId(),
                    order.getCustomerId(),
                    order.getTotalAmount(),
                    order.getStatus().name(),
                    order.getFailureReason(),
                    order.getCreatedAt(),
                    items
            );
        }

        public record OrderItemResponse(
                UUID productId,
                String productName,
                int quantity,
                java.math.BigDecimal price
        ) {}
    }
}
