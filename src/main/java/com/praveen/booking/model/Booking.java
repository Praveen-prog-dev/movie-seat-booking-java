package com.praveen.booking.model;

import java.time.Instant;
import java.util.List;

public record Booking(String bookingId, String showId, String userId, List<String> seatIds,
                      long amount, String paymentReference, Instant bookedAt, Status status) {

    public enum Status {
        CONFIRMED,
        CANCELLED
    }

    public Booking {
        seatIds = List.copyOf(seatIds);
    }

    public Booking cancelled() {
        return new Booking(bookingId, showId, userId, seatIds, amount, paymentReference, bookedAt, Status.CANCELLED);
    }
}
