package dnc.cuong.inventory.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    /**
     * Batch lookup products by IDs.
     *
     * WHY batch thay vì findById từng cái?
     * → 1 SQL query: SELECT * FROM products WHERE id IN (?, ?, ...)
     * → Tránh N+1 problem khi order có nhiều items.
     * → Performance: 1 round-trip tới DB thay vì N round-trips.
     */
    List<Product> findAllByIdIn(List<UUID> ids);
}
