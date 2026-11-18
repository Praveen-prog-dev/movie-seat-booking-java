package com.praveen.booking;

import com.praveen.booking.BookingException.HoldExpired;
import com.praveen.booking.BookingException.PaymentFailed;
import com.praveen.booking.BookingException.SeatsUnavailable;
import com.praveen.booking.model.Booking;
import com.praveen.booking.model.Seat;
import com.praveen.booking.model.SeatHold;
import com.praveen.booking.model.Show;
import com.praveen.booking.payment.PaymentGateway;
import com.praveen.booking.payment.PaymentGateway.PaymentResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-step checkout, same as every ticketing site:
 *
 *   1. holdSeats()  - seats are reserved for a few minutes while the user pays
 *   2. confirm()    - payment is charged and the hold turns into a booking
 *
 * If the user walks away, the hold expires and the seats go back on sale.
 */
public class BookingService {

    static final int MAX_SEATS_PER_BOOKING = 10;

    private final Map<String, ShowInventory> inventories = new ConcurrentHashMap<>();
    private final Map<String, SeatHold> holds = new ConcurrentHashMap<>();
    private final Set<String> confirming = ConcurrentHashMap.newKeySet();
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Map<String, String> bookingIdByHoldId = new ConcurrentHashMap<>();

    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final Duration holdDuration;

    public BookingService(PaymentGateway paymentGateway, Clock clock, Duration holdDuration) {
        this.paymentGateway = paymentGateway;
        this.clock = clock;
        this.holdDuration = holdDuration;
    }

    public void addShow(Show show) {
        if (inventories.putIfAbsent(show.id(), new ShowInventory(show)) != null) {
            throw new IllegalArgumentException("show " + show.id() + " already exists");
        }
    }

    public SeatHold holdSeats(String showId, String userId, List<String> seatIds) {
        Set<String> wanted = new LinkedHashSet<>(seatIds);
        if (wanted.isEmpty() || wanted.size() > MAX_SEATS_PER_BOOKING) {
            throw new IllegalArgumentException("you can book between 1 and " + MAX_SEATS_PER_BOOKING + " seats");
        }
        ShowInventory inventory = inventory(showId);

        inventory.lock.lock();
        try {
            Instant now = clock.instant();
            List<String> taken = new ArrayList<>();
            long amount = 0;
            for (String seatId : wanted) {
                Seat seat = inventory.seat(seatId);
                if (!inventory.isFree(seatId, now, confirming)) {
                    taken.add(seatId);
                }
                amount += inventory.show.priceOf(seat);
            }
            // all or nothing: half a booking is worse than none
            if (!taken.isEmpty()) {
                throw new SeatsUnavailable(taken);
            }

            SeatHold hold = new SeatHold(newId("H"), showId, userId, List.copyOf(wanted), amount, now.plus(holdDuration));
            wanted.forEach(seatId -> inventory.hold(seatId, hold.holdId(), hold.expiresAt()));
            holds.put(hold.holdId(), hold);
            return hold;
        } finally {
            inventory.lock.unlock();
        }
    }

    /**
     * Payment is the slow part (an external call that can take seconds), so we must not hold
     * the show lock while charging. Instead the hold is flagged as "confirming" - that stops
     * it from expiring mid-payment - and the lock is taken again to finish up.
     */
    public Booking confirm(String holdId) {
        String existingBooking = bookingIdByHoldId.get(holdId);
        if (existingBooking != null) {
            return bookings.get(existingBooking); // retried request, already done
        }

        SeatHold hold = holds.get(holdId);
        if (hold == null) {
            throw new HoldExpired(holdId);
        }
        ShowInventory inventory = inventory(hold.showId());

        inventory.lock.lock();
        try {
            if (!holds.containsKey(holdId) || hold.isExpired(clock.instant())) {
                releaseHold(inventory, hold);
                throw new HoldExpired(holdId);
            }
            if (!confirming.add(holdId)) {
                throw new IllegalStateException("hold " + holdId + " is already being confirmed");
            }
        } finally {
            inventory.lock.unlock();
        }

        PaymentResult payment;
        try {
            payment = paymentGateway.charge(hold.userId(), hold.amount(), holdId);
        } catch (RuntimeException e) {
            payment = PaymentResult.failed(e.getMessage());
        }

        inventory.lock.lock();
        try {
            confirming.remove(holdId);
            if (!payment.success()) {
                releaseHold(inventory, hold);
                throw new PaymentFailed(payment.failureReason());
            }
            Booking booking = new Booking(newId("B"), hold.showId(), hold.userId(), hold.seatIds(),
                    hold.amount(), payment.reference(), clock.instant(), Booking.Status.CONFIRMED);
            hold.seatIds().forEach(seatId -> inventory.book(seatId, booking.bookingId()));
            holds.remove(holdId);
            bookings.put(booking.bookingId(), booking);
            bookingIdByHoldId.put(holdId, booking.bookingId());
            return booking;
        } finally {
            inventory.lock.unlock();
        }
    }

