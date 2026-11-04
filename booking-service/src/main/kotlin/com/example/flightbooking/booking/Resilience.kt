package com.example.flightbooking.booking

import io.grpc.Status
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.ArrayDeque

class CircuitOpenException : RuntimeException("Flight service is temporarily unavailable; circuit breaker is open")

internal fun transientFailure(error: Throwable): Boolean = Status.fromThrowable(error).code in
    setOf(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED)

/** Gateway middleware. The breaker counts one logical call, after its retries finish. */
class ResilientCalls(
    private val breaker: FlightCircuitBreaker,
    private val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 100,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    init {
        require(maxAttempts in 1..3) { "GRPC_MAX_ATTEMPTS must be 1..3" }
        require(initialDelayMs in 0..1_000) { "Retry delay must be 0..1000 ms" }
    }
    fun <T> execute(operation: () -> T): T = breaker.execute {
        var attempt = 1
        while (true) {
            try { return@execute operation() }
            catch (error: RuntimeException) {
                if (!transientFailure(error) || attempt >= maxAttempts) throw error
                try { sleeper(initialDelayMs * (1L shl (attempt - 1))) }
                catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw Status.CANCELLED.withDescription("Retry interrupted").withCause(interrupted).asRuntimeException()
                }
                attempt++
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }
}

/** Thread-safe sliding window breaker with exactly one half-open probe. */
class FlightCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val windowSize: Int = 10,
    private val minCalls: Int = 5,
    private val openDurationMs: Long = 10_000,
    private val nanoTime: () -> Long = System::nanoTime
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }
    private val log = LoggerFactory.getLogger(javaClass)
    private var state = State.CLOSED
    private var epoch = 0L
    private var openedAt = 0L
    private val outcomes = ArrayDeque<Boolean>()
    init {
        require(windowSize in 1..10_000)
        require(failureThreshold in 1..windowSize)
        require(minCalls in 1..windowSize)
        require(openDurationMs in 1..3_600_000)
    }
    @Synchronized fun currentState(): State = state

    fun <T> execute(operation: () -> T): T {
        val permit = acquire()
        try {
            val result = operation()
            complete(permit, false)
            return result
        } catch (error: Throwable) {
            // Business failures prove reachability; they never trip the breaker.
            complete(permit, transientFailure(error))
            throw error
        }
    }

    @Synchronized private fun acquire(): Long {
        if (state == State.OPEN) {
            if (nanoTime() - openedAt < openDurationMs * 1_000_000) throw CircuitOpenException()
            transition(State.HALF_OPEN)
            return epoch
        }
        if (state == State.HALF_OPEN) throw CircuitOpenException()
        return epoch
    }

    @Synchronized private fun complete(permit: Long, failure: Boolean) {
        if (permit != epoch) return // Ignore completions admitted in an earlier state.
        if (state == State.HALF_OPEN) {
            if (failure) open() else { outcomes.clear(); transition(State.CLOSED) }
        } else if (state == State.CLOSED) {
            outcomes.addLast(failure)
            if (outcomes.size > windowSize) outcomes.removeFirst()
            if (outcomes.size >= minCalls && outcomes.count { it } >= failureThreshold) open()
        }
    }

    private fun open() { openedAt = nanoTime(); transition(State.OPEN) }
    private fun transition(next: State) {
        log.info("Flight circuit breaker {} -> {}", state, next)
        state = next
        epoch++
    }
}

@Configuration
class ResilienceConfiguration {
    @Bean fun flightCircuitBreaker(
        @Value("\${booking.breaker.failure-threshold}") threshold: Int,
        @Value("\${booking.breaker.window-size}") window: Int,
        @Value("\${booking.breaker.min-calls}") minimum: Int,
        @Value("\${booking.breaker.open-duration-ms}") duration: Long
    ) = FlightCircuitBreaker(threshold, window, minimum, duration)

    @Bean fun resilientCalls(
        breaker: FlightCircuitBreaker,
        @Value("\${booking.max-attempts}") attempts: Int,
        @Value("\${booking.retry-delay-ms}") delay: Long
    ) = ResilientCalls(breaker, attempts, delay)
}
