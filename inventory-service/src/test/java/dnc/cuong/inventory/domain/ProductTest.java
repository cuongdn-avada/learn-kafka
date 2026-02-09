package dnc.cuong.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho Product domain entity — test business logic thuần túy.
 *
 * WHY test domain logic riêng?
 * -> hasStock, reserveStock, releaseStock là core business rules.
 * -> Test không cần database hay Spring context → chạy cực nhanh.
 * -> Đảm bảo stock calculation chính xác trước khi test integration.
 */
class ProductTest {

    private Product createProduct(int available, int reserved) {
        return Product.builder()
                .name("Test Product")
                .skuCode("TEST-001")
                .availableQuantity(available)
                .reservedQuantity(reserved)
                .build();
    }

    // --- hasStock ---

    @Test
    void hasStock_shouldReturnTrue_whenSufficientQuantity() {
        Product product = createProduct(50, 0);
        assertTrue(product.hasStock(50));
    }

    @Test
    void hasStock_shouldReturnTrue_whenMoreThanRequested() {
        Product product = createProduct(100, 0);
        assertTrue(product.hasStock(30));
    }

    @Test
    void hasStock_shouldReturnFalse_whenInsufficientQuantity() {
        Product product = createProduct(5, 0);
        assertFalse(product.hasStock(10));
    }

    @Test
    void hasStock_shouldReturnFalse_whenZeroAvailable() {
        Product product = createProduct(0, 0);
        assertFalse(product.hasStock(1));
    }

    @Test
    void hasStock_shouldReturnTrue_whenZeroRequested() {
        Product product = createProduct(10, 0);
        assertTrue(product.hasStock(0));
    }

    // --- reserveStock ---

    @Test
    void reserveStock_shouldDecreaseAvailableAndIncreaseReserved() {
        Product product = createProduct(50, 0);

        product.reserveStock(10);

        assertEquals(40, product.getAvailableQuantity());
        assertEquals(10, product.getReservedQuantity());
    }

    @Test
    void reserveStock_shouldHandleMultipleReservations() {
        Product product = createProduct(100, 0);

        product.reserveStock(30);
        product.reserveStock(20);

        assertEquals(50, product.getAvailableQuantity());
        assertEquals(50, product.getReservedQuantity());
    }

    @Test
    void reserveStock_shouldReserveExactlyAllAvailable() {
        Product product = createProduct(25, 0);

        product.reserveStock(25);

        assertEquals(0, product.getAvailableQuantity());
        assertEquals(25, product.getReservedQuantity());
    }

    @Test
    void reserveStock_shouldThrow_whenInsufficientStock() {
        Product product = createProduct(5, 0);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> product.reserveStock(10)
        );

        assertTrue(ex.getMessage().contains("Insufficient stock"));
        // Ensure no change on failure
        assertEquals(5, product.getAvailableQuantity());
        assertEquals(0, product.getReservedQuantity());
    }

    // --- releaseStock ---

    @Test
    void releaseStock_shouldIncreaseAvailableAndDecreaseReserved() {
        Product product = createProduct(40, 10);

        product.releaseStock(10);

        assertEquals(50, product.getAvailableQuantity());
        assertEquals(0, product.getReservedQuantity());
    }

    @Test
    void releaseStock_shouldHandlePartialRelease() {
        Product product = createProduct(30, 20);

        product.releaseStock(5);

        assertEquals(35, product.getAvailableQuantity());
        assertEquals(15, product.getReservedQuantity());
    }

    @Test
    void releaseStock_shouldThrow_whenReleasingMoreThanReserved() {
        Product product = createProduct(40, 10);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> product.releaseStock(15)
        );

        assertTrue(ex.getMessage().contains("Cannot release more than reserved"));
        // Ensure no change on failure
        assertEquals(40, product.getAvailableQuantity());
        assertEquals(10, product.getReservedQuantity());
    }

    // --- reserve + release combination (Saga compensation) ---

    @Test
    void reserveThenRelease_shouldRestoreOriginalQuantities() {
        Product product = createProduct(100, 0);

        // Saga step: reserve stock
        product.reserveStock(30);
        assertEquals(70, product.getAvailableQuantity());
        assertEquals(30, product.getReservedQuantity());

        // Saga compensation: release stock
        product.releaseStock(30);
        assertEquals(100, product.getAvailableQuantity());
        assertEquals(0, product.getReservedQuantity());
    }
}