    public void releaseHold(String holdId) {
        SeatHold hold = holds.get(holdId);
        if (hold == null) {
            return;
        }
        ShowInventory inventory = inventory(hold.showId());
        inventory.lock.lock();
        try {
            if (!confirming.contains(holdId)) {
                releaseHold(inventory, hold);
            }
        } finally {
            inventory.lock.unlock();
        }
    }

    public Booking cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        if (booking == null || booking.status() == Booking.Status.CANCELLED) {
            throw new IllegalArgumentException("no active booking " + bookingId);
        }
        ShowInventory inventory = inventory(booking.showId());
        Booking cancelled;
        inventory.lock.lock();
        try {
            booking.seatIds().forEach(inventory::release);
            cancelled = booking.cancelled();
            bookings.put(bookingId, cancelled);
        } finally {
            inventory.lock.unlock();
        }
        paymentGateway.refund(booking.paymentReference(), booking.amount());
        return cancelled;
    }

    /** Meant to run every few seconds from a scheduler. Returns how many holds were released. */
    public int releaseExpiredHolds() {
        Instant now = clock.instant();
        int released = 0;
        for (SeatHold hold : List.copyOf(holds.values())) {
            if (!hold.isExpired(now) || confirming.contains(hold.holdId())) {
                continue;
            }
            ShowInventory inventory = inventory(hold.showId());
            inventory.lock.lock();
            try {
                if (holds.containsKey(hold.holdId()) && !confirming.contains(hold.holdId())) {
                    releaseHold(inventory, hold);
                    released++;
                }
            } finally {
                inventory.lock.unlock();
            }
        }
        return released;
    }

    public List<String> availableSeats(String showId) {
        ShowInventory inventory = inventory(showId);
        inventory.lock.lock();
        try {
            Instant now = clock.instant();
            return inventory.seats().keySet().stream()
                    .filter(seatId -> inventory.isFree(seatId, now, confirming))
                    .toList();
        } finally {
            inventory.lock.unlock();
        }
    }

    public String seatMap(String showId) {
        ShowInventory inventory = inventory(showId);
        inventory.lock.lock();
        try {
            Instant now = clock.instant();
            StringBuilder sb = new StringBuilder();
            char currentRow = 0;
            for (Seat seat : inventory.seats().values()) {
                if (seat.row() != currentRow) {
                    if (currentRow != 0) {
                        sb.append('\n');
                    }
                    currentRow = seat.row();
                    sb.append(currentRow).append("  ");
                }
                sb.append(inventory.symbolFor(seat.id(), now)).append(' ');
            }
            return sb.append('\n').toString();
        } finally {
            inventory.lock.unlock();
        }
    }

    public Booking booking(String bookingId) {
        return bookings.get(bookingId);
    }

    // caller must hold inventory.lock
    private void releaseHold(ShowInventory inventory, SeatHold hold) {
        if (holds.remove(hold.holdId()) == null) {
            return;
        }
        // only release seats that still belong to this hold - once it expired, another
        // user may have grabbed them
        for (String seatId : hold.seatIds()) {
            if (inventory.isHeldBy(seatId, hold.holdId())) {
                inventory.release(seatId);
            }
        }
    }

    private ShowInventory inventory(String showId) {
        ShowInventory inventory = inventories.get(showId);
        if (inventory == null) {
            throw new IllegalArgumentException("unknown show " + showId);
        }
        return inventory;
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
