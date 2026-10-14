package com.aurum.edge.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class VenueHttpFailure(val code: Int, val retryAfter: String?) : Exception()

/** Process-wide pacing, not just concurrent-call limits. Route startup/cancellation is NOT
 * a venue ban. Preserve the real reason and Retry-After; a changed route gets a fresh probe. */
internal class VenueRequestPolicy(
    private val route: () -> AppNetwork.Route = { AppNetwork.route.value },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private data class Slot(val mutex: Mutex = Mutex(), var revision: Long = -1,
                            var next: Long = 0, var until: Long = 0, var reason: String = "")
    private val slots = CryptoRadarCandleClient.Venue.entries.associateWith { Slot() }

    suspend fun <T> read(venue: CryptoRadarCandleClient.Venue, request: suspend () -> T): T {
        if (!route().ready) throw DataFeedException("اتصال Tor هنوز آماده نیست؛ درخواستی به صرافی ارسال نشد")
        val slot = slots.getValue(venue)
        if (!slot.mutex.tryLock()) throw PublisherOccupied()
        try {
            val current = route()
            if (!current.ready) throw DataFeedException("اتصال Tor هنوز آماده نیست؛ درخواستی ارسال نشد")
            if (slot.revision != current.revision) {
                slot.revision = current.revision; slot.next = 0; slot.until = 0; slot.reason = ""
            }
            if (clock() < slot.until) throw DataFeedException(
                "${venue.name}: ${slot.reason}؛ تلاش بعدی ${(slot.until - clock() + 999) / 1000} ثانیه")
            val wait = slot.next - clock()
            if (wait > 0) pause(wait)
            if (route() != current) throw DataFeedException("مسیر اتصال تغییر کرد؛ دریافت دوباره لازم است")
            slot.next = clock() + if (venue == CryptoRadarCandleClient.Venue.KRAKEN) 1100L else 350L
            return try { request() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (route() != current) throw DataFeedException("مسیر اتصال تغییر کرد؛ دریافت دوباره لازم است")
                val (reason, backoff) = when (failure) {
                    is VenueHttpFailure -> when (failure.code) {
                        429 -> "محدودیت HTTP 429" to retryDelay(failure.retryAfter, clock())
                        403, 418 -> "منع دسترسی HTTP ${failure.code}" to 300_000L
                        in 500..599 -> "اختلال سرور HTTP ${failure.code}" to 20_000L
                        else -> "HTTP ${failure.code}" to 0L // one missing pair must not block a venue
                    }
                    is IOException -> "خطای اتصال شبکه" to 15_000L
                    else -> throw failure // bad market data is not an HTTP quota failure
                }
                slot.reason = reason; slot.until = clock() + backoff
                throw DataFeedException("${venue.name}: $reason" +
                    if (backoff > 0) "؛ تلاش بعدی ${(backoff + 999) / 1000} ثانیه" else "")
            }
        } finally { slot.mutex.unlock() }
    }

    companion object {
        val shared = VenueRequestPolicy()
        internal fun retryDelay(header: String?, now: Long): Long {
            val seconds = header?.trim()?.toLongOrNull()?.coerceIn(0, 86_400)?.times(1000)
            val date = runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - now }.getOrNull()
            return (seconds ?: date ?: 60_000L).coerceIn(1_000L, 86_400_000L)
        }
    }
}
