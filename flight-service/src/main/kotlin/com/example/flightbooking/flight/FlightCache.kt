package com.example.flightbooking.flight

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Base64

@Component
class FlightCache(
    private val redis: StringRedisTemplate,
    @Value("\${flight.cache-ttl-seconds}") ttlSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = Duration.ofSeconds(ttlSeconds.also { require(it in 1..600) { "Cache TTL must be 1..600 seconds" } })

    fun <T> get(key: String, decode: (ByteArray) -> T, encode: (T) -> ByteArray, load: () -> T): T {
        val cached = try { redis.opsForValue().get(key) } catch (e: Exception) {
            log.warn("Redis cache unavailable; using PostgreSQL: {}", e.javaClass.simpleName)
            null
        }
        if (cached != null) {
            try {
                val result = decode(Base64.getDecoder().decode(cached))
                log.info("cache hit key={}", key)
                return result
            } catch (e: Exception) {
                log.warn("Ignoring invalid cache entry key={}", key)
            }
        }
        log.info("cache miss key={}", key)
        val value = load()
        try { redis.opsForValue().set(key, Base64.getEncoder().encodeToString(encode(value)), ttl) }
        catch (e: Exception) { log.warn("Redis cache write failed: {}", e.javaClass.simpleName) }
        return value
    }
}
