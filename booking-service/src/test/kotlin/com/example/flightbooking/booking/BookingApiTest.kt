package com.example.flightbooking.booking

import com.example.flightbooking.contract.Flight
import com.example.flightbooking.contract.FlightStatus
import io.grpc.Status
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant
import java.util.UUID

@WebMvcTest(BookingController::class)
class BookingApiTest {
    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var bookings: BookingService
    @MockitoBean lateinit var flights: FlightGateway
    private val flightId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val bookingId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val validBody = """{"user_id":"user-1","flight_id":"$flightId","passenger_name":"Test Passenger","passenger_email":"test@example.com","seat_count":2}"""

    @Test fun `create returns 201 with snake case money and location`() {
        `when`(bookings.create(CreateBookingRequest("user-1", flightId, "Test Passenger", "test@example.com", 2)))
            .thenReturn(Booking(bookingId, "user-1", flightId, "Test Passenger", "test@example.com", 2,
                1000000L, BookingStatus.CONFIRMED, Instant.parse("2026-01-01T00:00:00Z")))
        mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(validBody))
            .andExpect(status().isCreated).andExpect(header().string("Location", "/bookings/$bookingId"))
            .andExpect(jsonPath("$.total_price_minor").value(1000000))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.created_at").value("2026-01-01T00:00:00Z"))
    }

    @Test fun `invalid email seat count missing fields and unknown fields return 400`() {
        for (body in listOf(validBody.replace("test@example.com", "broken"), validBody.replace("\"seat_count\":2", "\"seat_count\":0"),
            "{}", validBody.dropLast(1) + ",\"surprise\":true}")) {
            mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
        }
    }

    @Test fun `invalid path UUID returns 400`() {
        mvc.perform(get("/bookings/not-a-uuid")).andExpect(status().isBadRequest)
    }

    @Test fun `search validates IATA required parameters and calendar date`() {
        for (url in listOf("/flights?origin=SVO", "/flights?origin=svo&destination=LED",
            "/flights?origin=SVO&destination=LED&date=2026-02-30")) {
            mvc.perform(get(url)).andExpect(status().isBadRequest)
        }
    }

    @Test fun `user id cannot be blank`() {
        mvc.perform(get("/bookings").param("user_id", " ")).andExpect(status().isBadRequest)
    }

    @Test fun `flight proxy exposes JSON representation rather than protobuf internals`() {
        `when`(flights.get(flightId.toString())).thenReturn(Flight.newBuilder().setId(flightId.toString())
            .setFlightNumber("SU1234").setPriceMinor(500000).setStatus(FlightStatus.SCHEDULED).build())
        mvc.perform(get("/flights/$flightId")).andExpect(status().isOk)
            .andExpect(jsonPath("$.price_minor").value(500000)).andExpect(jsonPath("$.status").value("SCHEDULED"))
            .andExpect(jsonPath("$.unknownFields").doesNotExist())
    }

    @Test fun `upstream business errors map to 404 and 409`() {
        doThrow(Status.NOT_FOUND.withDescription("Flight was not found").asRuntimeException()).`when`(flights).get(flightId.toString())
        mvc.perform(get("/flights/$flightId")).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        doThrow(Status.RESOURCE_EXHAUSTED.withDescription("Insufficient seats").asRuntimeException()).`when`(flights).get(flightId.toString())
        mvc.perform(get("/flights/$flightId")).andExpect(status().isConflict)
    }

    @Test fun `unavailable deadline open circuit and service auth have distinct mappings`() {
        val cases = listOf(
            Status.UNAVAILABLE.asRuntimeException() to 503,
            Status.DEADLINE_EXCEEDED.asRuntimeException() to 504,
            CircuitOpenException() to 503,
            Status.UNAUTHENTICATED.withDescription("secret server detail").asRuntimeException() to 502
        )
        for ((failure, expected) in cases) {
            doThrow(failure).`when`(flights).get(flightId.toString())
            mvc.perform(get("/flights/$flightId")).andExpect(status().`is`(expected))
                .andExpect(jsonPath("$.message").exists())
        }
    }
}
