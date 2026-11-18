package com.praveen.booking.model;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/** Prices are in paise. */
public record Show(String id, String movieTitle, Screen screen, LocalDateTime startsAt,
                   Map<SeatCategory, Long> pricing) {

    public Show {
        pricing = Map.copyOf(new EnumMap<>(pricing));
        for (Seat seat : screen.seats()) {
            if (!pricing.containsKey(seat.category())) {
                throw new IllegalArgumentException("no price set for " + seat.category());
            }
        }
    }

    public long priceOf(Seat seat) {
        return pricing.get(seat.category());
    }
}
