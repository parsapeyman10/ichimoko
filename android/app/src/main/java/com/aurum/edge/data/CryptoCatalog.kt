package com.aurum.edge.data

/**
 * Crypto instruments traded entirely ON THE PHONE.
 *
 * Binance's public market endpoints need no key and no account, so the app can chart,
 * signal and paper-trade crypto with nothing else running. There is no backend in this
 * path — the same way the forex side already works.
 *
 * Price scale is the thing that breaks naive ports: BTC ticks in cents around $80,000
 * while DOGE ticks in millionths. Anything that formats or rounds a crypto price has to
 * ask this catalog instead of assuming two decimals.
 */
object CryptoCatalog {

    data class CryptoSymbol(
        /** App-facing id, e.g. "BTC/USDT". */
        val id: String,
        /** Binance ticker, e.g. "BTCUSDT". */
        val binance: String,
        val label: String,
        /** Display precision only, not exchange tick/lot-size authorization. */
        val digits: Int,
    )

    val symbols = listOf(
        CryptoSymbol("BTC/USDT", "BTCUSDT", "بیت‌کوین / تتر", 2),
        CryptoSymbol("ETH/USDT", "ETHUSDT", "اتریوم / تتر", 2),
        CryptoSymbol("BNB/USDT", "BNBUSDT", "بی‌ان‌بی / تتر", 2),
        CryptoSymbol("SOL/USDT", "SOLUSDT", "سولانا / تتر", 2),
        CryptoSymbol("XRP/USDT", "XRPUSDT", "ریپل / تتر", 4),
        CryptoSymbol("ADA/USDT", "ADAUSDT", "کاردانو / تتر", 4),
        CryptoSymbol("DOGE/USDT", "DOGEUSDT", "دوج‌کوین / تتر", 5),
        CryptoSymbol("AVAX/USDT", "AVAXUSDT", "آوالانچ / تتر", 3),
        CryptoSymbol("LINK/USDT", "LINKUSDT", "چین‌لینک / تتر", 3),
        CryptoSymbol("TRX/USDT", "TRXUSDT", "ترون / تتر", 5),
        CryptoSymbol("TON/USDT", "TONUSDT", "تون‌کوین / تتر", 3),
        CryptoSymbol("DOT/USDT", "DOTUSDT", "پولکادات / تتر", 3),
        CryptoSymbol("LTC/USDT", "LTCUSDT", "لایت‌کوین / تتر", 2),
        CryptoSymbol("BCH/USDT", "BCHUSDT", "بیت‌کوین کش / تتر", 2),
        CryptoSymbol("SHIB/USDT", "SHIBUSDT", "شیبا اینو / تتر", 8),
        CryptoSymbol("UNI/USDT", "UNIUSDT", "یونی‌سواپ / تتر", 3),
        CryptoSymbol("ATOM/USDT", "ATOMUSDT", "کازماس / تتر", 3),
        CryptoSymbol("XLM/USDT", "XLMUSDT", "استلار / تتر", 5),
        CryptoSymbol("NEAR/USDT", "NEARUSDT", "نیر / تتر", 3),
        CryptoSymbol("APT/USDT", "APTUSDT", "آپتوس / تتر", 3),
        CryptoSymbol("ARB/USDT", "ARBUSDT", "آربیتروم / تتر", 4),
        CryptoSymbol("OP/USDT", "OPUSDT", "اپتیمیسم / تتر", 4),
        CryptoSymbol("FIL/USDT", "FILUSDT", "فایل‌کوین / تتر", 3),
        CryptoSymbol("ICP/USDT", "ICPUSDT", "اینترنت کامپیوتر / تتر", 3),
        CryptoSymbol("ETC/USDT", "ETCUSDT", "اتریوم کلاسیک / تتر", 2),
        CryptoSymbol("HBAR/USDT", "HBARUSDT", "هدرا / تتر", 5),
        CryptoSymbol("VET/USDT", "VETUSDT", "وی‌چین / تتر", 5),
        CryptoSymbol("INJ/USDT", "INJUSDT", "اینجکتیو / تتر", 3),
        CryptoSymbol("SUI/USDT", "SUIUSDT", "سویی / تتر", 4),
        CryptoSymbol("SEI/USDT", "SEIUSDT", "سی / تتر", 4),
        CryptoSymbol("AAVE/USDT", "AAVEUSDT", "آوه / تتر", 2),
        CryptoSymbol("ALGO/USDT", "ALGOUSDT", "الگورند / تتر", 4),
        CryptoSymbol("PEPE/USDT", "PEPEUSDT", "پپه / تتر", 8),
        CryptoSymbol("WIF/USDT", "WIFUSDT", "داگ‌ویف‌هت / تتر", 4),
        CryptoSymbol("FET/USDT", "FETUSDT", "فچ / تتر", 4),
        CryptoSymbol("RENDER/USDT", "RENDERUSDT", "رندر / تتر", 3),
        CryptoSymbol("TIA/USDT", "TIAUSDT", "سلستیا / تتر", 4),
        CryptoSymbol("LDO/USDT", "LDOUSDT", "لیدو / تتر", 4),
        CryptoSymbol("GRT/USDT", "GRTUSDT", "گراف / تتر", 5),
        CryptoSymbol("SAND/USDT", "SANDUSDT", "سندباکس / تتر", 4),
        CryptoSymbol("MANA/USDT", "MANAUSDT", "دیسنترالند / تتر", 4),
        CryptoSymbol("AXS/USDT", "AXSUSDT", "اکسی اینفینیتی / تتر", 3),
        CryptoSymbol("POL/USDT", "POLUSDT", "پالیگان / تتر", 4),
        CryptoSymbol("RUNE/USDT", "RUNEUSDT", "تورچین / تتر", 4),
        CryptoSymbol("ENA/USDT", "ENAUSDT", "اتنا / تتر", 4),
        CryptoSymbol("1INCH/USDT", "1INCHUSDT", "1inch / تتر", 4),
        CryptoSymbol("ACH/USDT", "ACHUSDT", "Alchemy Pay / تتر", 5),
        CryptoSymbol("ACM/USDT", "ACMUSDT", "AC Milan Fan Token / تتر", 3),
        CryptoSymbol("ACT/USDT", "ACTUSDT", "Act I / تتر", 5),
        CryptoSymbol("ACX/USDT", "ACXUSDT", "Across / تتر", 4),
        CryptoSymbol("ADX/USDT", "ADXUSDT", "AdEx / تتر", 4),
        CryptoSymbol("AERGO/USDT", "AERGOUSDT", "Aergo / تتر", 5),
        CryptoSymbol("AEVO/USDT", "AEVOUSDT", "Aevo / تتر", 4),
        CryptoSymbol("AGLD/USDT", "AGLDUSDT", "Adventure Gold / تتر", 4),
        CryptoSymbol("AI/USDT", "AIUSDT", "Sleepless AI / تتر", 4),
        CryptoSymbol("AKT/USDT", "AKTUSDT", "Akash / تتر", 4),
        CryptoSymbol("ALICE/USDT", "ALICEUSDT", "My Neighbor Alice / تتر", 4),
        CryptoSymbol("ALT/USDT", "ALTUSDT", "AltLayer / تتر", 5),
        CryptoSymbol("AMP/USDT", "AMPUSDT", "Amp / تتر", 6),
        CryptoSymbol("ANKR/USDT", "ANKRUSDT", "Ankr / تتر", 5),
        CryptoSymbol("APE/USDT", "APEUSDT", "ApeCoin / تتر", 4),
        CryptoSymbol("API3/USDT", "API3USDT", "API3 / تتر", 4),
        CryptoSymbol("ARK/USDT", "ARKUSDT", "Ark / تتر", 4),
        CryptoSymbol("ARKM/USDT", "ARKMUSDT", "Arkham / تتر", 4),
        CryptoSymbol("AR/USDT", "ARUSDT", "Arweave / تتر", 3),
        CryptoSymbol("ASTR/USDT", "ASTRUSDT", "Astar / تتر", 5),
        CryptoSymbol("ATA/USDT", "ATAUSDT", "Automata / تتر", 5),
        CryptoSymbol("AUCTION/USDT", "AUCTIONUSDT", "Bounce / تتر", 3),
        CryptoSymbol("AUDIO/USDT", "AUDIOUSDT", "Audius / تتر", 4),
        CryptoSymbol("AVA/USDT", "AVAUSDT", "AVA / تتر", 4),
        CryptoSymbol("BANANA/USDT", "BANANAUSDT", "Banana Gun / تتر", 3),
        CryptoSymbol("BAND/USDT", "BANDUSDT", "Band Protocol / تتر", 4),
        CryptoSymbol("BAT/USDT", "BATUSDT", "Basic Attention Token / تتر", 4),
        CryptoSymbol("BB/USDT", "BBUSDT", "BounceBit / تتر", 4),
        CryptoSymbol("BICO/USDT", "BICOUSDT", "Biconomy / تتر", 4),
        CryptoSymbol("BLUR/USDT", "BLURUSDT", "Blur / تتر", 4),
        CryptoSymbol("BNT/USDT", "BNTUSDT", "Bancor / تتر", 4),
        CryptoSymbol("BONK/USDT", "BONKUSDT", "Bonk / تتر", 8),
        CryptoSymbol("C98/USDT", "C98USDT", "Coin98 / تتر", 4),
        CryptoSymbol("CAKE/USDT", "CAKEUSDT", "PancakeSwap / تتر", 4),
        CryptoSymbol("CELO/USDT", "CELOUSDT", "Celo / تتر", 4),
        CryptoSymbol("CELR/USDT", "CELRUSDT", "Celer / تتر", 5),
        CryptoSymbol("CETUS/USDT", "CETUSUSDT", "Cetus / تتر", 4),
        CryptoSymbol("CFX/USDT", "CFXUSDT", "Conflux / تتر", 4),
        CryptoSymbol("CGPT/USDT", "CGPTUSDT", "ChainGPT / تتر", 4),
        CryptoSymbol("CHESS/USDT", "CHESSUSDT", "Tranchess / تتر", 4),
        CryptoSymbol("CHR/USDT", "CHRUSDT", "Chromia / تتر", 4),
        CryptoSymbol("CHZ/USDT", "CHZUSDT", "Chiliz / تتر", 5),
        CryptoSymbol("CKB/USDT", "CKBUSDT", "Nervos / تتر", 6),
        CryptoSymbol("COMP/USDT", "COMPUSDT", "Compound / تتر", 2),
        CryptoSymbol("COTI/USDT", "COTIUSDT", "COTI / تتر", 5),
        CryptoSymbol("CRV/USDT", "CRVUSDT", "Curve / تتر", 4),
        CryptoSymbol("CTK/USDT", "CTKUSDT", "Shentu / تتر", 4),
        CryptoSymbol("CTSI/USDT", "CTSIUSDT", "Cartesi / تتر", 4),
        CryptoSymbol("CVC/USDT", "CVCUSDT", "Civic / تتر", 4),
        CryptoSymbol("CVX/USDT", "CVXUSDT", "Convex / تتر", 3),
        CryptoSymbol("CYBER/USDT", "CYBERUSDT", "Cyber / تتر", 3),
        CryptoSymbol("DASH/USDT", "DASHUSDT", "Dash / تتر", 2),
        CryptoSymbol("DCR/USDT", "DCRUSDT", "Decred / تتر", 3),
        CryptoSymbol("DEXE/USDT", "DEXEUSDT", "DeXe / تتر", 3),
        CryptoSymbol("DIA/USDT", "DIAUSDT", "DIA / تتر", 4),
        CryptoSymbol("DODO/USDT", "DODOUSDT", "DODO / تتر", 5),
        CryptoSymbol("DUSK/USDT", "DUSKUSDT", "Dusk / تتر", 5),
        CryptoSymbol("DYDX/USDT", "DYDXUSDT", "dYdX / تتر", 4),
        CryptoSymbol("DYM/USDT", "DYMUSDT", "Dymension / تتر", 4),
        CryptoSymbol("EDU/USDT", "EDUUSDT", "Open Campus / تتر", 4),
        CryptoSymbol("EGLD/USDT", "EGLDUSDT", "MultiversX / تتر", 3),
        CryptoSymbol("EIGEN/USDT", "EIGENUSDT", "EigenLayer / تتر", 4),
        CryptoSymbol("ELF/USDT", "ELFUSDT", "aelf / تتر", 4),
        CryptoSymbol("ENJ/USDT", "ENJUSDT", "Enjin / تتر", 4),
        CryptoSymbol("ENS/USDT", "ENSUSDT", "Ethereum Name Service / تتر", 3),
        CryptoSymbol("ETHFI/USDT", "ETHFIUSDT", "Ether.fi / تتر", 4),
        CryptoSymbol("FARM/USDT", "FARMUSDT", "Harvest Finance / تتر", 2),
        CryptoSymbol("FIO/USDT", "FIOUSDT", "FIO Protocol / تتر", 5),
        CryptoSymbol("FLOKI/USDT", "FLOKIUSDT", "Floki / تتر", 8),
        CryptoSymbol("FLOW/USDT", "FLOWUSDT", "Flow / تتر", 4),
        CryptoSymbol("FLUX/USDT", "FLUXUSDT", "Flux / تتر", 4),
        CryptoSymbol("G/USDT", "GUSDT", "G Gravity / تتر", 5),
        CryptoSymbol("GALA/USDT", "GALAUSDT", "Gala / تتر", 5),
        CryptoSymbol("GAS/USDT", "GASUSDT", "Gas / تتر", 3),
        CryptoSymbol("GLM/USDT", "GLMUSDT", "Golem / تتر", 4),
        CryptoSymbol("GMT/USDT", "GMTUSDT", "STEPN / تتر", 5),
        CryptoSymbol("GMX/USDT", "GMXUSDT", "GMX / تتر", 3),
        CryptoSymbol("GPS/USDT", "GPSUSDT", "GoPlus / تتر", 5),
        CryptoSymbol("GTC/USDT", "GTCUSDT", "Gitcoin / تتر", 4),
        CryptoSymbol("HFT/USDT", "HFTUSDT", "Hashflow / تتر", 4),
        CryptoSymbol("HIVE/USDT", "HIVEUSDT", "Hive / تتر", 4),
        CryptoSymbol("HOOK/USDT", "HOOKUSDT", "Hooked / تتر", 4),
        CryptoSymbol("HOT/USDT", "HOTUSDT", "Holo / تتر", 6),
        CryptoSymbol("ICX/USDT", "ICXUSDT", "ICON / تتر", 4),
        CryptoSymbol("ID/USDT", "IDUSDT", "SPACE ID / تتر", 4),
        CryptoSymbol("ILV/USDT", "ILVUSDT", "Illuvium / تتر", 3),
        CryptoSymbol("IMX/USDT", "IMXUSDT", "Immutable / تتر", 4),
        CryptoSymbol("IO/USDT", "IOUSDT", "io.net / تتر", 4),
        CryptoSymbol("IOST/USDT", "IOSTUSDT", "IOST / تتر", 6),
        CryptoSymbol("IOTA/USDT", "IOTAUSDT", "IOTA / تتر", 4),
        CryptoSymbol("IOTX/USDT", "IOTXUSDT", "IoTeX / تتر", 5),
        CryptoSymbol("JASMY/USDT", "JASMYUSDT", "Jasmy / تتر", 6),
        CryptoSymbol("JOE/USDT", "JOEUSDT", "JOE / تتر", 4),
        CryptoSymbol("JST/USDT", "JSTUSDT", "JUST / تتر", 5),
        CryptoSymbol("JTO/USDT", "JTOUSDT", "Jito / تتر", 4),
        CryptoSymbol("JUP/USDT", "JUPUSDT", "Jupiter / تتر", 4),
        CryptoSymbol("KAIA/USDT", "KAIAUSDT", "Kaia / تتر", 4),
        CryptoSymbol("KAS/USDT", "KASUSDT", "Kaspa / تتر", 5),
        CryptoSymbol("KAVA/USDT", "KAVAUSDT", "Kava / تتر", 4),
        CryptoSymbol("KNC/USDT", "KNCUSDT", "Kyber / تتر", 4),
        CryptoSymbol("KSM/USDT", "KSMUSDT", "Kusama / تتر", 3),
        CryptoSymbol("LAYER/USDT", "LAYERUSDT", "Solayer / تتر", 4),
        CryptoSymbol("LPT/USDT", "LPTUSDT", "Livepeer / تتر", 3),
        CryptoSymbol("LQTY/USDT", "LQTYUSDT", "Liquity / تتر", 4),
        CryptoSymbol("LRC/USDT", "LRCUSDT", "Loopring / تتر", 4),
        CryptoSymbol("LSK/USDT", "LSKUSDT", "Lisk / تتر", 4),
        CryptoSymbol("MAGIC/USDT", "MAGICUSDT", "Magic / تتر", 4),
        CryptoSymbol("MANTA/USDT", "MANTAUSDT", "Manta / تتر", 4),
        CryptoSymbol("MASK/USDT", "MASKUSDT", "Mask Network / تتر", 4),
        CryptoSymbol("MAV/USDT", "MAVUSDT", "Maverick / تتر", 5),
        CryptoSymbol("METIS/USDT", "METISUSDT", "Metis / تتر", 3),
        CryptoSymbol("MINA/USDT", "MINAUSDT", "Mina / تتر", 4),
        CryptoSymbol("MOVE/USDT", "MOVEUSDT", "Movement / تتر", 4),
        CryptoSymbol("NEO/USDT", "NEOUSDT", "Neo / تتر", 3),
        CryptoSymbol("NEXO/USDT", "NEXOUSDT", "Nexo / تتر", 4),
        CryptoSymbol("NMR/USDT", "NMRUSDT", "Numeraire / تتر", 3),
        CryptoSymbol("NOT/USDT", "NOTUSDT", "Notcoin / تتر", 6),
        CryptoSymbol("NTRN/USDT", "NTRNUSDT", "Neutron / تتر", 4),
        CryptoSymbol("OG/USDT", "OGUSDT", "OG Fan Token / تتر", 3),
        CryptoSymbol("ONDO/USDT", "ONDOUSDT", "Ondo / تتر", 4),
        CryptoSymbol("ONG/USDT", "ONGUSDT", "Ontology Gas / تتر", 4),
        CryptoSymbol("ONT/USDT", "ONTUSDT", "Ontology / تتر", 4),
        CryptoSymbol("ORDI/USDT", "ORDIUSDT", "ORDI / تتر", 3),
        CryptoSymbol("OSMO/USDT", "OSMOUSDT", "Osmosis / تتر", 4),
        CryptoSymbol("PAXG/USDT", "PAXGUSDT", "PAX Gold / تتر", 2),
        CryptoSymbol("PENDLE/USDT", "PENDLEUSDT", "Pendle / تتر", 4),
        CryptoSymbol("PHA/USDT", "PHAUSDT", "Phala / تتر", 4),
        CryptoSymbol("PIXEL/USDT", "PIXELUSDT", "Pixels / تتر", 5),
        CryptoSymbol("PNUT/USDT", "PNUTUSDT", "Peanut / تتر", 4),
        CryptoSymbol("RVN/USDT", "RVNUSDT", "Ravencoin / تتر", 5),
        CryptoSymbol("S/USDT", "SUSDT", "Sonic / تتر", 4),
        CryptoSymbol("SAGA/USDT", "SAGAUSDT", "Saga / تتر", 4),
        CryptoSymbol("SCR/USDT", "SCRUSDT", "Scroll / تتر", 4),
        CryptoSymbol("SKL/USDT", "SKLUSDT", "SKALE / تتر", 5),
        CryptoSymbol("SNX/USDT", "SNXUSDT", "Synthetix / تتر", 4),
        CryptoSymbol("SOLV/USDT", "SOLVUSDT", "Solv / تتر", 5),
        CryptoSymbol("SSV/USDT", "SSVUSDT", "SSV Network / تتر", 3),
        CryptoSymbol("STG/USDT", "STGUSDT", "Stargate / تتر", 4),
        CryptoSymbol("STORJ/USDT", "STORJUSDT", "Storj / تتر", 4),
        CryptoSymbol("STRK/USDT", "STRKUSDT", "Starknet / تتر", 4),
        CryptoSymbol("STX/USDT", "STXUSDT", "Stacks / تتر", 4),
        CryptoSymbol("SUPER/USDT", "SUPERUSDT", "SuperVerse / تتر", 4),
        CryptoSymbol("SUSHI/USDT", "SUSHIUSDT", "Sushi / تتر", 4),
        CryptoSymbol("THETA/USDT", "THETAUSDT", "Theta / تتر", 4),
        CryptoSymbol("TRUMP/USDT", "TRUMPUSDT", "Official Trump / تتر", 3),
        CryptoSymbol("TURBO/USDT", "TURBOUSDT", "Turbo / تتر", 6),
        CryptoSymbol("TWT/USDT", "TWTUSDT", "Trust Wallet / تتر", 4),
        CryptoSymbol("W/USDT", "WUSDT", "Wormhole / تتر", 5),
        CryptoSymbol("WLD/USDT", "WLDUSDT", "Worldcoin / تتر", 4),
        CryptoSymbol("XEC/USDT", "XECUSDT", "eCash / تتر", 8),
        CryptoSymbol("XTZ/USDT", "XTZUSDT", "Tezos / تتر", 4),
        CryptoSymbol("ZEC/USDT", "ZECUSDT", "Zcash / تتر", 2),
        CryptoSymbol("ZK/USDT", "ZKUSDT", "ZKsync / تتر", 5),
        CryptoSymbol("ZRO/USDT", "ZROUSDT", "LayerZero / تتر", 4),
    )

