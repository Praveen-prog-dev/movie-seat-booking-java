package com.praveen.booking.model;

import java.util.ArrayList;
import java.util.List;

public record Screen(String id, String name, List<Seat> seats) {

    public Screen {
        seats = List.copyOf(seats);
    }

    /** Small builder for the usual layout: a few rows per category, same number of seats per row. */
    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private final List<Seat> seats = new ArrayList<>();

        private Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder rows(String rowLetters, int seatsPerRow, SeatCategory category) {
            for (char row : rowLetters.toCharArray()) {
                for (int n = 1; n <= seatsPerRow; n++) {
                    seats.add(Seat.of(row, n, category));
                }
            }
            return this;
        }

        public Screen build() {
            return new Screen(id, name, seats);
        }
    }
}
