package com.praveen.booking.payment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Stand-in for Razorpay/Stripe. Honors idempotency keys like the real ones do. */
public class InMemoryPaymentGateway implements PaymentGateway {

    private final Map<String, PaymentResult> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Long> refunds = new ConcurrentHashMap<>();
    private final AtomicInteger charges = new AtomicInteger();
    private final Predicate<String> declineUser;

    public InMemoryPaymentGateway() {
        this(userId -> false);
    }

    public InMemoryPaymentGateway(Predicate<String> declineUser) {
        this.declineUser = declineUser;
    }

    @Override
    public PaymentResult charge(String userId, long amountPaise, String idempotencyKey) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            if (declineUser.test(userId)) {
                return PaymentResult.failed("card declined");
            }
            charges.incrementAndGet();
            return PaymentResult.ok("pay_" + key);
        });
    }

    @Override
    public void refund(String paymentReference, long amountPaise) {
        refunds.merge(paymentReference, amountPaise, Long::sum);
    }

    public int successfulCharges() {
        return charges.get();
    }

    public long refundedFor(String paymentReference) {
        return refunds.getOrDefault(paymentReference, 0L);
    }
}
