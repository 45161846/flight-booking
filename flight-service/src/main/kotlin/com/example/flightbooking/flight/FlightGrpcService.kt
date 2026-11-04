package com.example.flightbooking.flight

import com.example.flightbooking.contract.*
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@Service
class FlightGrpcService(private val store: FlightStore, private val cache: FlightCache) : FlightServiceGrpc.FlightServiceImplBase() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun getFlight(request: GetFlightRequest, observer: StreamObserver<Flight>) = respond(observer) {
        val id = uuid(request.flightId, "flight_id")
        val key = "flight:$id:v${store.revision()}"
        cache.get(key, Flight::parseFrom, Flight::toByteArray) { store.get(id) }
    }

    override fun searchFlights(request: SearchFlightsRequest, observer: StreamObserver<SearchFlightsResponse>) = respond(observer) {
        requireInput(request.origin.matches(Regex("[A-Z]{3}")), "origin must be an uppercase IATA code")
        requireInput(request.destination.matches(Regex("[A-Z]{3}")), "destination must be an uppercase IATA code")
        requireInput(request.origin != request.destination, "origin and destination must differ")
        val date = if (request.hasDate()) {
            requireInput(request.date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")), "date must be YYYY-MM-DD")
            try { LocalDate.parse(request.date).also { requireInput(it.year in 1..9999, "date year is outside supported range") } }
            catch (e: DateTimeParseException) { throw Status.INVALID_ARGUMENT.withDescription("Invalid date").asRuntimeException() }
        } else null
        val key = "search:${request.origin}:${request.destination}:${date ?: "all"}:v${store.revision()}"
        cache.get(key, SearchFlightsResponse::parseFrom, SearchFlightsResponse::toByteArray) {
            store.search(request.origin, request.destination, date)
        }
    }

    override fun reserveSeats(request: ReserveSeatsRequest, observer: StreamObserver<SeatReservation>) = respond(observer) {
        val flight = uuid(request.flightId, "flight_id")
        val booking = uuid(request.bookingId, "booking_id")
        requireInput(request.seatCount > 0, "seat_count must be positive")
        requireInput(!request.hasExpectedPriceMinor() || request.expectedPriceMinor > 0, "expected_price_minor must be positive")
        store.reserve(flight, booking, request.seatCount, if (request.hasExpectedPriceMinor()) request.expectedPriceMinor else null)
    }

    override fun releaseReservation(request: ReleaseReservationRequest, observer: StreamObserver<ReleaseReservationResponse>) = respond(observer) {
        val released = store.release(uuid(request.bookingId, "booking_id"))
        ReleaseReservationResponse.newBuilder().setReleased(released).build()
    }

    override fun updateFlight(request: UpdateFlightRequest, observer: StreamObserver<Flight>) = respond(observer) {
        val id = uuid(request.flightId, "flight_id")
        requireInput(request.priceMinor > 0, "price_minor must be positive")
        requireInput(request.status in setOf(FlightStatus.SCHEDULED, FlightStatus.DEPARTED, FlightStatus.CANCELLED, FlightStatus.COMPLETED), "Invalid status")
        store.update(id, request.priceMinor, request.status)
    }

    private fun uuid(value: String, field: String): UUID {
        requireInput(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")), "$field must be a UUID")
        return UUID.fromString(value)
    }

    private fun requireInput(condition: Boolean, message: String) {
        if (!condition) throw Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException()
    }

    private fun <T> respond(observer: StreamObserver<T>, operation: () -> T) {
        try { observer.onNext(operation()); observer.onCompleted() }
        catch (e: StatusRuntimeException) { observer.onError(e) }
        catch (e: StatusException) { observer.onError(e) }
        catch (e: DataAccessException) {
            log.error("Flight database operation failed", e)
            observer.onError(Status.UNAVAILABLE.withDescription("Flight database unavailable").asRuntimeException())
        } catch (e: Exception) {
            log.error("Flight operation failed", e)
            observer.onError(Status.INTERNAL.withDescription("Flight operation failed").asRuntimeException())
        }
    }
}
