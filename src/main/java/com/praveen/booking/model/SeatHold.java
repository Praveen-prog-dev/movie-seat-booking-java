package com.praveen.booking.model;

import java.time.Instant;
import java.util.List;

public record SeatHold(String holdId, String showId, String userId, List<String> seatIds,
                       long amount, Instant expiresAt) {

    public SeatHold {
        seatIds = List.copyOf(seatIds);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
