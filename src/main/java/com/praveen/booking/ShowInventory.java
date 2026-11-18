package com.praveen.booking;

import com.praveen.booking.model.Seat;
import com.praveen.booking.model.Show;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Seat states for one show, guarded by one lock. Locking per show (not globally, not per
 * seat) is the sweet spot: different shows never contend, and a multi-seat hold can check
 * and grab all its seats atomically without lock ordering headaches.
 */
final class ShowInventory {

    sealed interface SeatState permits Available, Held, Booked {
    }

    record Available() implements SeatState {
    }

    record Held(String holdId, Instant expiresAt) implements SeatState {
    }

    record Booked(String bookingId) implements SeatState {
    }

    private static final Available AVAILABLE = new Available();

    final Show show;
    final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Seat> seatsById = new LinkedHashMap<>();
    private final Map<String, SeatState> states = new LinkedHashMap<>();

    ShowInventory(Show show) {
        this.show = show;
        for (Seat seat : show.screen().seats()) {
            seatsById.put(seat.id(), seat);
            states.put(seat.id(), AVAILABLE);
        }
    }

    Seat seat(String seatId) {
        Seat seat = seatsById.get(seatId);
        if (seat == null) {
            throw new IllegalArgumentException("no seat " + seatId + " in " + show.screen().name());
        }
        return seat;
    }

    /** A hold that ran out counts as available - no need to wait for the sweeper. */
    boolean isFree(String seatId, Instant now, Set<String> holdsBeingConfirmed) {
        return switch (states.get(seatId)) {
            case Available a -> true;
            case Held h -> h.expiresAt().compareTo(now) <= 0 && !holdsBeingConfirmed.contains(h.holdId());
            case Booked b -> false;
        };
    }

    boolean isHeldBy(String seatId, String holdId) {
        return states.get(seatId) instanceof Held held && held.holdId().equals(holdId);
    }

    void hold(String seatId, String holdId, Instant expiresAt) {
        states.put(seatId, new Held(holdId, expiresAt));
    }

    void book(String seatId, String bookingId) {
        states.put(seatId, new Booked(bookingId));
    }

    void release(String seatId) {
        states.put(seatId, AVAILABLE);
    }

    /** For display: '.' free, 'h' held, 'X' booked. */
    char symbolFor(String seatId, Instant now) {
        return switch (states.get(seatId)) {
            case Available a -> '.';
            case Held h -> h.expiresAt().isAfter(now) ? 'h' : '.';
            case Booked b -> 'X';
        };
    }

    Map<String, Seat> seats() {
        return seatsById;
    }
}
