package com.praveen.booking;

import com.praveen.booking.model.Booking;
import com.praveen.booking.payment.InMemoryPaymentGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingConcurrencyTest {

    @Test
    void onlyOneOfManyUsersGetsTheSameSeat() throws InterruptedException {
        BookingService service = newService();
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);

        for (int i = 0; i < 100; i++) {
            String user = "user-" + i;
            pool.submit(() -> {
                go.await();
                try {
                    service.confirm(service.holdSeats("SH1", user, List.of("C2")).holdId());
                    winners.incrementAndGet();
                } catch (BookingException.SeatsUnavailable e) {
                    losers.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, winners.get());
        assertEquals(99, losers.get());
    }

    @Test
    void overlappingMultiSeatRequestsNeverDoubleBook() throws InterruptedException {
        BookingService service = newService();
        Queue<Booking> bookings = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(12);

        for (int t = 0; t < 12; t++) {
            long seed = t;
            pool.submit(() -> {
                Random random = new Random(seed);
                for (int i = 0; i < 200; i++) {
                    char row = "AB".charAt(random.nextInt(2));
                    int start = 1 + random.nextInt(4);
                    List<String> seats = List.of("" + row + start, "" + row + (start + 1));
                    try {
                        Booking b = service.confirm(service.holdSeats("SH1", "u" + seed + "-" + i, seats).holdId());
                        bookings.add(b);
                        if (random.nextInt(4) == 0) {
                            service.cancelBooking(b.bookingId()); // some people change their minds
                            bookings.remove(b);
                        }
                    } catch (BookingException.SeatsUnavailable ignored) {
                        // expected most of the time
                    }
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Set<String> sold = new HashSet<>();
        for (Booking b : bookings) {
            for (String seat : b.seatIds()) {
                assertTrue(sold.add(seat), "seat " + seat + " sold twice");
            }
        }
        // whatever isn't sold must be available again, nothing stuck in limbo
        Set<String> rowsAB = new HashSet<>(List.of("A1", "A2", "A3", "A4", "A5", "B1", "B2", "B3", "B4", "B5"));
        Set<String> available = new HashSet<>(service.availableSeats("SH1"));
        for (String seat : rowsAB) {
            assertTrue(sold.contains(seat) ^ available.contains(seat), seat + " is neither sold nor free");
        }
    }

    private static BookingService newService() {
        BookingService service = new BookingService(new InMemoryPaymentGateway(),
                new MutableClock(Instant.parse("2026-01-01T10:00:00Z")), Duration.ofMinutes(5));
        service.addShow(BookingServiceTest.smallShow());
        return service;
    }
}
