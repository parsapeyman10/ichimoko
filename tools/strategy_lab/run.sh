#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# آزمایشگاه محلی «استراتژی بیس» — بدون Android SDK و بدون Gradle.
#
# چرا این اسکریپت وجود دارد؟ موتور استراتژی و بک‌تست هیچ وابستگی اندرویدی ندارند
# (به‌جز @Serializable که این‌جا حذف می‌شود)، پس می‌شود همان **کد اپ** را روی JVM
# اجرا کرد و روی سری واقع‌نما اندازه گرفت: نرخ پاس هر شرط، تعداد سیگنال، سرعت،
# و سود/زیان بک‌تست. این تنها راه اندازه‌گیری صادقانه است، چون ./gradlew در این
# محیط کار نمی‌کند و «حس کردن» جای عدد را نمی‌گیرد.
#
# استفاده:  tools/strategy_lab/run.sh [bars] [seed]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/android/app/src/main/java/com/aurum/edge"
WORK="${STRATEGY_LAB_DIR:-/tmp/strategy-lab}"
BARS="${1:-8000}"
SEED="${2:-11}"

mkdir -p "$WORK/shadow" "$WORK/out"

# ۱) JDK — از pypi به‌صورت بستهٔ آماده (بدون apt/root).
if [[ ! -x "$WORK/jdk/bin/java" ]]; then
  echo "[lab] نصب JDK…"
  for attempt in 1 2 3 4 5; do
    rm -rf "$WORK/dl"; mkdir -p "$WORK/dl"
    if pip download --no-deps -d "$WORK/dl" jdk4py >/dev/null 2>&1; then break; fi
    echo "[lab] تلاش $attempt برای JDK ناموفق بود؛ تکرار…"
  done
  rm -rf "$WORK/jdk-tmp"; mkdir -p "$WORK/jdk-tmp"
  unzip -q "$WORK"/dl/jdk4py-*.whl -d "$WORK/jdk-tmp"
  mv "$WORK/jdk-tmp"/jdk4py/java-runtime "$WORK/jdk"
fi
export JAVA_HOME="$WORK/jdk"
export PATH="$JAVA_HOME/bin:$PATH"

# ۲) کامپایلر کاتلین — بستهٔ رسمی JetBrains روی npm.
if [[ ! -x "$WORK/kc/bin/kotlinc" ]]; then
  echo "[lab] نصب کامپایلر کاتلین…"
  ( cd "$WORK" && npm i --silent --registry=https://registry.npmjs.org/ kotlin-compiler >/dev/null )
  ln -sfn "$WORK/node_modules/kotlin-compiler" "$WORK/kc"
fi
KOTLINC="$WORK/kc/bin/kotlinc"
STDLIB="$WORK/kc/lib/kotlin-stdlib.jar"

# ۳) کپی سایه: حذف @Serializable تا کد بدون kotlinx.serialization کامپایل شود.
#    هیچ فایل مخزن تغییر نمی‌کند — فقط یک کپی در /tmp.
echo "[lab] آماده‌سازی منابع…"
rm -rf "$WORK/shadow"; mkdir -p "$WORK/shadow"
FILES=(
  domain/Candle.kt domain/Interval.kt domain/SignalAction.kt
  domain/FeedMode.kt domain/FeedStatus.kt
  core/Models.kt core/V1Universe.kt core/VenueSpecs.kt core/MarketHours.kt core/MarketState.kt
  core/PaperOrderRules.kt core/TradePlanGuard.kt core/HiddenCrossEngine.kt core/ParsaLineTurnEngine.kt core/CompressionExpansionEngine.kt core/NeoWaveEngine.kt
  core/peyman/PeymanCryptoModels.kt core/peyman/PeymanMarketContextEngine.kt
  core/peyman/NeoWaveCountEnsemble.kt core/peyman/CryptoStructuralBreakEngine.kt
  core/peyman/CryptoExecutionQualityGate.kt core/peyman/PeymanCryptoEngine.kt
  core/NeoWaveNeuralAdvisor.kt core/RiskAndCalendarEngine.kt core/SessionExitPlanner.kt
  core/TradingPerformanceAnalytics.kt core/NeuralGatePolicy.kt core/EntrySafetyGate.kt
  core/IchimokuStrategyEngine.kt core/BacktestSeries.kt core/BacktestReplayEngine.kt
  core/BacktestCsvReader.kt core/BacktestCsvLimits.kt core/BacktestTradeArchive.kt
)
for f in "${FILES[@]}"; do
  mkdir -p "$WORK/shadow/$(dirname "$f")"
  # تو رفتگی هم پاک می‌شود: انوتیشن روی فیلد/کلاس تودرتو با فاصله می‌آید و اگر بماند،
  # کامپایل آزمایشگاه با «internal typealias Serializable» می‌شکند.
  sed -e 's/^[[:space:]]*import kotlinx\.serialization\.Serializable$//' \
      -e 's/^[[:space:]]*@Serializable$//' "$SRC/$f" > "$WORK/shadow/$f"
done
# WatchCatalog (وابستگی دادهٔ اپ) با یک فهرست کوچک جایگزین می‌شود.
sed -i 's|com\.aurum\.edge\.data\.WatchCatalog\.scannerSymbols|listOf("BTC/USDT")|' "$WORK/shadow/core/Models.kt"

echo "[lab] کامپایل موتور…"
rm -rf "$WORK/out"; mkdir -p "$WORK/out"
( cd "$WORK/shadow" && "$KOTLINC" -nowarn -d "$WORK/out" $(find . -name '*.kt' | sed 's|^\./||' | tr '\n' ' ') ) 2>&1 | grep -E "error" || true

echo "[lab] کامپایل آزمایشگاه…"
"$KOTLINC" -nowarn -Xfriend-paths="$WORK/out" -cp "$WORK/out:$STDLIB" -d "$WORK/out" \
  "$ROOT"/tools/strategy_lab/*.kt 2>&1 | grep -E "error" || true

echo "[lab] اجرا (bars=$BARS seed=$SEED)…"
MAIN="LabKt"
EXTRA=("${@:3}")
if [[ "${3:-}" == "probe" ]]; then MAIN="ProbeKt"; fi
if [[ "${3:-}" == "tf" ]]; then MAIN="TfKt"; fi
if [[ "${3:-}" == "stop" ]]; then MAIN="StopKt"; fi
if [[ "${3:-}" == "sym" ]]; then MAIN="SymKt"; fi
if [[ "${3:-}" == "robust" ]]; then MAIN="RobustKt"; fi
if [[ "${3:-}" == "btc" ]]; then MAIN="BtcKt"; fi
# حالت «csv»: مسیر فایل‌ها از آرگومان چهارم شروع می‌شود (سوم خودِ کلیدواژه است).
if [[ "${3:-}" == "csv" ]]; then MAIN="CsvLabKt"; EXTRA=("${@:4}"); fi
# چرتکهٔ کامل «پیمان کریپتو» روی هر کندل: راهنمای PeymanLab.kt
if [[ "${3:-}" == "pey" ]]; then MAIN="PeymanLabKt"; EXTRA=("${@:4}"); fi
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/out:$STDLIB" "$MAIN" "$BARS" "$SEED" "${EXTRA[@]}"
