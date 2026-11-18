package com.praveen.booking;

import com.praveen.booking.model.Booking;
import com.praveen.booking.model.Screen;
import com.praveen.booking.model.SeatCategory;
import com.praveen.booking.model.SeatHold;
import com.praveen.booking.model.Show;
import com.praveen.booking.payment.InMemoryPaymentGateway;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public class Demo {

    public static void main(String[] args) throws Exception {
        BookingService service = new BookingService(new InMemoryPaymentGateway(), Clock.systemUTC(), Duration.ofMinutes(5));

        Screen audi1 = Screen.builder("S1", "Audi 1")
                .rows("ABCD", 10, SeatCategory.REGULAR)
                .rows("EF", 10, SeatCategory.PREMIUM)
                .rows("G", 6, SeatCategory.RECLINER)
                .build();
        service.addShow(new Show("SHOW-1", "Interstellar (re-release)", audi1,
                LocalDateTime.of(2026, 11, 21, 19, 30),
                Map.of(SeatCategory.REGULAR, 200_00L, SeatCategory.PREMIUM, 350_00L, SeatCategory.RECLINER, 600_00L)));

        // Friday night rush: 200 users try to grab 2-3 seats each, mostly fighting over the back rows
        Map<String, Integer> outcome = new ConcurrentHashMap<>();
        List<Booking> confirmed = new ArrayList<>();
        try (ExecutorService users = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int u = 0; u < 200; u++) {
                String userId = "user-" + u;
                users.submit(() -> {
                    List<String> wanted = pickSeats();
                    try {
                        SeatHold hold = service.holdSeats("SHOW-1", userId, wanted);
                        Booking booking = service.confirm(hold.holdId());
                        synchronized (confirmed) {
                            confirmed.add(booking);
                        }
                        outcome.merge("booked", 1, Integer::sum);
                    } catch (BookingException.SeatsUnavailable e) {
                        outcome.merge("seats taken, try again", 1, Integer::sum);
                    }
                });
            }
        }

        long seatsSold = confirmed.stream().mapToLong(b -> b.seatIds().size()).sum();
        long revenue = confirmed.stream().mapToLong(Booking::amount).sum();
        System.out.println("Outcome: " + outcome);
        System.out.printf("Seats sold: %d, revenue: Rs %,d%n%n", seatsSold, revenue / 100);
        System.out.println("Seat map (X = booked, . = free):");
        System.out.println(service.seatMap("SHOW-1"));
        System.out.println("Every seat sold exactly once: " + noSeatSoldTwice(confirmed));
    }

    private static List<String> pickSeats() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char row = "DEFG".charAt(random.nextInt(4));
        int maxStart = row == 'G' ? 4 : 8;
        int start = 1 + random.nextInt(maxStart);
        int count = 2 + random.nextInt(2);
        List<String> seats = new ArrayList<>();
        for (int i = 0; i < count && start + i <= (row == 'G' ? 6 : 10); i++) {
            seats.add("" + row + (start + i));
        }
        return seats;
    }

    private static boolean noSeatSoldTwice(List<Booking> bookings) {
        List<String> all = bookings.stream().flatMap(b -> b.seatIds().stream()).toList();
        return all.size() == all.stream().distinct().count();
    }
}