    /**
     * The handful shown in the watchlist. Everything else stays reachable through the
     * chart's search; a 200-row watchlist is noise, not coverage, and polls the provider
     * for instruments nobody is looking at.
     */
    val watchlistSeed: List<CryptoSymbol> =
        listOf("BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT", "BNB/USDT")
            .mapNotNull { id -> symbols.firstOrNull { it.id == id } }

    private val byId = symbols.associateBy { it.id }

    /** Offline catalog, available without a key/account. Availability and fresh same-pair
     * data must still be verified by each provider; a listing is not a feed guarantee. */
    fun find(id: String): CryptoSymbol? = byId[id.trim().uppercase(java.util.Locale.ROOT)]

    fun isCrypto(id: String): Boolean = find(id) != null

    val ids: List<String> = symbols.map { it.id }

    /**
     * Display decimals for any symbol, crypto or not.
     *
     * Forex keeps the existing behaviour (5 digits, 3 for JPY pairs); crypto uses its own
     * tick scale. Printing BTC to 5 decimals or DOGE to 2 are equally useless.
     */
    fun digitsFor(id: String): Int {
        find(id)?.let { return it.digits }
        val quote = id.substringAfter('/', "").uppercase()
        return when {
            id.startsWith("XAU") || id.startsWith("XPT") || id.startsWith("XPD") -> 2
            id.startsWith("XAG") -> 3
            quote == "JPY" -> 3
            quote.isNotEmpty() -> 5
            else -> 2
        }
    }

    /**
     * Crypto never closes, so the weekend gate must not apply to it. Treating a 24/7 venue
     * as shut would silence the engine for two days a week for no reason.
     */
    fun tradesAroundTheClock(id: String): Boolean = isCrypto(id)
}
