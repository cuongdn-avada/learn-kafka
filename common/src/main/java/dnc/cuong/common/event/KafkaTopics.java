package dnc.cuong.common.event;

/**
 * Topic names dùng chung giữa producer và consumer.
 *
 * WHY constants thay vì hardcode string?
 * → Tránh typo — compiler bắt lỗi thay vì runtime.
 * → Single source of truth cho topic names.
 * → Dễ refactor khi đổi naming convention.
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    public static final String ORDER_PLACED = "order.placed";
    public static final String ORDER_VALIDATED = "order.validated";
    public static final String ORDER_PAID = "order.paid";
    public static final String ORDER_COMPLETED = "order.completed";
    public static final String ORDER_FAILED = "order.failed";
    public static final String PAYMENT_FAILED = "payment.failed";
}
