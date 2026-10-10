package com.aurum.edge.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Safe, actionable diagnostics. Never echo endpoint, response body or the user's secret. */
object AiConnectionDiagnostics {
    fun describe(error: Throwable): String = when (error) {
        is UnknownHostException -> "نام دامنهٔ سرویس پیدا نشد (DNS)؛ نشانی HTTPS و دسترسی شبکه را بررسی کنید"
        is SocketTimeoutException -> "مهلت پاسخ سرویس تمام شد؛ اتصال/دسترسی از همین شبکه را بررسی کنید"
        is SSLException -> "گواهی HTTPS یا ارتباط امن تأیید نشد؛ نشانی و ساعت دستگاه را بررسی کنید"
        is ConnectException -> "برقراری اتصال به سرویس ممکن نشد؛ شبکه یا نشانی را بررسی کنید"
        else -> when {
            error.message?.contains("HTTP 401") == true -> "HTTP 401: کلید این سرویس معتبر نیست؛ کلید را فقط روی گوشی بررسی کنید"
            error.message?.contains("HTTP 403") == true -> "HTTP 403: دسترسی سرویس/منطقه مجاز نیست؛ از همین شبکه با ارائه‌دهنده بررسی کنید"
            error.message?.contains("HTTP 404") == true -> "HTTP 404: مسیر API یا نام مدل پیدا نشد؛ قالب و شناسهٔ مدل را بررسی کنید"
            error.message?.contains("HTTP 429") == true -> "HTTP 429: سهمیه یا محدودیت نرخ سرویس؛ بدون ادعای پلن رایگان، وضعیت حساب را بررسی کنید"
            error.message?.contains("HTTP 400") == true -> "HTTP 400: قالب درخواست، نام مدل یا پارامترهای سرویس سازگار نیست"
            error.message?.contains("JSON") == true -> "پاسخ مدل JSON معتبر نداشت؛ قالب API یا سازگاری سرویس را بررسی کنید"
            else -> {
                val code = Regex("HTTP [1-5][0-9]{2}").find(error.message.orEmpty())?.value
                if (code != null) "$code: سرویس پاسخ معتبر نداد؛ وضعیت ارائه‌دهنده را بررسی کنید"
                else "پاسخ معتبر از مدل دریافت نشد؛ اتصال و تنظیمات سرویس را بررسی کنید"
            }
        }
    }
}
