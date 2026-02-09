package dnc.cuong.payment.service;

import dnc.cuong.common.event.KafkaTopics;
import dnc.cuong.common.event.OrderEvent;
import dnc.cuong.common.event.OrderStatus;
import dnc.cuong.payment.domain.Payment;
import dnc.cuong.payment.domain.PaymentRepository;
import dnc.cuong.payment.domain.PaymentStatus;
import dnc.cuong.payment.domain.ProcessedEvent;
import dnc.cuong.payment.domain.ProcessedEventRepository;
import dnc.cuong.payment.kafka.PaymentKafkaProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Business logic cho Payment — simulate thanh toán.
 *
 * Rule đơn giản để test cả happy path và failure path:
 * - totalAmount <= 10,000 → payment SUCCESS → publish order.paid
 * - totalAmount > 10,000  → payment FAILED  → publish payment.failed
 *
 * WHY dùng amount threshold thay vì random?
 * → Deterministic: test lặp lại được kết quả.
 * → Dễ demo: biết trước order nào sẽ fail.
 * → Trong production: thay bằng real payment gateway (Stripe, VNPay, etc).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000");

    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PaymentKafkaProducer kafkaProducer;

    /**
     * Xử lý event order.validated: simulate payment.
     *
     * Flow:
     * 1. Check amount threshold
     * 2. Save Payment record vào DB
     * 3. Publish kết quả lên Kafka
     */
    @Transactional
    public void processOrderValidated(OrderEvent event) {
        // Idempotency check: prevent double charge!
        if (processedEventRepository.existsById(event.eventId())) {
            log.warn("Duplicate event detected, skipping | eventId={} | orderId={} | topic={}",
                    event.eventId(), event.orderId(), KafkaTopics.ORDER_VALIDATED);
            return;
        }

        log.info("Processing payment | orderId={} | amount={}",
                event.orderId(), event.totalAmount());

        boolean paymentSuccess = event.totalAmount().compareTo(MAX_AMOUNT) <= 0;

        if (paymentSuccess) {
            // Save payment record
            Payment payment = Payment.builder()
                    .orderId(event.orderId())
                    .customerId(event.customerId())
                    .amount(event.totalAmount())
                    .status(PaymentStatus.SUCCESS)
                    .build();
            paymentRepository.save(payment);

            log.info("Payment SUCCESS | orderId={} | amount={}", event.orderId(), event.totalAmount());

            // Publish order.paid
            OrderEvent paidEvent = OrderEvent.create(
                    event.orderId(), event.customerId(),
                    event.items(), event.totalAmount(),
                    OrderStatus.PAID
            );
            // Save ProcessedEvent — trong cùng transaction với save Payment
            processedEventRepository.save(new ProcessedEvent(event.eventId(), KafkaTopics.ORDER_VALIDATED));

            kafkaProducer.sendOrderPaid(paidEvent);

        } else {
            String reason = String.format("Payment declined: amount %s exceeds limit %s",
                    event.totalAmount(), MAX_AMOUNT);

            // Save failed payment record
            Payment payment = Payment.builder()
                    .orderId(event.orderId())
                    .customerId(event.customerId())
                    .amount(event.totalAmount())
                    .status(PaymentStatus.FAILED)
                    .failureReason(reason)
                    .build();
            paymentRepository.save(payment);

            log.warn("Payment FAILED | orderId={} | reason={}", event.orderId(), reason);

            // Save ProcessedEvent even for failure path
            processedEventRepository.save(new ProcessedEvent(event.eventId(), KafkaTopics.ORDER_VALIDATED));

            // Publish payment.failed → trigger compensation
            OrderEvent failedEvent = OrderEvent.withReason(
                    event.orderId(), event.customerId(),
                    event.items(), event.totalAmount(),
                    OrderStatus.PAYMENT_FAILED, reason
            );
            kafkaProducer.sendPaymentFailed(failedEvent);
        }
    }
}
