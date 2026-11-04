package com.example.flightbooking.flight

import com.example.flightbooking.contract.*
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.MetadataUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real TCP/HTTP2 transport and PostgreSQL, using the production service and auth interceptor. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "FLIGHT_TEST_DB_URL", matches = ".+")
class FlightGrpcIntegrationTest {
    @Autowired lateinit var service: FlightGrpcService
    @Autowired lateinit var jdbc: JdbcTemplate
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private val authorized: FlightServiceGrpc.FlightServiceBlockingStub get() = stub("test-key")

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

    @BeforeEach
    fun startTransport() {
        server = NettyServerBuilder.forPort(0).intercept(ApiKeyInterceptor("test-key")).addService(service).build().start()
        channel = NettyChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
    }

    @AfterEach
    fun stopTransport() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `all five methods reject missing and wrong credentials over TCP`() {
        for (key in listOf(null, "wrong-key")) {
            val unauthenticated = stub(key)
            val calls = listOf<() -> Any>(
                { unauthenticated.getFlight(GetFlightRequest.getDefaultInstance()) },
                { unauthenticated.searchFlights(SearchFlightsRequest.getDefaultInstance()) },
                { unauthenticated.reserveSeats(ReserveSeatsRequest.getDefaultInstance()) },
                { unauthenticated.releaseReservation(ReleaseReservationRequest.getDefaultInstance()) },
                { unauthenticated.updateFlight(UpdateFlightRequest.getDefaultInstance()) }
            )
            calls.forEach { assertCode(Status.Code.UNAUTHENTICATED, it) }
        }
    }

    @Test
    fun `authorized get and optional date search return coherent seeded flights`() {
        val seededId = "11111111-1111-1111-1111-111111111111"
        val flight = get(seededId)
        assertEquals(seededId, flight.id)
        assertEquals("SVO", flight.origin)
        assertEquals("LED", flight.destination)
        assertTrue(flight.priceMinor > 0)
        assertEquals(FlightStatus.SCHEDULED, flight.status)
        assertTrue(flight.arrivalTime.seconds > flight.departureTime.seconds)
        assertTrue(flight.availableSeats in 0..flight.totalSeats)
        val all = authorized.searchFlights(searchRequest()).flightsList
        assertTrue(all.any { it.id == seededId })
        assertTrue(all.all { it.origin == "SVO" && it.destination == "LED" && it.status == FlightStatus.SCHEDULED })
        val date = Instant.ofEpochSecond(flight.departureTime.seconds).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val dated = authorized.searchFlights(searchRequest().toBuilder().setDate(date).build()).flightsList
        assertTrue(dated.any { it.id == seededId })
        assertTrue(dated.all { Instant.ofEpochSecond(it.departureTime.seconds).atZone(ZoneOffset.UTC).toLocalDate().toString() == date })
    }

    @Test
    fun `business and validation error codes survive transport`() {
        assertCode(Status.Code.INVALID_ARGUMENT) {
            authorized.getFlight(GetFlightRequest.newBuilder().setFlightId("invalid").build())
        }
        assertCode(Status.Code.INVALID_ARGUMENT) {
            authorized.searchFlights(searchRequest().toBuilder().setDate("2026-02-30").build())
        }
        val missing = UUID.randomUUID().toString()
        assertCode(Status.Code.NOT_FOUND) { get(missing) }
        assertCode(Status.Code.NOT_FOUND) {
            authorized.reserveSeats(reserveRequest(missing, UUID.randomUUID().toString(), 1))
        }
        assertCode(Status.Code.NOT_FOUND) {
            authorized.updateFlight(UpdateFlightRequest.newBuilder().setFlightId(missing)
                .setPriceMinor(500000).setStatus(FlightStatus.SCHEDULED).build())
        }
        val flight = fixture(1)
        assertCode(Status.Code.RESOURCE_EXHAUSTED) {
            authorized.reserveSeats(reserveRequest(flight, UUID.randomUUID().toString(), 2))
        }
        assertEquals(1, get(flight).availableSeats)
        assertCode(Status.Code.FAILED_PRECONDITION) {
            authorized.reserveSeats(reserveRequest(flight, UUID.randomUUID().toString(), 1)
                .toBuilder().setExpectedPriceMinor(1).build())
        }
    }

