package com.example.flightbooking.booking

import com.example.flightbooking.contract.Flight
import com.example.flightbooking.contract.FlightStatus
import com.example.flightbooking.contract.SeatReservation
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties = ["booking.api-key=test-key", "booking.cleanup.grace-ms=0", "booking.cleanup.interval-ms=3600000"])
@EnabledIfEnvironmentVariable(named = "BOOKING_TEST_DB_URL", matches = ".+")
class BookingPersistenceTest {
    companion object {
        @JvmStatic @DynamicPropertySource fun database(properties: DynamicPropertyRegistry) {
            properties.add("spring.datasource.url") { System.getenv("BOOKING_TEST_DB_URL") }
            properties.add("spring.datasource.username") { System.getenv("BOOKING_TEST_DB_USER") ?: "booking" }
            properties.add("spring.datasource.password") { System.getenv("BOOKING_TEST_DB_PASSWORD") ?: "booking-local" }
        }
    }
    @Autowired lateinit var service: BookingService
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoBean lateinit var flights: FlightGateway
    private val flightId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private fun request(name: String = "Test Passenger") = CreateBookingRequest("user-1", flightId, name, "test@example.com", 2)

    @BeforeEach fun clean() {
        jdbc.execute("TRUNCATE cancellation_intents, reservation_cleanup, bookings")
        reset(flights)
        `when`(flights.get(flightId.toString())).thenReturn(Flight.newBuilder().setId(flightId.toString())
            .setPriceMinor(500000).setStatus(FlightStatus.SCHEDULED).build())
        `when`(flights.reserve(anyString(), anyInt(), anyString(), anyLong())).thenAnswer {
            SeatReservation.newBuilder().setBookingId(it.getArgument(2)).setUnitPriceMinor(500000).build()
        }
    }

    @Test fun `successful booking snapshots price and atomically removes cleanup intent`() {
        val booking = service.create(request())
        assertEquals(1000000L, booking.totalPriceMinor)
        assertEquals(BookingStatus.CONFIRMED, booking.status)
        assertEquals(booking, service.get(booking.id))
        assertEquals(listOf(booking), service.list("user-1"))
        assertEquals(0, count("reservation_cleanup"))
        verify(flights).reserve(flightId.toString(), 2, booking.id.toString(), 500000)
        verify(flights, never()).release(anyString())
    }

    @Test fun `failed reservation creates no booking and is immediately compensated`() {
        `when`(flights.reserve(anyString(), anyInt(), anyString(), anyLong()))
            .thenThrow(Status.RESOURCE_EXHAUSTED.asRuntimeException())
        assertThrows(StatusRuntimeException::class.java) { service.create(request()) }
        assertEquals(0, count("bookings"))
        assertEquals(0, count("reservation_cleanup"))
        verify(flights).release(anyString())
    }

    @Test fun `failure to save after remote reserve releases seats and leaves no partial booking`() {
        assertThrows(DataIntegrityViolationException::class.java) { service.create(request(" ")) }
        assertEquals(0, count("bookings"))
        assertEquals(0, count("reservation_cleanup"))
        verify(flights).reserve(anyString(), eq(2), anyString(), eq(500000L))
        verify(flights).release(anyString())
    }

    @Test fun `ambiguous reserve failure retains durable intent until release recovers`() {
        `when`(flights.reserve(anyString(), anyInt(), anyString(), anyLong()))
            .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException())
        doThrow(Status.UNAVAILABLE.asRuntimeException()).`when`(flights).release(anyString())
        assertThrows(StatusRuntimeException::class.java) { service.create(request()) }
        assertEquals(0, count("bookings"))
        assertEquals(1, count("reservation_cleanup"))
        doNothing().`when`(flights).release(anyString())
        service.reconcile()
        assertEquals(0, count("reservation_cleanup"))
        assertEquals(0, count("bookings"))
    }

    @Test fun `reconciler releases an intent left by a crashed process`() {
        val abandoned = UUID.randomUUID()
        jdbc.update("INSERT INTO reservation_cleanup(booking_id) VALUES (?)", abandoned)
        service.reconcile()
        verify(flights).release(abandoned.toString())
        assertEquals(0, count("reservation_cleanup"))
    }

    @Test fun `reconciler skips active creation locks without cancelling live reservations`() {
        val reserved = CountDownLatch(1)
        val finish = CountDownLatch(1)
        `when`(flights.reserve(anyString(), anyInt(), anyString(), anyLong())).thenAnswer {
            reserved.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            SeatReservation.newBuilder().setBookingId(it.getArgument(2)).build()
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val creation = pool.submit<Booking> { service.create(request()) }
            assertTrue(reserved.await(5, TimeUnit.SECONDS))
            service.reconcile()
            verify(flights, never()).release(anyString())
            finish.countDown()
            assertEquals(BookingStatus.CONFIRMED, creation.get(5, TimeUnit.SECONDS).status)
            assertEquals(0, count("reservation_cleanup"))
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun `cancellation is idempotent and returns seats only once`() {
        val booking = service.create(request())
        assertEquals(BookingStatus.CANCELLED, service.cancel(booking.id).status)
        assertEquals(BookingStatus.CANCELLED, service.cancel(booking.id).status)
        verify(flights, times(1)).release(booking.id.toString())
        assertEquals(0, count("cancellation_intents"))
    }

    @Test fun `failed cancellation keeps confirmed booking and recovers from durable intent`() {
        val booking = service.create(request())
        doThrow(Status.UNAVAILABLE.asRuntimeException()).`when`(flights).release(booking.id.toString())
        assertThrows(StatusRuntimeException::class.java) { service.cancel(booking.id) }
        assertEquals(BookingStatus.CONFIRMED, service.get(booking.id).status)
        assertEquals(1, count("cancellation_intents"))
        doNothing().`when`(flights).release(booking.id.toString())
        service.reconcile()
        assertEquals(BookingStatus.CANCELLED, service.get(booking.id).status)
        assertEquals(0, count("cancellation_intents"))
    }

    @Test fun `concurrent cancellations serialize through PostgreSQL row locks`() {
        val booking = service.create(request())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Booking> { service.cancel(booking.id) }
            val b = pool.submit<Booking> { service.cancel(booking.id) }
            assertEquals(BookingStatus.CANCELLED, a.get(5, TimeUnit.SECONDS).status)
            assertEquals(BookingStatus.CANCELLED, b.get(5, TimeUnit.SECONDS).status)
            verify(flights, times(1)).release(booking.id.toString())
        } finally { pool.shutdownNow() }
    }

    @Test fun `missing booking reports not found`() {
        val failure = assertThrows(ApiException::class.java) { service.get(UUID.randomUUID()) }
        assertEquals(404, failure.status.value())
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)
}
