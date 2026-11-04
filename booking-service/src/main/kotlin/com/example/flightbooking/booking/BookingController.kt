package com.example.flightbooking.booking

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.LocalDate
import java.util.UUID

@RestController
@Validated
class BookingController(private val bookings: BookingService, private val flights: FlightGateway) {
    @GetMapping("/flights")
    fun search(
        @RequestParam @Pattern(regexp = "[A-Z]{3}") origin: String,
        @RequestParam @Pattern(regexp = "[A-Z]{3}") destination: String,
        @RequestParam(required = false) date: String?
    ): List<FlightResponse> {
        if (date != null) {
            require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "date must be YYYY-MM-DD" }
            LocalDate.parse(date)
        }
        return flights.search(origin, destination, date).map(FlightResponse::from)
    }

    @GetMapping("/flights/{id}")
    fun flight(@PathVariable id: UUID): FlightResponse = FlightResponse.from(flights.get(id.toString()))

    @PostMapping("/bookings")
    fun create(@Valid @RequestBody request: CreateBookingRequest): ResponseEntity<Booking> {
        val booking = bookings.create(request)
        return ResponseEntity.created(URI.create("/bookings/${booking.id}")).body(booking)
    }

    @GetMapping("/bookings/{id}") fun get(@PathVariable id: UUID): Booking = bookings.get(id)

    @GetMapping("/bookings") fun list(
        @RequestParam("user_id") @NotBlank @Size(max = 100) userId: String
    ): List<Booking> = bookings.list(userId)

    @PostMapping("/bookings/{id}/cancel") fun cancel(@PathVariable id: UUID): Booking = bookings.cancel(id)
}
