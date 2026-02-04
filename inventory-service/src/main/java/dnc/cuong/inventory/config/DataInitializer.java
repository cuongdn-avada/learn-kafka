package dnc.cuong.inventory.config;

import dnc.cuong.inventory.domain.Product;
import dnc.cuong.inventory.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seed sample products khi khởi động.
 *
 * WHY CommandLineRunner?
 * → Chạy SAU khi Spring context hoàn tất (tất cả beans đã ready).
 * → JPA/Hibernate đã tạo bảng (ddl-auto: update) → safe để insert.
 * → Chỉ seed khi database trống (idempotent — chạy nhiều lần không lỗi).
 *
 * WHY fixed UUIDs?
 * → Để test request từ Order Service dùng đúng productId.
 * → Reproducible: mỗi lần restart đều có cùng products.
 * → README chứa curl commands với các UUID này.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final ProductRepository productRepository;

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            log.info("Products already exist, skipping seed data");
            return;
        }

        List<Product> products = List.of(
                Product.builder()
                        .id(UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"))
                        .skuCode("LAPTOP-001")
                        .name("MacBook Pro 14")
                        .availableQuantity(50)
                        .build(),
                Product.builder()
                        .id(UUID.fromString("8a9e6679-7425-40de-944b-e07fc1f90ae8"))
                        .skuCode("MOUSE-001")
                        .name("Magic Mouse")
                        .availableQuantity(100)
                        .build(),
                Product.builder()
                        .id(UUID.fromString("9b9e6679-7425-40de-944b-e07fc1f90ae9"))
                        .skuCode("PHONE-001")
                        .name("iPhone 15 Pro")
                        .availableQuantity(30)
                        .build(),
                // Product không có stock — dùng để test failure case
                Product.builder()
                        .id(UUID.fromString("ac9e6679-7425-40de-944b-e07fc1f90af0"))
                        .skuCode("WATCH-001")
                        .name("Apple Watch Ultra")
                        .availableQuantity(0)
                        .build()
        );

        productRepository.saveAll(products);
        log.info("Initialized {} sample products", products.size());
    }
}