    @Test
    fun `reserve retries and repeated release do not duplicate seat changes`() {
        val flight = fixture(5)
        val booking = UUID.randomUUID().toString()
        val request = reserveRequest(flight, booking, 2)
        val first = authorized.reserveSeats(request)
        val repeated = authorized.reserveSeats(request)
        assertEquals(first, repeated)
        assertEquals(ReservationStatus.ACTIVE, first.status)
        assertEquals(500000L, first.unitPriceMinor)
        assertEquals(3, get(flight).availableSeats)
        assertCode(Status.Code.FAILED_PRECONDITION) {
            authorized.reserveSeats(request.toBuilder().setSeatCount(3).build())
        }
        val release = ReleaseReservationRequest.newBuilder().setBookingId(booking).build()
        assertTrue(authorized.releaseReservation(release).released)
        assertFalse(authorized.releaseReservation(release).released)
        assertEquals(5, get(flight).availableSeats)
        assertCode(Status.Code.FAILED_PRECONDITION) { authorized.reserveSeats(request) }
    }

    @Test
    fun `release before reserve fences an unknown booking across network calls`() {
        val booking = UUID.randomUUID().toString()
        val flight = fixture(1)
        assertFalse(authorized.releaseReservation(ReleaseReservationRequest.newBuilder().setBookingId(booking).build()).released)
        assertCode(Status.Code.FAILED_PRECONDITION) {
            authorized.reserveSeats(reserveRequest(flight, booking, 1))
        }
        assertEquals(1, get(flight).availableSeats)
    }

    @Test
    fun `update invalidates flight and search reads through the gRPC API`() {
        val flight = fixture(3)
        assertEquals(500000L, get(flight).priceMinor)
        assertTrue(authorized.searchFlights(searchRequest()).flightsList.any { it.id == flight })
        val changed = authorized.updateFlight(UpdateFlightRequest.newBuilder().setFlightId(flight)
            .setPriceMinor(650000).setStatus(FlightStatus.CANCELLED).build())
        assertEquals(FlightStatus.CANCELLED, changed.status)
        assertEquals(650000L, changed.priceMinor)
        assertEquals(changed, get(flight))
        assertFalse(authorized.searchFlights(searchRequest()).flightsList.any { it.id == flight })
        authorized.updateFlight(UpdateFlightRequest.newBuilder().setFlightId(flight)
            .setPriceMinor(650000).setStatus(FlightStatus.SCHEDULED).build())
        val restored = authorized.searchFlights(searchRequest()).flightsList.single { it.id == flight }
        assertEquals(650000L, restored.priceMinor)
    }

    private fun stub(key: String?): FlightServiceGrpc.FlightServiceBlockingStub {
        val base = FlightServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
        if (key == null) return base
        val metadata = Metadata().apply { put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), key) }
        return base.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    private fun get(id: String): Flight = authorized.withDeadlineAfter(10, TimeUnit.SECONDS)
        .getFlight(GetFlightRequest.newBuilder().setFlightId(id).build())

    private fun searchRequest(): SearchFlightsRequest = SearchFlightsRequest.newBuilder().setOrigin("SVO").setDestination("LED").build()

    private fun reserveRequest(flight: String, booking: String, seats: Int): ReserveSeatsRequest = ReserveSeatsRequest.newBuilder()
        .setFlightId(flight).setBookingId(booking).setSeatCount(seats).setExpectedPriceMinor(500000).build()

    private fun fixture(seats: Int): String {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO flights(id, flight_number, airline_code, origin, destination, departure_time,
            arrival_time,total_seats,available_seats,price_minor,status)
            VALUES (?,?,'SU','SVO','LED',NOW() + INTERVAL '80 days',NOW() + INTERVAL '80 days 2 hours',?,?,500000,'SCHEDULED')""",
            id, "G" + id.toString().take(8), seats, seats)
        // Fixtures bypass the production mutation path; advance its cache namespace too.
        jdbc.update("UPDATE cache_revision SET revision = revision + 1 WHERE singleton")
        return id.toString()
    }

    private fun assertCode(code: Status.Code, action: () -> Any) {
        assertEquals(code, assertThrows(StatusRuntimeException::class.java) { action() }.status.code)
    }
}
