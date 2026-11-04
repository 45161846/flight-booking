package com.example.flightbooking.booking

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class BookingRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper { rs, _ -> Booking(
        rs.getObject("id", UUID::class.java), rs.getString("user_id"),
        rs.getObject("flight_id", UUID::class.java), rs.getString("passenger_name"),
        rs.getString("passenger_email"), rs.getInt("seat_count"), rs.getLong("total_price_minor"),
        BookingStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant()
    ) }

    fun find(id: UUID, lock: Boolean = false): Booking? = jdbc.query(
        "SELECT * FROM bookings WHERE id = ?" + if (lock) " FOR UPDATE" else "", mapper, id
    ).firstOrNull()

    fun list(userId: String): List<Booking> = jdbc.query(
        "SELECT * FROM bookings WHERE user_id = ? ORDER BY created_at DESC, id", mapper, userId
    )

    fun insert(id: UUID, request: CreateBookingRequest, total: Long): Booking = jdbc.query(
        """INSERT INTO bookings(id,user_id,flight_id,passenger_name,passenger_email,seat_count,total_price_minor,status)
           VALUES (?,?,?,?,?,?,?,'CONFIRMED') RETURNING *""", mapper,
        id, request.userId, request.flightId, request.passengerName, request.passengerEmail, request.seatCount, total
    ).single()

    fun markCancelled(id: UUID): Booking = jdbc.query(
        "UPDATE bookings SET status = 'CANCELLED' WHERE id = ? RETURNING *", mapper, id
    ).single()

    fun addCleanup(id: UUID) { jdbc.update("INSERT INTO reservation_cleanup(booking_id) VALUES (?)", id) }
    fun lockCleanup(id: UUID): Boolean = jdbc.queryForList(
        "SELECT booking_id FROM reservation_cleanup WHERE booking_id = ? FOR UPDATE", id
    ).isNotEmpty()
    fun deleteCleanup(id: UUID) { jdbc.update("DELETE FROM reservation_cleanup WHERE booking_id = ?", id) }
    fun nextCleanup(graceMs: Long): UUID? = nextIntent("reservation_cleanup", graceMs)

    fun addCancellation(id: UUID) {
        jdbc.update("INSERT INTO cancellation_intents(booking_id) VALUES (?) ON CONFLICT DO NOTHING", id)
    }
    fun lockCancellation(id: UUID): Boolean = jdbc.queryForList(
        "SELECT booking_id FROM cancellation_intents WHERE booking_id = ? FOR UPDATE", id
    ).isNotEmpty()
    fun deleteCancellation(id: UUID) { jdbc.update("DELETE FROM cancellation_intents WHERE booking_id = ?", id) }
    fun nextCancellation(graceMs: Long): UUID? = nextIntent("cancellation_intents", graceMs)

    private fun nextIntent(table: String, graceMs: Long): UUID? = jdbc.query(
        """SELECT booking_id FROM $table WHERE created_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')
           ORDER BY created_at, booking_id LIMIT 1 FOR UPDATE SKIP LOCKED""",
        RowMapper { rs, _ -> rs.getObject(1, UUID::class.java) }, graceMs
    ).firstOrNull()
}
