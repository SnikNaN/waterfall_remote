
# ESP8266 LED Controller + Android App

This repo contains:
- `firmware/` – PlatformIO project for ESP8266 (FastLED, preview, LittleFS, OTA + /update).
- `android/` – Android app (Kotlin + Jetpack Compose) with a color wheel and brightness controls.
- GitHub Actions to build both firmware and APK automatically.

## CI (GitHub Actions)

- **Android APK**: `.github/workflows/android.yml`
  - Installs Gradle 8.7, generates Gradle Wrapper, builds `app-debug.apk` and uploads as artifact.
- **Firmware (PlatformIO)**: `.github/workflows/platformio.yml`
  - Builds `.bin`. Optional upload via `/update` or ArduinoOTA (provide secrets).

## Local build

### Firmware
```bash
cd firmware
pio run -e usb
# OTA (optional):
# pio run -e ota -t upload --upload-port led.local
```

### Android
```bash
cd android
# First time on a fresh checkout:
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug
```
APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Configure Wi‑Fi
Edit `firmware/src/main.cpp`:
```cpp
#define WIFI_SSID   "YOUR_WIFI_SSID"
#define WIFI_PASS   "YOUR_WIFI_PASSWORD"
```
(or pass via `-D WIFI_SSID` / `-D WIFI_PASS` in `platformio.ini`).

## REST API
`/select?start=..&end=..&blink=1`, `/set?hex=RRGGBB[&lbright=0..255]`, `/lbright?value=..`, `/brightness?value=..`, `/save`, `/load`, `/cancel`, `/blink`, `/state`, `/reboot`, `/update`.
