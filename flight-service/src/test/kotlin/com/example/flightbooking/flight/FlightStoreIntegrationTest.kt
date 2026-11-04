package com.example.flightbooking.flight

import com.example.flightbooking.contract.FlightStatus
import com.example.flightbooking.contract.ReservationStatus
import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "FLIGHT_TEST_DB_URL", matches = ".+")
class FlightStoreIntegrationTest {
    @Autowired lateinit var store: FlightStore
    @Autowired lateinit var jdbc: JdbcTemplate

    companion object {
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { System.getenv("FLIGHT_TEST_DB_URL") }
            registry.add("spring.datasource.username") { System.getenv("FLIGHT_TEST_DB_USER") ?: "flight" }
            registry.add("spring.datasource.password") { System.getenv("FLIGHT_TEST_DB_PASSWORD") ?: "flight" }
            registry.add("flight.grpc-port") { 0 }
            registry.add("flight.api-key") { "test-key" }
        }
    }

    @Test
    fun `concurrent buyers cannot reserve the same last seat`() {
        val flight = fixture(1)
        val executor = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        try {
            val calls = (1..8).map {
                executor.submit(Callable {
                    gate.await()
                    try { store.reserve(flight, UUID.randomUUID(), 1, 500000); "OK" }
                    catch (e: StatusRuntimeException) { e.status.code.name }
                })
            }
            gate.countDown()
            val outcomes = calls.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == "OK" })
            assertEquals(7, outcomes.count { it == "RESOURCE_EXHAUSTED" })
            assertEquals(0, store.get(flight).availableSeats)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `same booking is idempotent under concurrent duplicate requests`() {
        val flight = fixture(10)
        val booking = UUID.randomUUID()
        val executor = Executors.newFixedThreadPool(6)
        try {
            val reservations = executor.invokeAll((1..6).map { Callable { store.reserve(flight, booking, 2, 500000) } })
                .map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, reservations.map { it.id }.distinct().size)
            assertEquals(8, store.get(flight).availableSeats)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM seat_reservations WHERE booking_id = ?", Int::class.java, booking))
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `release returns seats once and fences delayed reserves`() {
        val flight = fixture(10)
        val booking = UUID.randomUUID()
        store.reserve(flight, booking, 3, null)
        assertTrue(store.release(booking))
        assertFalse(store.release(booking))
        assertEquals(10, store.get(flight).availableSeats)
        assertEquals("RELEASED", jdbc.queryForObject("SELECT status FROM seat_reservations WHERE booking_id = ?", String::class.java, booking))
        assertCode(Status.Code.FAILED_PRECONDITION) { store.reserve(flight, booking, 3, null) }
    }

    @Test
    fun `release of unknown booking prevents future reservation`() {
        val booking = UUID.randomUUID()
        assertFalse(store.release(booking))
        val flight = fixture(1)
        assertCode(Status.Code.FAILED_PRECONDITION) { store.reserve(flight, booking, 1, null) }
        assertEquals(1, store.get(flight).availableSeats)
    }

    @Test
    fun `idempotency key cannot be reused for a different flight or count`() {
        val flight = fixture(10)
        val booking = UUID.randomUUID()
        store.reserve(flight, booking, 1, null)
        assertCode(Status.Code.FAILED_PRECONDITION) { store.reserve(flight, booking, 2, null) }
        assertCode(Status.Code.FAILED_PRECONDITION) { store.reserve(fixture(10), booking, 1, null) }
        assertEquals(9, store.get(flight).availableSeats)
    }

    @Test
    fun `insufficient seats and changed prices roll back all writes`() {
        val flight = fixture(1)
        val booking = UUID.randomUUID()
        val revision = store.revision()
        assertCode(Status.Code.RESOURCE_EXHAUSTED) { store.reserve(flight, booking, 2, null) }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservation_commands WHERE booking_id = ?", Int::class.java, booking))
        assertCode(Status.Code.FAILED_PRECONDITION) { store.reserve(flight, booking, 1, 1) }
        assertEquals(1, store.get(flight).availableSeats)
        assertEquals(revision, store.revision())
    }

    @Test
    fun `every mutation invalidates flight and search revision namespace`() {
        val flight = fixture(3)
        val booking = UUID.randomUUID()
        val start = store.revision()
        val reservation = store.reserve(flight, booking, 1, null)
        assertEquals(ReservationStatus.ACTIVE, reservation.status)
        assertEquals(start + 1, store.revision())
        store.update(flight, 600000, FlightStatus.CANCELLED)
        assertEquals(start + 2, store.revision())
        assertEquals(600000L, store.get(flight).priceMinor)
        store.release(booking)
        assertEquals(start + 3, store.revision())
    }

    @Test
    fun `departed and nonexistent flights cannot be reserved`() {
        val flight = fixture(2)
        store.update(flight, 500000, FlightStatus.DEPARTED)
        assertCode(Status.Code.FAILED_PRECONDITION) { store.reserve(flight, UUID.randomUUID(), 1, null) }
        assertCode(Status.Code.NOT_FOUND) { store.reserve(UUID.randomUUID(), UUID.randomUUID(), 1, null) }
    }

    private fun fixture(seats: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO flights(id, flight_number, airline_code, origin, destination, departure_time,
            arrival_time,total_seats,available_seats,price_minor,status)
            VALUES (?,?,'SU','SVO','LED',NOW() + INTERVAL '60 days',NOW() + INTERVAL '60 days 2 hours',?,?,500000,'SCHEDULED')""",
            id, "T" + id.toString().take(8), seats, seats)
        return id
    }

    private fun assertCode(code: Status.Code, action: () -> Unit) {
        assertEquals(code, assertThrows(StatusRuntimeException::class.java) { action() }.status.code)
    }
}
