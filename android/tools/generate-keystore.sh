#!/usr/bin/env bash
# Script to generate a stable, private release keystore for com.aurum.edge
# Usage: ./generate-keystore.sh [output_path] [key_alias]
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd -P)"
default_dir="$HOME/aurum-private"
default_file="$default_dir/aurum-edge.jks"
default_alias="aurum-edge"

target_file="${1:-$default_file}"
key_alias="${2:-$default_alias}"

# Safety check: keystore MUST be stored outside the repo
case "$(realpath -m "$target_file")" in "$repo"/*)
  echo "خطا: فایل کلید امضا (keystore) باید خارج از پوشه مخزن گیت ذخیره شود تا از انتشار تصادفی جلوگیری شود." >&2
  exit 2;;
esac

mkdir -p "$(dirname "$target_file")"

if [[ -f "$target_file" ]]; then
  echo "فایل $target_file قبلاً وجود دارد."
  read -r -p "آیا می‌خواهید اثر انگشت گواهی موجود را بررسی کنید؟ (y/N): " confirm
  if [[ "$confirm" =~ ^[Yy]$ ]]; then
    keytool -list -v -keystore "$target_file" -alias "$key_alias"
    exit 0
  fi
  echo "عملیات لغو شد تا از رونویسی کلید قبلی جلوگیری شود."
  exit 1
fi

echo "=========================================================="
echo " ساخت کلید امضای ثابت برای اپلیکیشن com.aurum.edge"
echo "=========================================================="
echo "مسیر فایل کلید: $target_file"
echo "نام مستعار کلید (Alias): $key_alias"
echo "الگوریتم: RSA 3072-bit · مدت اعتبار: ۱۰۰۰۰ روز (۲۷ سال)"
echo "----------------------------------------------------------"

keytool -genkeypair -v \
  -keystore "$target_file" \
  -alias "$key_alias" \
  -keyalg RSA \
  -keysize 3072 \
  -validity 10000 \
  -storetype JKS

echo ""
echo "=========================================================="
echo " کلید با موفقیت ساخته شد!"
echo "=========================================================="
echo "برای استخراج اثر انگشت گواهی (SHA-256 Fingerprint):"
cert_sha256=$(keytool -list -v -keystore "$target_file" -alias "$key_alias" 2>/dev/null | grep -i "SHA256:" | head -n 1 | awk '{print $2}' || true)

if [[ -n "$cert_sha256" ]]; then
  echo "SHA-256 Fingerprint: $cert_sha256"
fi

echo ""
echo "برای استفاده در GitHub Actions Secrets:"
echo "1. AURUM_RELEASE_KEYSTORE_BASE64: $(base64 -w 0 "$target_file" 2>/dev/null || base64 "$target_file" | tr -d '\n')"
echo "2. AURUM_RELEASE_KEY_ALIAS: $key_alias"
echo "3. AURUM_RELEASE_STORE_PASSWORD: [رمزی که وارد کردید]"
echo "4. AURUM_RELEASE_KEY_PASSWORD: [رمزی که وارد کردید]"
if [[ -n "$cert_sha256" ]]; then
  echo "5. AURUM_RELEASE_CERT_SHA256: $cert_sha256"
fi
echo "=========================================================="
echo "هشدار: از فایل $target_file و رمزهای آن در جای امن پشتیبان بگیرید."
echo "تمام آپدیت‌های آینده باید با همین کلید امضا شوند تا روی نسخه‌های قبلی نصب شوند."
