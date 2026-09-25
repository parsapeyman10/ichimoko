package com.aurum.edge.ui

/** Four research contexts, not four authenticated broker accounts. No prices or journals are shared. */
enum class Workspace(val id: String, val label: String, val sources: String) {
    FOREX("forex", "فارکس · XAU/USD", "Twelve Data · Forex Factory · خبر اقتصاد"),
    CRYPTO("crypto", "کریپتوی جهانی", "CoinGecko · Binance Spot (سرور اختیاری) · CoinDesk"),
    NOBITEX("nobitex", "نوبیتکس", "آمار عمومی اسپات نوبیتکس · دفتر سفارش BTC/USDT"),
    IRAN_STOCKS("iran_stocks", "بورس ایران و آگاه", "تابلوی TSETMC از BrsApi · گزارش کدال · آساتریدر"),
}
