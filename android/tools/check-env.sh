#!/usr/bin/env bash
# Verifies build prerequisites for com.aurum.edge
set -euo pipefail

echo "=========================================================="
echo " بررسی نیازمندی‌های محیط بیلد اندروید (com.aurum.edge)"
echo "=========================================================="

OK="✓"
FAIL="✗"
WARN="!"

# 1. Java / JDK
if command -v java >/dev/null 2>&1; then
  java_ver=$(java -version 2>&1 | head -n 1)
  echo "[$OK] Java یافت شد: $java_ver"
else
  echo "[$FAIL] Java یافت نشد. نیازمند JDK 17 هستید."
fi

# 2. Keytool
if command -v keytool >/dev/null 2>&1; then
  echo "[$OK] keytool آماده است."
else
  echo "[$FAIL] keytool یافت نشد (معمولاً همراه JDK نصب می‌شود)."
fi

# 3. Android SDK
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$sdk_root" && -d "$sdk_root" ]]; then
  echo "[$OK] ANDROID_HOME تنظیم شده است: $sdk_root"
  if [[ -d "$sdk_root/platforms/android-35" ]]; then
    echo "[$OK] Android Platform SDK 35 نصب است."
  else
    echo "[$WARN] Android Platform SDK 35 یافت نشد. لطفاً نصب کنید: sdkmanager \"platforms;android-35\""
  fi
  if [[ -d "$sdk_root/build-tools/35.0.0" ]]; then
    echo "[$OK] Android Build-tools 35.0.0 نصب است."
  else
    echo "[$WARN] Android Build-tools 35.0.0 یافت نشد. لطفاً نصب کنید: sdkmanager \"build-tools;35.0.0\""
  fi
else
  echo "[$FAIL] متغیر ANDROID_HOME یا ANDROID_SDK_ROOT تنظیم نشده است."
fi

# 4. apksigner
if [[ -n "$sdk_root" && -x "$sdk_root/build-tools/35.0.0/apksigner" ]]; then
  echo "[$OK] apksigner در SDK یافت شد."
elif command -v apksigner >/dev/null 2>&1; then
  echo "[$OK] apksigner در مسیر سیستم یافت شد."
else
  echo "[$WARN] apksigner یافت نشد (برای تأیید امضای نهایی APK نیاز است)."
fi

echo "=========================================================="
