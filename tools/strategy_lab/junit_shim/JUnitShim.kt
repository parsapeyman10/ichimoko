// ─────────────────────────────────────────────────────────────────────────────
// شبیه‌ساز کوچک JUnit 4.
//
// چرا؟ در این محیط به مخزن Maven دسترسی نیست، پس برای اینکه بتوانیم **همان فایل‌های
// تست مخزن** را قبل از CI اجرا کنیم، حداقلی از org.junit بازسازی می‌شود. این یک
// جایگزین CI نیست؛ فقط یک تور محافظ محلی برای گیرافتادن خطاهای کامپایل و رفتار
// پیش از ۸ دقیقه انتظار برای رانر.
// ─────────────────────────────────────────────────────────────────────────────
package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Before

class AssertionError2(message: String) : AssertionError(message)

object Assert {
    @JvmStatic fun fail(message: String): Nothing = throw AssertionError(message)
    @JvmStatic fun fail(message: String?) { throw AssertionError(message ?: "fail") }

    @JvmStatic fun assertTrue(condition: Boolean) { if (!condition) throw AssertionError("expected true") }
    @JvmStatic fun assertTrue(message: String, condition: Boolean) { if (!condition) throw AssertionError(message) }
    @JvmStatic fun assertFalse(condition: Boolean) { if (condition) throw AssertionError("expected false") }
    @JvmStatic fun assertFalse(message: String, condition: Boolean) { if (condition) throw AssertionError(message) }

    @JvmStatic fun assertNotNull(actual: Any?) { if (actual == null) throw AssertionError("expected not null") }
    @JvmStatic fun assertNotNull(message: String, actual: Any?) { if (actual == null) throw AssertionError(message) }
    @JvmStatic fun assertNull(actual: Any?) { if (actual != null) throw AssertionError("expected null but was $actual") }
    @JvmStatic fun assertNull(message: String, actual: Any?) { if (actual != null) throw AssertionError(message) }

    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) {
        rejectDoubleWithoutDelta("assertEquals", expected, actual)
        if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertEquals(message: String, expected: Any?, actual: Any?) {
        rejectDoubleWithoutDelta(message, expected, actual)
        if (expected != actual) throw AssertionError("$message: expected <$expected> but was <$actual>")
    }

    /**
     * JUnit واقعی مقایسهٔ دو `Double` بدون `delta` را **رد می‌کند**؛ این شبیه‌ساز قبلاً
     * بی‌صدا قبول می‌کرد و نتیجه‌اش یک CI قرمز با محلی سبز بود (دقیقاً همین اتفاق در
     * `CompressionExpansionEngineTest` افتاد). حالا رفتار شبیه‌ساز مثل JUnit است.
     */
    private fun rejectDoubleWithoutDelta(message: String, expected: Any?, actual: Any?) {
        val e = expected as? Double
        val a = actual as? Double
        if (e != null && a != null) {
            throw AssertionError("$message: Use assertEquals(expected, actual, delta) to compare floating-point numbers")
        }
    }
    @JvmStatic fun assertEquals(expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("expected <$expected> but was <$actual> (delta $delta)")
    }
    @JvmStatic fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) {
        if (kotlin.math.abs(expected - actual) > delta) throw AssertionError("$message: expected <$expected> but was <$actual> (delta $delta)")
    }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) {
        if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertEquals(message: String, expected: Long, actual: Long) {
        if (expected != actual) throw AssertionError("$message: expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertEquals(expected: Int, actual: Int) {
        if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertEquals(message: String, expected: Int, actual: Int) {
        if (expected != actual) throw AssertionError("$message: expected <$expected> but was <$actual>")
    }
    @JvmStatic fun assertNotEquals(unexpected: Any?, actual: Any?) {
        if (unexpected == actual) throw AssertionError("expected not <$unexpected>")
    }
    @JvmStatic fun assertNotEquals(message: String, unexpected: Any?, actual: Any?) {
        if (unexpected == actual) throw AssertionError(message)
    }
    @JvmStatic fun assertSame(expected: Any?, actual: Any?) {
        if (expected !== actual) throw AssertionError("expected same instance")
    }
}
