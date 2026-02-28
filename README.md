# xiqueer-catch

Android tool for capturing Xiqueer schedule data and exporting CSV that can be imported into WakeUp timetable.

Language: English | [简体中文](README.zh-CN.md)

## Features

- Capture timetable API responses through local VPN interception
- Group captured snapshots by academic year and semester
- Select weekly snapshots and merge into export rows
- Export CSV to the Downloads directory
- Built-in usage guide and log viewer

## Environment

- Android Studio Iguana+ (or latest stable)
- JDK 11
- Android SDK 34
- minSdk 24, targetSdk 34

## Build

```bash
./gradlew assembleDebug
./gradlew test
```

On Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

## Usage

1. Open Xiqueer app, then open this app.
2. Tap `Start Capture` and grant VPN permission.
3. In Xiqueer, clear cache and enter the timetable page.
4. Switch to target semester, refresh week by week.
5. Return to this app and select snapshots.
6. Tap `Export CSV` and import into WakeUp timetable.

## Release APK

- Current packaged APK path: `app/release/喜鹊儿课表导出.apk`
- GitHub release asset naming recommendation: `xiqueer-catch-v<version>.apk`
- GitHub release assets can be uploaded from this file directly.

If you have `apksigner`, you can verify signature locally:

```bash
apksigner verify --print-certs "app/release/喜鹊儿课表导出.apk"
```

## Repository

- GitHub: https://github.com/letr007/xiqueer-catch
