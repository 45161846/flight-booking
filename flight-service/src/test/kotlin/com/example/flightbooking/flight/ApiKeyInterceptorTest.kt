package com.example.flightbooking.flight

import com.example.flightbooking.contract.*
import io.grpc.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiKeyInterceptorTest {
    @Test
    fun `all business methods reject missing and wrong API keys`() {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().intercept(ApiKeyInterceptor("secret"))
            .addService(object : FlightServiceGrpc.FlightServiceImplBase() {
                override fun getFlight(request: GetFlightRequest, observer: StreamObserver<Flight>) {
                    observer.onNext(Flight.newBuilder().setId("ok").build()); observer.onCompleted()
                }
            }).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try {
            val stub = FlightServiceGrpc.newBlockingStub(channel)
            val calls = listOf<() -> Any>(
                { stub.getFlight(GetFlightRequest.getDefaultInstance()) },
                { stub.searchFlights(SearchFlightsRequest.getDefaultInstance()) },
                { stub.reserveSeats(ReserveSeatsRequest.getDefaultInstance()) },
                { stub.releaseReservation(ReleaseReservationRequest.getDefaultInstance()) },
                { stub.updateFlight(UpdateFlightRequest.getDefaultInstance()) }
            )
            calls.forEach { call -> assertEquals(Status.Code.UNAUTHENTICATED, assertThrows(StatusRuntimeException::class.java) { call() }.status.code) }
            val wrong = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers("wrong")))
            assertEquals(Status.Code.UNAUTHENTICATED, assertThrows(StatusRuntimeException::class.java) {
                wrong.getFlight(GetFlightRequest.getDefaultInstance())
            }.status.code)
            val valid = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers("secret")))
            assertEquals("ok", valid.getFlight(GetFlightRequest.getDefaultInstance()).id)
        } finally { channel.shutdownNow(); server.shutdownNow() }
    }

    @Test
    fun `blank credential is rejected at startup`() {
        assertThrows(IllegalArgumentException::class.java) { ApiKeyInterceptor(" ") }
    }

    private fun headers(value: String) = Metadata().apply {
        put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), value)
    }
}
