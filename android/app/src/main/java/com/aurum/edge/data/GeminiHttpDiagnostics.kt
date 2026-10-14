package com.aurum.edge.data

import kotlinx.serialization.json.*

/** Inspect Google error codes in memory, but NEVER expose raw body/message/metadata/key. */
internal object GeminiHttpDiagnostics {
    enum class Cause(val advice: String) {
        KEY("کلید نامعتبر/منقضی است؛ کلید جدید پروژهٔ درست را فقط در گوشی ذخیره کنید"),
        RESTRICTION("محدودیت کلید با این درخواست سازگار نیست؛ محدودیت API، IP، Android یا HTTP referrer را در پروژه بررسی کنید"),
        SERVICE("Gemini API برای پروژه فعال/مجاز نیست؛ پروژه و فعال‌بودن Generative Language API را بررسی کنید"),
        BILLING("دسترسی صورتحساب/پلن پروژه برقرار نیست؛ وضعیت حساب و Billing را بررسی کنید"),
        REGION("Google صراحتاً محدودیت موقعیت را گزارش کرد؛ شرایط دسترسی رسمی سرویس را بررسی کنید"),
        PERMISSION("اجازهٔ درخواست رد شد؛ علت دقیق مشخص نیست. کلید، پروژه، محدودیت کلید و دسترسی سرویس را بررسی کنید؛ ۴۰۳ به‌تنهایی اثبات محدودیت منطقه نیست"),
        MODEL("مدل یا مسیر پیدا نشد؛ از «دریافت مدل‌های سرویس» مدل قابل‌تولید همین کلید را انتخاب کنید"),
        QUOTA("سهمیه/نرخ درخواست محدود است؛ وضعیت سهمیهٔ همان پروژه و مدل را بررسی کنید"),
        REQUEST("درخواست یا پارامترهای مدل پذیرفته نشد؛ مدل و قالب API را بررسی کنید"),
        OTHER("سرویس پاسخ معتبر نداد؛ وضعیت سرویس و شبکه را بررسی کنید"),
    }
    class ModelUnavailable : IllegalStateException("کلید به فهرست مدل‌ها دسترسی دارد، اما مدل انتخابی قابلیت generateContent ندارد یا در فهرست همین کلید نیست؛ مدل را از فهرست انتخاب کنید")

    enum class Stage(val label: String) { MODELS("دریافت فهرست مدل‌ها"), GENERATION("تولید پاسخ") }
    class Failure(val code: Int, val reason: Cause, val stage: Stage? = null) : IllegalStateException("HTTP $code: ${reason.advice}")

    fun failure(code: Int, body: String, stage: Stage? = null): Failure {
        val error = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val reasons = (error?.get("details") as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
        }.toSet()
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
        val cause = when {
            reasons.any { it in setOf("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_NOT_FOUND", "CREDENTIALS_MISSING") } || code == 401 -> Cause.KEY
            reasons.any { it in setOf("API_KEY_SERVICE_BLOCKED", "API_KEY_HTTP_REFERRER_BLOCKED", "API_KEY_IP_ADDRESS_BLOCKED", "API_KEY_ANDROID_APP_BLOCKED", "API_KEY_IOS_APP_BLOCKED") } -> Cause.RESTRICTION
            "SERVICE_DISABLED" in reasons || "CONSUMER_INVALID" in reasons -> Cause.SERVICE
            "BILLING_DISABLED" in reasons || "BILLING_NOT_ACTIVE" in reasons -> Cause.BILLING
            message.contains("user location is not supported") || message.contains("not available in your country") -> Cause.REGION
            code == 403 -> Cause.PERMISSION
            code == 404 -> Cause.MODEL
            code == 429 -> Cause.QUOTA
            code == 400 -> Cause.REQUEST
            else -> Cause.OTHER
        }
        return Failure(code, cause, stage)
    }
}
