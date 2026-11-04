package com.example.flightbooking.flight

import com.example.flightbooking.contract.*
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.DataAccessResourceFailureException
import java.util.UUID

class FlightGrpcValidationTest {
    @Test
    fun `invalid inputs use INVALID_ARGUMENT before touching storage`() {
        withStub { stub, store ->
            val badCalls = listOf<() -> Any>(
                { stub.getFlight(GetFlightRequest.newBuilder().setFlightId("123").build()) },
                { stub.searchFlights(SearchFlightsRequest.newBuilder().setOrigin("svo").setDestination("LED").build()) },
                { stub.searchFlights(SearchFlightsRequest.newBuilder().setOrigin("SVO").setDestination("SVO").build()) },
                { stub.searchFlights(SearchFlightsRequest.newBuilder().setOrigin("SVO").setDestination("LED").setDate("2026-02-30").build()) },
                { stub.searchFlights(SearchFlightsRequest.newBuilder().setOrigin("SVO").setDestination("LED").setDate("").build()) },
                { stub.reserveSeats(validReserve().toBuilder().setSeatCount(0).build()) },
                { stub.reserveSeats(validReserve().toBuilder().setExpectedPriceMinor(0).build()) },
                { stub.releaseReservation(ReleaseReservationRequest.newBuilder().setBookingId("bad").build()) },
                { stub.updateFlight(UpdateFlightRequest.newBuilder().setFlightId(UUID.randomUUID().toString()).setPriceMinor(1).build()) }
            )
            badCalls.forEach { call ->
                assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException::class.java) { call() }.status.code)
            }
            verifyNoInteractions(store)
        }
    }

    @Test
    fun `database outages use UNAVAILABLE and hide storage details`() {
        withStub { stub, store ->
            val request = validReserve()
            `when`(store.reserve(UUID.fromString(request.flightId), UUID.fromString(request.bookingId), 1, 500000))
                .thenThrow(DataAccessResourceFailureException("sensitive database details"))
            val error = assertThrows(StatusRuntimeException::class.java) { stub.reserveSeats(request) }
            assertEquals(Status.Code.UNAVAILABLE, error.status.code)
            assertFalse(error.message!!.contains("sensitive"))
        }
    }

    private fun validReserve(): ReserveSeatsRequest = ReserveSeatsRequest.newBuilder()
        .setFlightId(UUID.randomUUID().toString()).setBookingId(UUID.randomUUID().toString())
        .setSeatCount(1).setExpectedPriceMinor(500000).build()

    private fun withStub(action: (FlightServiceGrpc.FlightServiceBlockingStub, FlightStore) -> Unit) {
        val store = mock(FlightStore::class.java)
        val service = FlightGrpcService(store, mock(FlightCache::class.java))
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try { action(FlightServiceGrpc.newBlockingStub(channel), store) }
        finally { channel.shutdownNow(); server.shutdownNow() }
    }
}
