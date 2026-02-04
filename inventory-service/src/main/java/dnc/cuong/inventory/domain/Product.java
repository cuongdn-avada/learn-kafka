package dnc.cuong.inventory.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Product entity — theo dõi tồn kho.
 *
 * WHY tách availableQuantity và reservedQuantity?
 * → availableQuantity: số lượng còn có thể đặt.
 * → reservedQuantity: số lượng đã reserve cho order đang xử lý (chưa ship).
 * → Khi payment thành công → giảm reservedQuantity (ship hàng).
 * → Khi payment thất bại → chuyển reservedQuantity về lại availableQuantity (compensation).
 * → Mô hình này hỗ trợ Saga compensation pattern ở Step 5.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String skuCode;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int availableQuantity;

    @Column(nullable = false)
    @Builder.Default
    private int reservedQuantity = 0;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    // --- Domain methods ---

    /**
     * Kiểm tra còn đủ stock hay không.
     */
    public boolean hasStock(int quantity) {
        return availableQuantity >= quantity;
    }

    /**
     * Reserve stock cho order.
     *
     * WHY throw IllegalStateException thay vì return false?
     * → Caller đã check hasStock() trước khi gọi. Nếu vẫn thiếu stock,
     *   đây là bug hoặc race condition → cần fail loudly.
     * → Pessimistic locking sẽ được thêm ở Step 10 để tránh race condition.
     */
    public void reserveStock(int quantity) {
        if (!hasStock(quantity)) {
            throw new IllegalStateException(
                    "Insufficient stock for product " + id + ": available=" + availableQuantity + ", requested=" + quantity);
        }
        availableQuantity -= quantity;
        reservedQuantity += quantity;
    }

    /**
     * Hoàn trả stock khi payment thất bại (Saga compensation).
     * reserved → available.
     */
    public void releaseStock(int quantity) {
        if (quantity > reservedQuantity) {
            throw new IllegalStateException(
                    "Cannot release more than reserved for product " + id +
                    ": reserved=" + reservedQuantity + ", requested=" + quantity);
        }
        reservedQuantity -= quantity;
        availableQuantity += quantity;
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
