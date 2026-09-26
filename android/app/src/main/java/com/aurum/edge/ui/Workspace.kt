package com.aurum.edge.ui

/** Four research contexts, not four authenticated broker accounts. No prices or journals are shared. */
enum class Workspace(val id: String, val label: String, val sources: String) {
    FOREX("forex", "فارکس · XAU/USD", "نمونهٔ Twelve Data · تقویم Forex Factory · FXStreet/BLS"),
    CRYPTO("crypto", "کریپتوی جهانی", "قیمت عمومی CoinGecko · خبر CoinDesk؛ Binance با سرور اختیاری"),
    NOBITEX("nobitex", "نوبیتکس", "آمار عمومی رسمی نوبیتکس · صفحهٔ رسمی اطلاعیه‌ها (HTML)"),
    IRAN_STOCKS("iran_stocks", "بورس ایران و آگاه", "RSS سنا · تابلوی BrsApi با کلید · پیوند رسمی کدال"),
}
