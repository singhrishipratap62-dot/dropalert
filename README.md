# DropAlert — Offline-Capable Android Drop-Location Alarm

Complete native Android project (Kotlin + Jetpack Compose Material 3 + Room + Foreground Location Service + OSM/OSRM + Native Alarm/Vibration).

## How to Get the Installable `app-debug.apk`

### Method A: Android Studio (1-Click Local Build)
1. Extract `DropAlert_Complete_Android_Project.zip`.
2. Open the extracted `DropAlert` folder in **Android Studio**.
3. Wait for Gradle Sync to finish, then click **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**.
4. Grab the installable APK at `app/build/outputs/apk/debug/app-debug.apk`.

### Method B: Command Line (`gradle` / `./gradlew`)
```bash
gradle wrapper --gradle-version 8.6
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Method C: Cloud Build via GitHub Actions (No Local Android SDK Required)
1. Push this repository to a GitHub repo.
2. Open the **Actions** tab -> **Build DropAlert Android APK**.
3. Download **`DropAlert-debug-apk`** (`app-debug.apk`) directly from the workflow artifacts onto your phone.