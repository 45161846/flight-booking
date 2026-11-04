package com.example.flightbooking.booking

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class BookingService(
    private val repository: BookingRepository,
    private val flights: FlightGateway,
    transactionManager: PlatformTransactionManager,
    @Value("\${booking.cleanup.grace-ms}") private val graceMs: Long,
    @Value("\${booking.cleanup.batch-size}") private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        timeout = 60
    }
    init { require(graceMs >= 0); require(batchSize in 1..1000) }

    fun get(id: UUID): Booking = repository.find(id)
        ?: throw ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking was not found")

    fun list(userId: String): List<Booking> = repository.list(userId)

    fun create(request: CreateBookingRequest): Booking {
        val id = UUID.randomUUID()
        // Commit before gRPC: a process crash can never leave an untracked reservation.
        tx.executeWithoutResult { repository.addCleanup(id) }
        try {
            return tx.execute {
                if (!repository.lockCleanup(id)) throw ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_EXPIRED", "Booking attempt expired; please try again"
                )
                val flight = flights.get(request.flightId.toString())
                val total = try { Math.multiplyExact(flight.priceMinor, request.seatCount.toLong()) }
                catch (_: ArithmeticException) {
                    throw ApiException(HttpStatus.BAD_REQUEST, "PRICE_OVERFLOW", "Total price is too large")
                }
                flights.reserve(request.flightId.toString(), request.seatCount, id.toString(), flight.priceMinor)
                val booking = repository.insert(id, request, total)
                repository.deleteCleanup(id)
                booking
            }!!
        } catch (failure: RuntimeException) {
            try { compensate(id) }
            catch (cleanupFailure: RuntimeException) {
                log.warn("Reservation {} cleanup deferred: {}", id, cleanupFailure.javaClass.simpleName)
            }
            throw failure
        }
    }

    fun cancel(id: UUID): Booking {
        val existing = get(id)
        if (existing.status == BookingStatus.CANCELLED) return existing
        // Persist cancellation intent before release, independently from final status update.
        tx.executeWithoutResult { repository.addCancellation(id) }
        return tx.execute {
            repository.lockCancellation(id)
            val booking = repository.find(id, lock = true)
                ?: throw ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking was not found")
            val result = if (booking.status == BookingStatus.CONFIRMED) {
                flights.release(id.toString())
                repository.markCancelled(id)
            } else booking
            repository.deleteCancellation(id)
            result
        }!!
    }

    private fun compensate(id: UUID) {
        tx.executeWithoutResult {
            if (repository.lockCleanup(id)) {
                // Release writes a tombstone even if ReserveSeats is still delayed in transit.
                flights.release(id.toString())
                repository.deleteCleanup(id)
            }
        }
    }

    @Scheduled(fixedDelayString = "\${booking.cleanup.interval-ms}")
    fun reconcile() {
        for (attempt in 0 until batchSize) {
            val processed = try { tx.execute {
                val id = repository.nextCleanup(graceMs) ?: return@execute false
                flights.release(id.toString())
                repository.deleteCleanup(id)
                log.info("Compensated abandoned reservation {}", id)
                true
            }!! } catch (failure: RuntimeException) {
                log.warn("Reservation reconciliation deferred: {}", failure.javaClass.simpleName)
                false
            }
            if (!processed) break
        }
        for (attempt in 0 until batchSize) {
            val processed = try { tx.execute {
                val id = repository.nextCancellation(graceMs) ?: return@execute false
                val booking = repository.find(id, lock = true)
                if (booking?.status == BookingStatus.CONFIRMED) {
                    flights.release(id.toString())
                    repository.markCancelled(id)
                }
                repository.deleteCancellation(id)
                log.info("Recovered cancellation {}", id)
                true
            }!! } catch (failure: RuntimeException) {
                log.warn("Cancellation reconciliation deferred: {}", failure.javaClass.simpleName)
                false
            }
            if (!processed) break
        }
    }
}
