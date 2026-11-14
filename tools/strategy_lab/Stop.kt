import com.aurum.edge.core.*
import com.aurum.edge.domain.*

fun main() {
    val price = 62000.0
    val atr = price * 0.0022   // ~۰.۲۲٪ ≈ ATR(14) روی ۱۵ دقیقه
    for (iv in listOf(Interval.M15, Interval.M30, Interval.H1)) {
        val b = TradePlanGuard.boundsFor("BTC/USDT", iv)
        val floor = TradePlanGuard.stopDistanceFloor("BTC/USDT", price, atr, iv)
        val ceil = TradePlanGuard.stopDistanceCeiling("BTC/USDT", price, atr, iv)
        println(String.format(java.util.Locale.US,
            "%s: minStopPct=%.3f%% maxStopPct=%.3f%% · کف=%.0f (%.3f%%) · سقف=%.0f (%.3f%%)",
            iv.label, b.minStopPct, b.maxStopPct, floor, floor / price * 100, ceil, ceil / price * 100))
    }
}
