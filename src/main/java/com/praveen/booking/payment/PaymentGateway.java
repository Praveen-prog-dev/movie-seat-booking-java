package com.praveen.booking.payment;

public interface PaymentGateway {

    /**
     * The idempotency key matters: if our request times out and we retry, the gateway must
     * not charge the customer twice. We use the hold id, which is unique per checkout.
     */
    PaymentResult charge(String userId, long amountPaise, String idempotencyKey);

    void refund(String paymentReference, long amountPaise);

    record PaymentResult(boolean success, String reference, String failureReason) {

        public static PaymentResult ok(String reference) {
            return new PaymentResult(true, reference, null);
        }

        public static PaymentResult failed(String reason) {
            return new PaymentResult(false, null, reason);
        }
    }
}
