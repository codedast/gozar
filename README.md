# GOZARTAHRIM | گذرتحریم

### فورکی از v2rayNG برای اندروید، با قابلیت‌های اضافه مخصوص عبور از فیلترینگ
### An Android fork of [v2rayNG](https://github.com/2dust/v2rayNG) with extra features built to bypass internet censorship

[![Telegram Channel](https://img.shields.io/badge/Telegram-gozartahrim-26A5E4?logo=telegram)](https://t.me/gozartahrim)
[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/nougat)
[![Releases](https://img.shields.io/github/v/release/codedast/gozar?logo=github&label=Release)](https://github.com/codedast/gozar/releases)

---

## دانلود / Download

آخرین نسخه را از بخش Releases دانلود کنید:

Download the latest build from the Releases page:

**[https://github.com/codedast/gozar/releases](https://github.com/codedast/gozar/releases)**

| فایل / File | مناسب برای / For |
| --- | --- |
| `GozarTahrim-*-arm64-v8a.apk` | اکثر گوشی‌های امروزی (پیشنهادی) / Most modern phones (recommended) |
| `GozarTahrim-*-armeabi-v7a.apk` | گوشی‌های قدیمی‌تر ۳۲‑بیتی / Older 32-bit devices |
| `GozarTahrim-*-universal.apk` | همه‌ی معماری‌ها، حجیم‌تر / All ABIs, larger file |

حداقل نسخه‌ی اندروید: ۷.۰ (API 24) — Minimum Android version: 7.0 (API 24)

---

## قابلیت‌های اضافه‌شده نسبت به v2rayNG اصلی

### ۱. تکه‌تکه‌سازی گذرتحریم (GozarTahrim Fragment)

روی برخی اپراتورهای ایران SNI سرور فیلتر می‌شود و اتصال پیش از برقراری قطع می‌گردد. این قابلیت بسته‌ی `ClientHello` را — که SNI داخل آن است — به تعداد زیادی تکه‌ی تصادفی می‌شکند تا سامانه‌ی DPI نتواند نام دامنه را بازسازی و تشخیص دهد.

**تنظیمات ← GozarTahrim Fragment**

| گزینه | توضیح | پیش‌فرض |
| --- | --- | --- |
| فعال‌سازی گذرتحریم | روشن کردن تکه‌تکه‌سازی | خاموش |
| تعداد تکه‌ها | ClientHello به چند تکه شکسته شود | ۶۷ |
| تأخیر بین تکه‌ها | فاصله‌ی زمانی هر تکه (میلی‌ثانیه) | ۱ |
| موتور تکه‌کننده‌ی داخلی | استفاده از تکه‌کننده‌ی Kotlin به‌جای موتور Xray (آزمایشی) | خاموش |

مقادیر «تعداد تکه‌ها» و «تأخیر» روی موتور fragment داخلی Xray نگاشت می‌شوند (`length=1-3`، `maxSplit=تعداد تکه‌ها`، `interval=تأخیر`) — همان الگوی random-chunk پروژه‌ی اصلی گذرتحریم، ولی داخل هسته‌ی Go که پایدارتر و سریع‌تر است.

> گزینه‌ی «موتور تکه‌کننده‌ی داخلی» فعلاً آزمایشی است: پروکسی محلی اجرا می‌شود، ولی مسیر داده‌ی اصلی همچنان از موتور Xray عبور می‌کند.

### ۲. یافتن آی‌پی جایگزین (Alt IP Finder)

وقتی آی‌پی سرور VLESS پشت Cloudflare کند یا فیلتر شده باشد، می‌توانید آی‌پی‌های جایگزین پیدا کنید.

**منوی کشویی (☰) ← یافتن آی‌پی جایگزین**

- آی‌پی از دو منبع جمع می‌شود: **محدوده‌های رسمی IPv4 کلادفلر** و **جستجوی FOFA**
- پرست کشورها با اولویت: ★ ایران، آمریکا، ترکیه، آلمان، انگلیس، چین + حدود ۵۰ کشور دیگر
- عبارت جستجوی FOFA قابل ویرایش دستی است
- هر آی‌پی با **اتصال TCP و دست‌دادن کامل TLS** روی SNI اصلی سرور تست و تأخیرش اندازه‌گیری می‌شود
- آی‌پی‌های سالم در یک گروه ساب‌اسکریپشن جدید با نام `{۴ حرف اول} IPs` ذخیره می‌شوند؛ منبع هر آی‌پی با `CL` (کلادفلر) یا `F` (فوفا) مشخص می‌شود
- SNI و Host دست‌نخورده می‌مانند، پس TLS همچنان دامنه‌ی اصلی را هدف می‌گیرد

> چون دسترسی به `cloudflare.com` و `fofa.info` معمولاً فیلتر است، ابتدا به یک سرور سالم وصل شوید؛ برنامه درخواست‌ها را از تونل خودتان عبور می‌دهد.

### ۳. اتصال خودکار به بهترین سرور

برای هر گروه ساب‌اسکریپشن **جداگانه** فعال می‌شود، نه با یک کلید سراسری.

**ویرایش ساب‌اسکریپشن ← «اتصال خودکار به بهترین سرور این گروه»**

برنامه به‌صورت دوره‌ای سرورهای آن گروه را تست تأخیر می‌کند و اگر سروری محسوس‌تر از سرور فعلی بهتر بود، خودکار به آن سوییچ می‌کند.

**تنظیمات ← امکانات گذرتحریم**

| گزینه | پیش‌فرض |
| --- | --- |
| فاصله‌ی هر بررسی (دقیقه) | ۳۰ |
| تعداد سرور تست‌شده در هر بررسی | ۵ |

### ۴. اعلان پست‌های کانال تلگرام

هر پست جدید کانال [@gozartahrim](https://t.me/gozartahrim) به‌صورت نوتیفیکیشن اندرویدی نمایش داده می‌شود؛ با لمس آن، پست باز می‌شود.

- بدون نیاز به بات یا توکن — صفحه‌ی پیش‌نمایش عمومی کانال خوانده می‌شود
- بررسی **بلافاصله پس از هر اتصال موفق** و هر ۱۵ دقیقه در پس‌زمینه
- چون تلگرام فیلتر است، این قابلیت فقط وقتی تونل برقرار باشد کار می‌کند
- نام کانال از تنظیمات قابل تغییر است

### ۵. برندسازی مجدد و بومی‌سازی

نام و آیکون برنامه به گذرتحریم تغییر کرده و تمام صفحه‌های اضافه‌شده به‌طور کامل فارسی و انگلیسی شده‌اند.

---

## Features added on top of upstream v2rayNG

- **GozarTahrim Fragment** — splits the TLS `ClientHello` into many small random chunks so SNI-based DPI cannot reassemble the hostname. Configurable chunk count and inter-chunk delay, mapped onto Xray-core's native fragment engine.
- **Alt IP Finder** — finds alternate Cloudflare front-IPs for a VLESS profile from Cloudflare's official IPv4 ranges and from FOFA search, with priority country presets, TCP+TLS validation against the original SNI, and automatic saving of working IPs into a new subscription group.
- **Auto-connect to the best server** — opt-in per subscription group; periodically delay-tests the group's servers and switches when a meaningfully better one is found.
- **Telegram channel notifications** — native notifications for new posts on the project channel, with no bot or token required.
- **Rebranding & localisation** — GozarTahrim name and icon, plus full Persian/English translation of every added screen.

---

## ساخت از سورس / Building from source

```bash
git clone --recursive https://github.com/codedast/gozar.git
cd gozar
```

نیازمندی‌ها / Requirements:

- JDK 17 یا بالاتر / JDK 17+
- Android SDK: platform-37، build-tools 37.0.0
- Android NDK `29.0.14206865` (فقط برای ساخت libhevtun / only needed for libhevtun)

مراحل / Steps:

```bash
# 1) هسته / core: download libv2ray.aar from the AndroidLibXrayLite releases
#    and place it in V2rayNG/app/libs/

# 2) تونل / tunnel library
export NDK_HOME=/path/to/android-ndk-r29
bash compile-hevtun.sh          # Linux / macOS
bash compile-hevtun-win.sh      # Windows (copies instead of symlinking)
cp -r libs/* V2rayNG/app/libs/

# 3) ساخت APK / build the APK
cd V2rayNG
./gradlew assembleFdroidDebug
```

خروجی در `V2rayNG/app/build/outputs/apk/` قرار می‌گیرد.

> **ساخت از داخل ایران / Building from Iran:** دامنه‌ی `dl.google.com` روی بیشتر اپراتورهای ایران فیلتر است. به همین دلیل مخازن Gradle در `settings.gradle.kts` به آینه‌ی Aliyun هدایت شده‌اند، و بسته‌های SDK/NDK را می‌توانید از آینه‌ی Tencent بگیرید:
> ```bash
> export SDK_TEST_BASE_URL="https://mirrors.cloud.tencent.com/AndroidSDK/"
> ```

---

### Geoip and Geosite

- `geoip.dat` and `geosite.dat` live in `Android/data/com.v2ray.ang/files/assets` (the path may differ on some devices)
- the in-app download feature fetches the enhanced versions from [this repo](https://github.com/Loyalsoldier/v2ray-rules-dat) — it needs a working proxy
- more details in the [upstream wiki](https://github.com/2dust/v2rayNG/wiki)

---

## کامیونیتی / Community

- کانال تلگرام / Telegram channel: [@gozartahrim](https://t.me/gozartahrim)
- خرید سرویس / Buy a service: [@gozartahrimbot](https://t.me/gozartahrimbot)
- پشتیبانی / Support: [@mehrzero](https://t.me/mehrzero)

---

## پروژه‌ی اصلی / Upstream

این پروژه فورکی از [2dust/v2rayNG](https://github.com/2dust/v2rayNG) است. برای مشکلات مربوط به خودِ هسته‌ی برنامه — نه قابلیت‌های اضافه‌شده‌ی این فورک — به مخزن اصلی مراجعه کنید. راهنمای عمومی استفاده در [ویکی پروژه‌ی اصلی](https://github.com/2dust/v2rayNG/wiki) موجود است.

This is a fork of [2dust/v2rayNG](https://github.com/2dust/v2rayNG). For issues in the core app itself — as opposed to this fork's added features — please refer to the upstream repository.

## مجوز / License

مانند پروژه‌ی اصلی تحت [GPL-3.0](LICENSE) منتشر می‌شود. Released under GPL-3.0, same as upstream.
