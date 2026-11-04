package com.example.flightbooking.booking

import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ResilienceTest {
    private fun breaker() = FlightCircuitBreaker(5, 10, 5, 1000)

    @Test fun `transient errors retry with exponential delays and stop at three total attempts`() {
        val delays = mutableListOf<Long>()
        val calls = ResilientCalls(breaker(), 3, 100, delays::add)
        var attempts = 0
        val result = calls.execute {
            attempts++
            if (attempts < 3) throw Status.UNAVAILABLE.asRuntimeException()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(listOf(100L, 200L), delays)
    }

    @Test fun `deadline errors exhaust three attempts`() {
        var attempts = 0
        val calls = ResilientCalls(breaker(), 3, 0) {}
        val failure = assertThrows(StatusRuntimeException::class.java) {
            calls.execute { attempts++; throw Status.DEADLINE_EXCEEDED.asRuntimeException() }
        }
        assertEquals(Status.Code.DEADLINE_EXCEEDED, failure.status.code)
        assertEquals(3, attempts)
    }

    @Test fun `business and authentication errors are never retried`() {
        for (status in listOf(Status.INVALID_ARGUMENT, Status.NOT_FOUND, Status.RESOURCE_EXHAUSTED,
            Status.UNAUTHENTICATED, Status.FAILED_PRECONDITION, Status.PERMISSION_DENIED, Status.INTERNAL)) {
            var attempts = 0
            assertThrows(StatusRuntimeException::class.java) {
                ResilientCalls(breaker(), 3, 0) {}.execute { attempts++; throw status.asRuntimeException() }
            }
            assertEquals(1, attempts, status.code.name)
        }
    }

    @Test fun `retry configuration rejects more than three attempts`() {
        assertThrows(IllegalArgumentException::class.java) { ResilientCalls(breaker(), 4) }
    }

    @Test fun `breaker counts logical calls after retries and immediately rejects while open`() {
        val breaker = FlightCircuitBreaker(2, 4, 2, 1000)
        val calls = ResilientCalls(breaker, 3, 0) {}
        var attempts = 0
        repeat(2) {
            assertThrows(StatusRuntimeException::class.java) {
                calls.execute { attempts++; throw Status.UNAVAILABLE.asRuntimeException() }
            }
        }
        assertEquals(6, attempts)
        assertEquals(FlightCircuitBreaker.State.OPEN, breaker.currentState())
        assertThrows(CircuitOpenException::class.java) { calls.execute { attempts++ } }
        assertEquals(6, attempts)
    }

    @Test fun `server internal failures open breaker without being retried`() {
        for (status in listOf(Status.INTERNAL, Status.UNKNOWN, Status.DATA_LOSS)) {
            val breaker = FlightCircuitBreaker(2, 4, 2, 1000)
            val calls = ResilientCalls(breaker, 3, 0) { throw AssertionError("Server internal failures must not retry") }
            var attempts = 0
            repeat(2) {
                val failure = assertThrows(StatusRuntimeException::class.java) {
                    calls.execute { attempts++; throw status.asRuntimeException() }
                }
                assertEquals(status.code, failure.status.code)
            }
            assertEquals(2, attempts, status.code.name)
            assertEquals(FlightCircuitBreaker.State.OPEN, breaker.currentState(), status.code.name)
            assertThrows(CircuitOpenException::class.java) { calls.execute { attempts++ } }
            assertEquals(2, attempts, status.code.name)
        }
    }

    @Test fun `business errors do not open breaker`() {
        val breaker = FlightCircuitBreaker(1, 1, 1, 1000)
        repeat(10) {
            assertThrows(StatusRuntimeException::class.java) {
                breaker.execute { throw Status.RESOURCE_EXHAUSTED.asRuntimeException() }
            }
        }
        assertEquals(FlightCircuitBreaker.State.CLOSED, breaker.currentState())
    }

    @Test fun `sliding window forgets failures outside the window`() {
        val breaker = FlightCircuitBreaker(2, 3, 3, 1000)
        assertThrows(StatusRuntimeException::class.java) { breaker.execute { throw Status.UNAVAILABLE.asRuntimeException() } }
        repeat(3) { breaker.execute { "ok" } }
        assertThrows(StatusRuntimeException::class.java) { breaker.execute { throw Status.UNAVAILABLE.asRuntimeException() } }
        assertEquals(FlightCircuitBreaker.State.CLOSED, breaker.currentState())
    }

    @Test fun `half-open accepts exactly one concurrent probe and returns closed after success`() {
        val now = AtomicLong()
        val breaker = FlightCircuitBreaker(1, 1, 1, 1000, now::get)
        assertThrows(StatusRuntimeException::class.java) { breaker.execute { throw Status.UNAVAILABLE.asRuntimeException() } }
        now.set(TimeUnit.MILLISECONDS.toNanos(1000))
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val probe = pool.submit<String> { breaker.execute { started.countDown(); check(finish.await(2, TimeUnit.SECONDS)); "ok" } }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(FlightCircuitBreaker.State.HALF_OPEN, breaker.currentState())
            assertThrows(CircuitOpenException::class.java) { breaker.execute { "second probe" } }
            finish.countDown()
            assertEquals("ok", probe.get(2, TimeUnit.SECONDS))
            assertEquals(FlightCircuitBreaker.State.CLOSED, breaker.currentState())
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun `failed half-open probe opens a fresh timeout window`() {
        val now = AtomicLong()
        val breaker = FlightCircuitBreaker(1, 1, 1, 1000, now::get)
        assertThrows(StatusRuntimeException::class.java) { breaker.execute { throw Status.UNAVAILABLE.asRuntimeException() } }
        now.set(TimeUnit.MILLISECONDS.toNanos(1000))
        assertThrows(StatusRuntimeException::class.java) { breaker.execute { throw Status.DEADLINE_EXCEEDED.asRuntimeException() } }
        assertEquals(FlightCircuitBreaker.State.OPEN, breaker.currentState())
        assertThrows(CircuitOpenException::class.java) { breaker.execute { "ok" } }
        now.set(TimeUnit.MILLISECONDS.toNanos(2000))
        assertEquals("ok", breaker.execute { "ok" })
    }

    @Test fun `late successful completion cannot close a breaker opened by another call`() {
        val breaker = FlightCircuitBreaker(1, 1, 1, 1000)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val stale = pool.submit { breaker.execute { started.countDown(); check(finish.await(2, TimeUnit.SECONDS)); "ok" } }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertThrows(StatusRuntimeException::class.java) { breaker.execute { throw Status.UNAVAILABLE.asRuntimeException() } }
            finish.countDown()
            stale.get(2, TimeUnit.SECONDS)
            assertEquals(FlightCircuitBreaker.State.OPEN, breaker.currentState())
        } finally { finish.countDown(); pool.shutdownNow() }
    }
}
