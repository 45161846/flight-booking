package com.example.flightbooking.booking

import com.example.flightbooking.contract.*
import io.grpc.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class GrpcFlightGatewayTest {
    private var server: Server? = null
    private var channel: ManagedChannel? = null

    private fun gateway(service: BindableService, deadline: Long = 1000, metadata: MutableList<String> = mutableListOf()): GrpcFlightGateway {
        val auth = object : ServerInterceptor {
            override fun <ReqT : Any?, RespT : Any?> interceptCall(call: ServerCall<ReqT, RespT>, headers: Metadata,
                next: ServerCallHandler<ReqT, RespT>): ServerCall.Listener<ReqT> {
                metadata.add(headers.get(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER)) ?: "")
                return next.startCall(call, headers)
            }
        }
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).intercept(auth).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        return GrpcFlightGateway(GrpcClientConfiguration().flightStub(channel!!, "test-secret"),
            ResilientCalls(FlightCircuitBreaker(), 3, 0) {}, deadline)
    }

    @AfterEach fun close() { channel?.shutdownNow(); server?.shutdownNow() }

    @Test fun `all transport calls include service credentials`() {
        val service = object : FlightServiceGrpc.FlightServiceImplBase() {
            override fun getFlight(request: GetFlightRequest, observer: StreamObserver<Flight>) {
                observer.onNext(Flight.newBuilder().setId(request.flightId).build()); observer.onCompleted()
            }
            override fun searchFlights(request: SearchFlightsRequest, observer: StreamObserver<SearchFlightsResponse>) {
                observer.onNext(SearchFlightsResponse.getDefaultInstance()); observer.onCompleted()
            }
            override fun reserveSeats(request: ReserveSeatsRequest, observer: StreamObserver<SeatReservation>) {
                assertEquals(500000L, request.expectedPriceMinor)
                observer.onNext(SeatReservation.newBuilder().setBookingId(request.bookingId).build()); observer.onCompleted()
            }
            override fun releaseReservation(request: ReleaseReservationRequest, observer: StreamObserver<ReleaseReservationResponse>) {
                observer.onNext(ReleaseReservationResponse.getDefaultInstance()); observer.onCompleted()
            }
        }
        val metadata = mutableListOf<String>()
        val gateway = gateway(service, metadata = metadata)
        gateway.search("SVO", "LED", null)
        gateway.get("flight")
        gateway.reserve("flight", 2, "booking", 500000)
        gateway.release("booking")
        assertEquals(listOf("test-secret", "test-secret", "test-secret", "test-secret"), metadata)
    }

    @Test fun `each retry gets a new deadline`() {
        val attempts = AtomicInteger()
        val deadlines = mutableListOf<Long>()
        val service = object : FlightServiceGrpc.FlightServiceImplBase() {
            override fun getFlight(request: GetFlightRequest, observer: StreamObserver<Flight>) {
                attempts.incrementAndGet()
                deadlines.add(Context.current().deadline.timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS))
                observer.onError(Status.UNAVAILABLE.asRuntimeException())
            }
        }
        val gateway = gateway(service, deadline = 500)
        assertThrows(StatusRuntimeException::class.java) { gateway.get("flight") }
        assertEquals(3, attempts.get())
        assertTrue(deadlines.all { it in 100..500 })
    }

    @Test fun `unresponsive server returns deadline exceeded instead of hanging`() {
        val attempts = AtomicInteger()
        val service = object : FlightServiceGrpc.FlightServiceImplBase() {
            override fun getFlight(request: GetFlightRequest, observer: StreamObserver<Flight>) { attempts.incrementAndGet() }
        }
        val gateway = gateway(service, deadline = 200)
        val failure = assertThrows(StatusRuntimeException::class.java) { gateway.get("flight") }
        assertEquals(Status.Code.DEADLINE_EXCEEDED, failure.status.code)
        assertEquals(3, attempts.get())
    }
}
