# Development

## Prerequisites (not installed on the author's machine as of 2026-10-26)
- JDK 17: `winget install Microsoft.OpenJDK.17`
- Android SDK: install Android Studio (`winget install Google.AndroidStudio`) or command-line tools; set `ANDROID_HOME`; accept licenses (`sdkmanager --licenses`).
- Optional: `Google.PlatformTools` (adb) and an emulator for UI tests.

## Local configuration (never committed)
`local.properties`:
```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
GOOGLE_WEB_CLIENT_ID=<web client id>
API_BASE_URL=http://10.0.2.2:8000/
```

## Commands (valid once Phase 1 exists)
```
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest   # needs emulator/device
```

## Troubleshooting
- `SDK location not found`: set `sdk.dir` or `ANDROID_HOME`.
- Wrong JDK: `JAVA_HOME` must point to JDK 17.
