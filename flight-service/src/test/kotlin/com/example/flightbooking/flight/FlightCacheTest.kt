package com.example.flightbooking.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.Base64

class FlightCacheTest {
    @Suppress("UNCHECKED_CAST")
    private val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val redis = mock(StringRedisTemplate::class.java).also { `when`(it.opsForValue()).thenReturn(values) }
    private val cache = FlightCache(redis, 300)

    @Test
    fun `miss reads source and sets TTL`() {
        assertEquals("fresh", cache.get("flight:1:v0", { String(it) }, { it.toByteArray() }) { "fresh" })
        verify(values).set("flight:1:v0", Base64.getEncoder().encodeToString("fresh".toByteArray()), Duration.ofSeconds(300))
    }

    @Test
    fun `hit skips source`() {
        `when`(values.get("key")).thenReturn(Base64.getEncoder().encodeToString("cached".toByteArray()))
        assertEquals("cached", cache.get("key", { String(it) }, { it.toByteArray() }) { error("source must not be called") })
        verify(values, never()).set(anyString(), anyString(), any(Duration::class.java))
    }

    @Test
    fun `Redis outage falls back to source`() {
        `when`(values.get("key")).thenThrow(RuntimeException("offline"))
        doThrow(RuntimeException("offline")).`when`(values).set(anyString(), anyString(), any(Duration::class.java))
        assertEquals("fresh", cache.get("key", { String(it) }, { it.toByteArray() }) { "fresh" })
    }

    @Test
    fun `corrupt payload is replaced`() {
        `when`(values.get("key")).thenReturn("???")
        assertEquals("fresh", cache.get("key", { String(it) }, { it.toByteArray() }) { "fresh" })
        verify(values).set("key", Base64.getEncoder().encodeToString("fresh".toByteArray()), Duration.ofSeconds(300))
    }

    @Test
    fun `infinite or excessive TTL is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { FlightCache(redis, 0) }
        assertThrows(IllegalArgumentException::class.java) { FlightCache(redis, 601) }
    }
}
