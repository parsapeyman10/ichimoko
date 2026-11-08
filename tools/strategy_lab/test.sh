#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# اجرای محلی «همان» فایل‌های JUnit مخزن، بدون Gradle و بدون مخزن Maven.
#
# چرا؟ CI هشت دقیقه طول می‌کشد و اگر یک تست رفتار اشتباهی را قفل کرده باشد، بهتر
# است همان‌جا و قبل از پوش لو برود. این اسکریپت موتور را یک‌بار کامپایل می‌کند،
# سپس هر فایل تست را با یک شبیه‌ساز کوچک JUnit اجرا می‌کند (tools/strategy_lab/junit_shim).
#
# استفاده: tools/strategy_lab/test.sh [ClassSimpleName ...]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${STRATEGY_LAB_DIR:-/tmp/strategy-lab}"
LAB="$ROOT/tools/strategy_lab"
TEST_SRC="$ROOT/android/app/src/test/java/com/aurum/edge"

# ۱) همان آماده‌سازی آزمایشگاه (JDK + kotlinc + کپی سایه) را انجام بده.
if [[ ! -x "$WORK/out/LabKt.class" && ! -f "$WORK/out/LabKt.class" ]]; then
  "$LAB/run.sh" 100 >/dev/null 2>&1 || true
fi
export JAVA_HOME="$WORK/jdk"; export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="$WORK/kc/bin/kotlinc"
STDLIB="$WORK/kc/lib/kotlin-stdlib.jar"

echo "[test] کامپایل شبیه‌ساز JUnit…"
mkdir -p "$WORK/out"
"$KOTLINC" -nowarn -d "$WORK/out" "$LAB/junit_shim/JUnitShim.kt" "$LAB/junit_shim/Runner.kt" 2>&1 | grep -E "error" || true

echo "[test] کامپایل تست‌های مخزن…"
mapfile -t ALL_TESTS < <(find "$TEST_SRC" -name "*Test.kt" | sort)
COMPILE=()
CLASSES=()
for f in "${ALL_TESTS[@]}"; do
  name="$(basename "$f" .kt)"
  if [[ $# -gt 0 ]]; then
    keep=0; for want in "$@"; do [[ "$name" == "$want" ]] && keep=1; done
    [[ $keep -eq 0 ]] && continue
  fi
  COMPILE+=("$f")
  pkg="$(grep -m1 '^package ' "$f" | awk '{print $2}')"
  CLASSES+=("$pkg.$name")
done

if [[ ${#COMPILE[@]} -eq 0 ]]; then echo "[test] تستی انتخاب نشد"; exit 0; fi
# تست‌ها یک‌جا کامپایل می‌شوند؛ اگر یکی وابسته به ماژول اندرویدی باشد که در این
# محیط قابل کامپایل نیست، همه رد می‌شوند. پس هر فایل جدا کامپایل می‌شود و فقط
# فایل‌هایی که واقعاً کامپایل شده‌اند اجرا می‌شوند (شکست کامپایل صریح گزارش می‌شود).
RUN=()
for f in "${COMPILE[@]}"; do
  name="$(basename "$f" .kt)"
  pkg="$(grep -m1 '^package ' "$f" | awk '{print $2}')"
  log="$(mktemp)"
  if "$KOTLINC" -nowarn -Xfriend-paths="$WORK/out" -cp "$WORK/out:$STDLIB" -d "$WORK/out" "$f" >"$log" 2>&1; then
    RUN+=("$pkg.$name")
  else
    echo "[test] ⚠️  $name در این محیط (بدون اندروید) کامپایل نمی‌شود — CI آن را اجرا می‌کند:"
    grep -E "error" "$log" | head -3 | sed 's/^/        /'
  fi
  rm -f "$log"
done

echo "[test] اجرا…"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$WORK/out:$STDLIB" RunnerKt "${RUN[@]}"
