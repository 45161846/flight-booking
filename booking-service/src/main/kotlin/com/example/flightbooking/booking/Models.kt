package com.example.flightbooking.booking

import com.example.flightbooking.contract.Flight
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** All money is integer RUB kopecks. IDs across service databases are logical references. */
data class CreateBookingRequest(
    @field:NotBlank @field:Size(max = 100) val userId: String,
    val flightId: UUID,
    @field:NotBlank @field:Size(max = 200) val passengerName: String,
    @field:NotBlank @field:Email @field:Size(max = 254) val passengerEmail: String,
    @field:Min(1) @field:Max(1000000) val seatCount: Int
)

enum class BookingStatus { CONFIRMED, CANCELLED }

data class Booking(
    val id: UUID,
    val userId: String,
    val flightId: UUID,
    val passengerName: String,
    val passengerEmail: String,
    val seatCount: Int,
    val totalPriceMinor: Long,
    val status: BookingStatus,
    val createdAt: Instant
)

data class FlightResponse(
    val id: String,
    val flightNumber: String,
    val airlineCode: String,
    val airlineName: String,
    val origin: String,
    val destination: String,
    val departureTime: Instant,
    val arrivalTime: Instant,
    val totalSeats: Int,
    val availableSeats: Int,
    val priceMinor: Long,
    val status: String
) {
    companion object {
        fun from(flight: Flight) = FlightResponse(
            flight.id, flight.flightNumber, flight.airlineCode, flight.airlineName,
            flight.origin, flight.destination,
            Instant.ofEpochSecond(flight.departureTime.seconds, flight.departureTime.nanos.toLong()),
            Instant.ofEpochSecond(flight.arrivalTime.seconds, flight.arrivalTime.nanos.toLong()),
            flight.totalSeats, flight.availableSeats, flight.priceMinor, flight.status.name
        )
    }
}
