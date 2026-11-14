import java.lang.reflect.Modifier

/**
 * اجراکنندهٔ تست‌ها: کلاس‌های خواسته‌شده را می‌گیرد، هر متد `@org.junit.Test` را
 * روی یک نمونهٔ تازه صدا می‌زند و نتیجه را چاپ می‌کند. خروجی غیرصفر = شکست.
 */
fun main(args: Array<String>) {
    var pass = 0
    val failures = ArrayList<String>()
    for (className in args) {
        val cls = try { Class.forName(className) } catch (e: Throwable) {
            failures += "$className: کلاس پیدا نشد (${e.message})"; continue
        }
        val methods = cls.declaredMethods
            .filter { m -> m.annotations.any { it.annotationClass.simpleName == "Test" } }
            .sortedBy { it.name }
        if (methods.isEmpty()) { println("⚠️  $className: هیچ @Test پیدا نشد"); continue }
        for (m in methods) {
            val instance = cls.getDeclaredConstructor().newInstance()
            val t0 = System.currentTimeMillis()
            try {
                m.isAccessible = true
                m.invoke(instance)
                pass++
                println("✅ ${cls.simpleName} › ${m.name} (${System.currentTimeMillis() - t0}ms)")
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                failures += "${cls.simpleName} › ${m.name}: ${cause::class.java.simpleName}: ${cause.message}"
                println("❌ ${cls.simpleName} › ${m.name}: ${cause.message}")
            }
        }
    }
    println("\n──── ${pass} پاس · ${failures.size} شکست")
    failures.forEach { println("   ✗ $it") }
    if (failures.isNotEmpty()) kotlin.system.exitProcess(1)
}
