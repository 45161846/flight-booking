package com.example.flightbooking.flight

import com.example.flightbooking.contract.*
import com.google.protobuf.Timestamp
import io.grpc.Status
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

@Repository
class FlightStore(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate) {
    private val select = """SELECT f.*, a.name AS airline_name FROM flights f
        JOIN airlines a ON a.code = f.airline_code"""
    private val flightMapper = RowMapper { rs: ResultSet, _: Int ->
        val departure = rs.getTimestamp("departure_time").toInstant()
        val arrival = rs.getTimestamp("arrival_time").toInstant()
        Flight.newBuilder().setId(rs.getString("id")).setFlightNumber(rs.getString("flight_number"))
            .setAirlineCode(rs.getString("airline_code")).setAirlineName(rs.getString("airline_name"))
            .setOrigin(rs.getString("origin")).setDestination(rs.getString("destination"))
            .setDepartureTime(Timestamp.newBuilder().setSeconds(departure.epochSecond).setNanos(departure.nano))
            .setArrivalTime(Timestamp.newBuilder().setSeconds(arrival.epochSecond).setNanos(arrival.nano))
            .setTotalSeats(rs.getInt("total_seats")).setAvailableSeats(rs.getInt("available_seats"))
            .setPriceMinor(rs.getLong("price_minor")).setStatus(FlightStatus.valueOf(rs.getString("status")))
            .build()
    }
    private val reservationMapper = RowMapper { rs: ResultSet, _: Int ->
        SeatReservation.newBuilder().setId(rs.getString("id")).setFlightId(rs.getString("flight_id"))
            .setBookingId(rs.getString("booking_id")).setSeatCount(rs.getInt("seat_count"))
            .setUnitPriceMinor(rs.getLong("unit_price_minor"))
            .setStatus(ReservationStatus.valueOf(rs.getString("status"))).build()
    }

    fun revision(): Long = jdbc.queryForObject("SELECT revision FROM cache_revision WHERE singleton", Long::class.java)!!

    fun get(id: UUID): Flight = jdbc.query("$select WHERE f.id = ?", flightMapper, id).firstOrNull()
        ?: throw Status.NOT_FOUND.withDescription("Flight not found").asRuntimeException()

    fun search(origin: String, destination: String, date: LocalDate?): SearchFlightsResponse {
        val base = "$select WHERE f.origin = ? AND f.destination = ? AND f.status = 'SCHEDULED'"
        val flights = if (date == null) {
            jdbc.query("$base ORDER BY f.departure_time, f.id", flightMapper, origin, destination)
        } else {
            jdbc.query("$base AND f.departure_date = ? ORDER BY f.departure_time, f.id", flightMapper, origin, destination, date)
        }
        return SearchFlightsResponse.newBuilder().addAllFlights(flights).build()
    }

    fun reserve(flightId: UUID, bookingId: UUID, count: Int, expectedPrice: Long?): SeatReservation = tx.execute {
        if (lockCommand(bookingId)) fail("Booking reservation has already been released")
        val existing = jdbc.query("SELECT * FROM seat_reservations WHERE booking_id = ?", reservationMapper, bookingId).firstOrNull()
        if (existing != null) {
            if (existing.flightId != flightId.toString() || existing.seatCount != count ||
                (expectedPrice != null && existing.unitPriceMinor != expectedPrice)) {
                fail("booking_id was already used with different reservation parameters")
            }
            return@execute existing
        }
        jdbc.queryForList("SELECT id FROM flights WHERE id = ? FOR UPDATE", flightId)
        val flight = get(flightId)
        if (flight.status != FlightStatus.SCHEDULED) fail("Flight is not scheduled")
        if (expectedPrice != null && expectedPrice != flight.priceMinor) fail("Flight price changed; fetch the flight again")
        if (flight.availableSeats < count) throw Status.RESOURCE_EXHAUSTED.withDescription("Not enough seats").asRuntimeException()
        val reservationId = UUID.randomUUID()
        jdbc.update("UPDATE flights SET available_seats = available_seats - ? WHERE id = ?", count, flightId)
        jdbc.update("""INSERT INTO seat_reservations(id, flight_id, booking_id, seat_count, unit_price_minor, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')""", reservationId, flightId, bookingId, count, flight.priceMinor)
        advanceRevision()
        SeatReservation.newBuilder().setId(reservationId.toString()).setFlightId(flightId.toString())
            .setBookingId(bookingId.toString()).setSeatCount(count).setUnitPriceMinor(flight.priceMinor)
            .setStatus(ReservationStatus.ACTIVE).build()
    }!!

    fun release(bookingId: UUID): Boolean = tx.execute {
        val alreadyReleased = lockCommand(bookingId)
        if (alreadyReleased) return@execute false
        val existing = jdbc.query("SELECT * FROM seat_reservations WHERE booking_id = ?", reservationMapper, bookingId).firstOrNull()
        jdbc.update("UPDATE reservation_commands SET released = true WHERE booking_id = ?", bookingId)
        if (existing == null || existing.status != ReservationStatus.ACTIVE) return@execute false
        jdbc.queryForList("SELECT id FROM flights WHERE id = ? FOR UPDATE", UUID.fromString(existing.flightId))
        jdbc.update("UPDATE flights SET available_seats = available_seats + ? WHERE id = ?", existing.seatCount, UUID.fromString(existing.flightId))
        jdbc.update("UPDATE seat_reservations SET status = 'RELEASED' WHERE booking_id = ?", bookingId)
        advanceRevision()
        true
    }!!

    fun update(id: UUID, price: Long, status: FlightStatus): Flight = tx.execute {
        jdbc.queryForList("SELECT id FROM flights WHERE id = ? FOR UPDATE", id)
        get(id)
        jdbc.update("UPDATE flights SET price_minor = ?, status = ? WHERE id = ?", price, status.name, id)
        advanceRevision()
        get(id)
    }!!

    private fun lockCommand(bookingId: UUID): Boolean {
        jdbc.update("INSERT INTO reservation_commands(booking_id) VALUES (?) ON CONFLICT DO NOTHING", bookingId)
        return jdbc.queryForObject("SELECT released FROM reservation_commands WHERE booking_id = ? FOR UPDATE", Boolean::class.java, bookingId)!!
    }

    private fun advanceRevision() { jdbc.update("UPDATE cache_revision SET revision = revision + 1 WHERE singleton") }
    private fun fail(message: String): Nothing = throw Status.FAILED_PRECONDITION.withDescription(message).asRuntimeException()
}
