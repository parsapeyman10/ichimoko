package com.aurum.edge.data

import com.aurum.edge.core.Interval

/** A scored historical candidate is not a standing offer. It expires without a new check. */
object ScanFreshness {
    fun current(scannedAt: Long?, interval: Interval, now: Long): Boolean =
        scannedAt != null && now - scannedAt in 0L..(interval.millis * 2 + 90_000L)
}
