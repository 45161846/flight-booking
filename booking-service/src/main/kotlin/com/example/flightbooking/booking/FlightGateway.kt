package com.example.flightbooking.booking

import com.example.flightbooking.contract.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

interface FlightGateway {
    fun search(origin: String, destination: String, date: String?): List<Flight>
    fun get(id: String): Flight
    fun reserve(flightId: String, count: Int, bookingId: String, priceMinor: Long): SeatReservation
    fun release(bookingId: String)
}

@Configuration
class GrpcClientConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    fun flightChannel(@Value("\${booking.flight-target}") target: String): ManagedChannel =
        ManagedChannelBuilder.forTarget(target).usePlaintext().disableRetry().build()

    @Bean
    fun flightStub(channel: ManagedChannel, @Value("\${booking.api-key}") apiKey: String): FlightServiceGrpc.FlightServiceBlockingStub {
        require(apiKey.isNotBlank()) { "GRPC_API_KEY must not be blank" }
        val metadata = Metadata().apply {
            put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), apiKey)
        }
        return FlightServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }
}

@Component
class GrpcFlightGateway(
    private val stub: FlightServiceGrpc.FlightServiceBlockingStub,
    private val calls: ResilientCalls,
    @Value("\${booking.deadline-ms}") private val deadlineMs: Long
) : FlightGateway {
    init { require(deadlineMs in 1..5_000) { "GRPC_DEADLINE_MS must be 1..5000" } }

    private fun attempt() = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

    override fun search(origin: String, destination: String, date: String?): List<Flight> = calls.execute {
        val request = SearchFlightsRequest.newBuilder().setOrigin(origin).setDestination(destination)
        if (date != null) request.date = date
        attempt().searchFlights(request.build()).flightsList
    }

    override fun get(id: String): Flight = calls.execute {
        attempt().getFlight(GetFlightRequest.newBuilder().setFlightId(id).build())
    }

    override fun reserve(flightId: String, count: Int, bookingId: String, priceMinor: Long): SeatReservation = calls.execute {
        attempt().reserveSeats(ReserveSeatsRequest.newBuilder().setFlightId(flightId).setSeatCount(count)
            .setBookingId(bookingId).setExpectedPriceMinor(priceMinor).build())
    }

    override fun release(bookingId: String) {
        calls.execute {
            attempt().releaseReservation(ReleaseReservationRequest.newBuilder().setBookingId(bookingId).build())
        }
    }
}
