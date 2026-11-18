package com.praveen.booking;

import java.util.List;

public class BookingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BookingException(String message) {
        super(message);
    }

    public static class SeatsUnavailable extends BookingException {
        private static final long serialVersionUID = 1L;
        private final transient List<String> seatIds;

        public SeatsUnavailable(List<String> seatIds) {
            super("seats not available: " + seatIds);
            this.seatIds = List.copyOf(seatIds);
        }

        public List<String> seatIds() {
            return seatIds;
        }
    }

    public static class HoldExpired extends BookingException {
        private static final long serialVersionUID = 1L;

        public HoldExpired(String holdId) {
            super("hold " + holdId + " has expired, please select seats again");
        }
    }

    public static class PaymentFailed extends BookingException {
        private static final long serialVersionUID = 1L;

        public PaymentFailed(String reason) {
            super("payment failed: " + reason);
        }
    }
}
