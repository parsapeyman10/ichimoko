# راهنمای جامع ساخت اپلیکیشن با امضای ثابت و نمایش دیتای چارت

این راهنما تمام نیازمندی‌های نصبی برای ساخت APK اندروید با **همان امضای ثابت (Stable Signature)**، حفظ قابلیت آپدیت مستقیم روی گوشی‌های نصب‌شده، و ساختار نمایش **دیتای نماد از گذشته تا لحظهٔ حال** در بخش چارت را شرح می‌دهد.

---

## بخش اول: نیازمندی‌های نصبی برای ساخت اپلیکیشن (Prerequisites)

برای ساخت پروژه روی کامپیوتر شخصی (ویندوز، لینوکس یا مک)، موارد زیر نیاز است:

### ۱. جاوا (Java Development Kit)
- **نسخه مورد نیاز:** **JDK 17** (نسخه‌های پیشنهادی: Eclipse Temurin 17 یا OpenJDK 17).
- **نصب در لینوکس (Ubuntu/Debian):**
  ```bash
  sudo apt update && sudo apt install -y openjdk-17-jdk
  ```
- **نصب در ویندوز:**
  دانلود و نصب JDK 17 از وب‌سایت [Adoptium Temurin](https://adoptium.net/temurin/releases/?version=17) و تنظیم متغیر `JAVA_HOME`.
- **نصب در مک:**
  ```bash
  brew install openjdk@17
  ```

### ۲. ابزارهای اندروید (Android SDK & Build Tools)
- **نسخه پلتفرم اندروید:** Android SDK Platform 35 (Android 15)
- **نسخه ابزارهای بیلد:** Android SDK Build-Tools 35.0.0
- **تنظیم متغیر محیطی:**
  - لینوکس/مک: `export ANDROID_HOME=$HOME/Android/Sdk`
  - ویندوز: متغیر سیستم `ANDROID_HOME` به مسیر نصب SDK (مثلاً `C:\Users\Username\AppData\Local\Android\Sdk`).
- **نصب مستقیم از طریق command-line tools (اختیاری بدون نصب کامل Android Studio):**
  ```bash
  sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
  ```

### ۳. ابزار گریدل (Gradle)
- نیازی به نصب جداگانه گریدل ندارید؛ اسکریپت `gradlew` (در لینوکس/مک) و `gradlew.bat` (در ویندوز) نسخهٔ متناسب گریدل (Gradle 8.9) را خودکار دانلود و استفاده می‌کنند.

---

## بخش دوم: ساخت و مدیریت کلید امضا (Keystore) برای حفظ امضای یکسان

### چرا امضای یکسان حیاتی است؟
سیستم‌عامل اندروید هنگام آپدیت یک اپلیکیشن نصب‌شده، بررسی‌های زیر را انجام می‌دهد:
1. **شناسه بسته یکسان:** `applicationId` باید همان `com.aurum.edge` باشد.
2. **امضای گواهی یکسان (Certificate Match):** اثر انگشت گواهی SHA-256 فایل APK جدید باید دقیقاً با نسخهٔ نصب‌شده مطابقت داشته باشد. اگر امضا تغییر کند، خطای `INSTALL_FAILED_UPDATE_INCOMPATIBLE` رخ می‌دهد و کاربر مجبور به حذف اپ و از دست رفتن دیتای ژورنال خواهد شد.
3. **نسخه بالاتر:** مقدار `versionCode` باید اکیداً از نسخهٔ قبلی بزرگتر باشد.

### روش خودکار ساخت کلید امضا

#### در لینوکس / مک:
```bash
./android/tools/generate-keystore.sh
```
این اسکریپت پوشهٔ ایمن `~/aurum-private/aurum-edge.jks` را بیرون از مخزن ایجاد کرده و کلید RSA 3072 با اعتبار ۱۰۰۰۰ روز می‌سازد و مقادیر مورد نیاز برای GitHub Secrets را چاپ می‌کند.

#### در ویندوز (PowerShell):
```powershell
.\android\tools\generate-keystore.ps1
```

#### روش دستی با `keytool`:
```bash
mkdir -p "$HOME/aurum-private"
keytool -genkeypair -v \
  -keystore "$HOME/aurum-private/aurum-edge.jks" \
  -alias aurum-edge \
  -keyalg RSA \
  -keysize 3072 \
  -validity 10000 \
  -storetype JKS
```

> **نکته بسیار مهم امنیتی:** فایل `aurum-edge.jks` و رمزهای آن را هرگز درون پوشه مخزن گیت قرار ندهید و از آن در جای امن بکاپ بگیرید.

---

## بخش سوم: ساخت فایل APK امضاشده (Owner Release Build)

### بیلد در لینوکس / مک:
```bash
./android/tools/build-owner-apk.sh "$HOME/aurum-private/aurum-edge.jks" aurum-edge
```
اسکریپت به صورت امن رمزها را درخواست کرده، تست‌های واحد JVM را اجرا می‌کند و در صورت موفقیت، خروجی `android/app/build/outputs/apk/release/app-release.apk` را با کلید شما امضا و با `apksigner` اعتبارسنجی می‌کند.

### بیلد در ویندوز (PowerShell):
```powershell
.\android\tools\build-owner-apk.ps1 -KeystorePath "$HOME\aurum-private\aurum-edge.jks" -Alias "aurum-edge"
```

---

## بخش چهارم: تنظیم امضای ثابت در گیت‌هاب (GitHub Actions CI)

برای اینکه فایل‌های APK بیلدشده توسط GitHub Actions نیز با همین امضا تولید شوند، در تنظیمات مخزن گیت‌هاب به مسیر **Settings -> Secrets and variables -> Actions** رفته و متغیرهای زیر را ثبت کنید:

1. **`AURUM_RELEASE_KEYSTORE_BASE64`**: رشته Base64 فایل keystore (با دستور `base64 -w 0 aurum-edge.jks`).
2. **`AURUM_RELEASE_STORE_PASSWORD`**: رمز ورود Keystore.
3. **`AURUM_RELEASE_KEY_ALIAS`**: نام مستعار کلید (مثلاً `aurum-edge`).
4. **`AURUM_RELEASE_KEY_PASSWORD`**: رمز کلید.
5. **`AURUM_RELEASE_CERT_SHA256`**: اثر انگشت SHA-256 گواهی (برای اعتبارسنجی امن).

---

## بخش پنجم: معماری نمایش دیتای نماد از گذشته تا لحظهٔ حال در چارت

بخش چارت به گونه‌ای تجهیز شده است که مانند تمام چارت‌ها و پلتفرم‌های ترید استاندارد (مانند TradingView یا متاتریدر)، دیتای کندل‌های گذشته و قیمت‌های لحظهٔ جاری را به طور یکپارچه نمایش دهد:

### ۱. تاریخچهٔ کندلی عمیق (از گذشته):
- چارت تا **۳۰۰۰ کندل گذشته** را از منابع واقعی (Yahoo Finance / Dukascopy / Twelve Data / Nobitex) دریافت و در کش دیتابیس گوشی ذخیره می‌کند.
- کاربر می‌تواند با درگ/اسکرول افقی به عقب در تاریخچه برود و با Pinch-to-zoom مقیاس بزرگ‌نمایی را بین ۲۰ تا ۵۰۰ کندل تغییر دهد.

### ۲. کندل در حال تشکیل و قیمت لحظهٔ حال (Live Forming Bar):
- آخرین کندل به صورت **زنده (Forming Candle)** با دریافت تیک‌های لحظه‌ای ثانیه‌ای از فید عمومی Swissquote / Gold-API / Twelve Data یا Nobitex آپدیت می‌شود.
- نرخ باز شدن (Open)، بالاترین قیمت (High)، پایین‌ترین قیمت (Low)، آخرین قیمت (Close) و حجم (Volume) لحظه به لحظه منعکس می‌شوند.
- **خط و برچسب قیمت لحظه‌ای (Live Price Badge):** یک خط‌چین رنگی متحرک در راستای آخرین قیمت به همراه برچسب خوانا روی محور عمودی قیمت کشیده می‌شود.

### ۳. نوار اطلاعات OHLC لحظه‌ای:
- در بالای پنل چارت، مشخصات دقیق کندل انتخاب‌شده (یا کندل جاری) شامل تاریخ و ساعت، Open، High، Low، Close، درصد تغییرات مثبت/منفی و حجم نمایش داده می‌شود.

### ۴. لایه‌های تحلیلی روی چارت:
- **ایچیموکو (Ichimoku Kinko Hyo):** ابر کومو (Senkou Span A & B)، خطوط تنکان‌سن (Tenkan-sen)، کیجون‌سن (Kijun-sen) و چیکو اسپن (Chikou Span).
- **میانگین‌ها و حجم:** میانگین متحرک نمایی EMA 200، خط سشن VWAP و پنل حجم معاملات (Volume).
- **سطوح تکنیکال و ICT:** سطوح تاییدشدهٔ حمایت/مقاومت (S/R)، نواحی اوردربلاک (Order Block) و شکاف ارزش منصفانه (FVG).
- **سطوح سیگنال:** خطوط تارگت (TP)، حد ضرر (SL) و نقطه ورود (Entry).

### ۵. امکان انتخاب نمای چارت:
- کاربر می‌تواند با یک لمس بین **«چارت زنده و جامع (گذشته تا لحظه حال)»** و **«چارت TradingView»** سوئیچ کند.
