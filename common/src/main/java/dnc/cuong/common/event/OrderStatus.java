package dnc.cuong.common.event;

/**
 * Trạng thái đơn hàng trong Saga flow.
 *
 * Flow: PLACED → VALIDATED → PAID → COMPLETED
 *                    ↘ FAILED (stock không đủ)
 *                              ↘ PAYMENT_FAILED (charge thất bại)
 */
public enum OrderStatus {
    PLACED,
    VALIDATED,
    PAID,
    COMPLETED,
    FAILED,
    PAYMENT_FAILED
}
