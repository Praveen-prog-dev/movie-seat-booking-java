package com.praveen.booking;

import com.praveen.booking.model.Booking;
import com.praveen.booking.model.Screen;
import com.praveen.booking.model.SeatCategory;
import com.praveen.booking.model.SeatHold;
import com.praveen.booking.model.Show;
import com.praveen.booking.payment.InMemoryPaymentGateway;
import com.praveen.booking.payment.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingServiceTest {

    static final Duration HOLD = Duration.ofMinutes(5);

    private MutableClock clock;
    private InMemoryPaymentGateway payments;
    private BookingService service;

    static Show smallShow() {
        Screen screen = Screen.builder("S1", "Audi 1")
                .rows("AB", 5, SeatCategory.REGULAR)
                .rows("C", 4, SeatCategory.RECLINER)
                .build();
        return new Show("SH1", "Some Movie", screen, LocalDateTime.of(2026, 1, 1, 18, 0),
                Map.of(SeatCategory.REGULAR, 150_00L, SeatCategory.RECLINER, 500_00L));
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        payments = new InMemoryPaymentGateway(user -> user.startsWith("broke"));
        service = new BookingService(payments, clock, HOLD);
        service.addShow(smallShow());
    }

    @Test
    void holdAndConfirmBooksTheSeats() {
        SeatHold hold = service.holdSeats("SH1", "alice", List.of("A1", "A2"));
        Booking booking = service.confirm(hold.holdId());

        assertEquals(Booking.Status.CONFIRMED, booking.status());
        assertEquals(List.of("A1", "A2"), booking.seatIds());
        assertFalse(service.availableSeats("SH1").contains("A1"));
        assertEquals(1, payments.successfulCharges());
    }

    @Test
    void priceDependsOnSeatCategory() {
        SeatHold hold = service.holdSeats("SH1", "alice", List.of("A1", "C1"));
        assertEquals(150_00L + 500_00L, hold.amount());
    }

    @Test
    void heldSeatCannotBeHeldByAnotherUser() {
        service.holdSeats("SH1", "alice", List.of("B3"));

        BookingException.SeatsUnavailable e = assertThrows(BookingException.SeatsUnavailable.class,
                () -> service.holdSeats("SH1", "bob", List.of("B2", "B3")));
        assertEquals(List.of("B3"), e.seatIds());
    }

    @Test
    void failedMultiSeatHoldGrabsNothing() {
        service.holdSeats("SH1", "alice", List.of("A2"));

        assertThrows(BookingException.SeatsUnavailable.class,
                () -> service.holdSeats("SH1", "bob", List.of("A1", "A2")));
        assertTrue(service.availableSeats("SH1").contains("A1"), "A1 must not stay half-held");
    }

    @Test
    void expiredHoldFreesSeatsAndCannotBeConfirmed() {
        SeatHold slow = service.holdSeats("SH1", "alice", List.of("A1"));
        clock.advance(HOLD.plusSeconds(1));

        SeatHold fast = service.holdSeats("SH1", "bob", List.of("A1"));
        assertThrows(BookingException.HoldExpired.class, () -> service.confirm(slow.holdId()));

        // alice's expired hold must not release bob's seat
        assertFalse(service.availableSeats("SH1").contains("A1"));
        assertEquals("bob", service.confirm(fast.holdId()).userId());
    }

    @Test
    void sweeperReleasesExpiredHolds() {
        service.holdSeats("SH1", "alice", List.of("A1", "A2"));
        service.holdSeats("SH1", "bob", List.of("B1"));
        clock.advance(Duration.ofMinutes(3));
        service.holdSeats("SH1", "carol", List.of("C1"));
        clock.advance(Duration.ofMinutes(3));

        assertEquals(2, service.releaseExpiredHolds());
        assertEquals(0, service.releaseExpiredHolds());
        assertFalse(service.availableSeats("SH1").contains("C1"));
    }

    @Test
    void paymentFailureReleasesSeats() {
        SeatHold hold = service.holdSeats("SH1", "broke-user", List.of("A5"));

        assertThrows(BookingException.PaymentFailed.class, () -> service.confirm(hold.holdId()));
        assertTrue(service.availableSeats("SH1").contains("A5"));
    }

    @Test
    void holdDoesNotExpireWhilePaymentIsInProgress() {
        AtomicReference<BookingService> serviceRef = new AtomicReference<>();
        AtomicBoolean bobGotTheSeat = new AtomicBoolean();
        PaymentGateway slowGateway = new PaymentGateway() {
            @Override
            public PaymentResult charge(String userId, long amount, String key) {
                // the bank takes longer than the hold duration, the sweeper runs and another
                // user tries for the same seat in the meantime
                clock.advance(HOLD.multipliedBy(2));
                serviceRef.get().releaseExpiredHolds();
                try {
                    serviceRef.get().holdSeats("SH1", "bob", List.of("A1"));
                    bobGotTheSeat.set(true);
                } catch (BookingException.SeatsUnavailable expected) {
                    // good
                }
                return PaymentResult.ok("pay-1");
            }

            @Override
            public void refund(String reference, long amount) {
            }
        };
        BookingService slowService = new BookingService(slowGateway, clock, HOLD);
        serviceRef.set(slowService);
        slowService.addShow(smallShow());

        SeatHold hold = slowService.holdSeats("SH1", "alice", List.of("A1"));
        Booking booking = slowService.confirm(hold.holdId());

        assertEquals(Booking.Status.CONFIRMED, booking.status());
        assertFalse(bobGotTheSeat.get(), "seat was sold to bob while alice was paying for it");
    }

    @Test
    void confirmIsIdempotent() {
        SeatHold hold = service.holdSeats("SH1", "alice", List.of("A1"));
        Booking first = service.confirm(hold.holdId());
        Booking again = service.confirm(hold.holdId());

        assertSame(first, again);
        assertEquals(1, payments.successfulCharges());
    }

    @Test
    void cancellingABookingRefundsAndReopensSeats() {
        Booking booking = service.confirm(service.holdSeats("SH1", "alice", List.of("C1", "C2")).holdId());

        Booking cancelled = service.cancelBooking(booking.bookingId());

        assertEquals(Booking.Status.CANCELLED, cancelled.status());
        assertEquals(1000_00L, payments.refundedFor(booking.paymentReference()));
        assertTrue(service.availableSeats("SH1").containsAll(List.of("C1", "C2")));
        assertThrows(IllegalArgumentException.class, () -> service.cancelBooking(booking.bookingId()));
    }

    @Test
    void validatesRequests() {
        assertThrows(IllegalArgumentException.class, () -> service.holdSeats("SH1", "a", List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.holdSeats("SH1", "a", List.of("Z9")));
        assertThrows(IllegalArgumentException.class, () -> service.holdSeats("NOPE", "a", List.of("A1")));
        assertThrows(IllegalArgumentException.class, () -> service.holdSeats("SH1", "a",
                List.of("A1", "A2", "A3", "A4", "A5", "B1", "B2", "B3", "B4", "B5", "C1")));
    }
}
