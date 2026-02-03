package dnc.cuong.order.domain;

import dnc.cuong.common.event.OrderStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Order aggregate root.
 *
 * WHY dùng UUID làm ID thay vì auto-increment Long?
 * → Globally unique — an toàn khi dùng làm Kafka message key (partition routing).
 * → Không phụ thuộc vào DB sequence — có thể generate trước khi persist.
 * → Tránh information leak (attacker không đoán được tổng số order).
 *
 * WHY CascadeType.ALL cho orderItems?
 * → OrderItem là child entity, lifecycle gắn liền với Order.
 * → Khi save Order → tự save tất cả items. Khi delete Order → tự delete items.
 * → Đây là aggregate pattern trong DDD: Order là root, OrderItem là value object.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID customerId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    /** Lý do thất bại — null khi thành công */
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItem> orderItems = new ArrayList<>();

    // --- Domain methods ---

    public void addItem(OrderItem item) {
        orderItems.add(item);
        item.setOrder(this);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
