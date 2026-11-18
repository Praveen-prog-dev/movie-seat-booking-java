package com.praveen.booking.model;

public record Seat(String id, char row, int number, SeatCategory category) {

    public static Seat of(char row, int number, SeatCategory category) {
        return new Seat("" + row + number, row, number, category);
    }
}
