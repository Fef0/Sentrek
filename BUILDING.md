# Building Sentrek

This guide takes you from a fresh clone to an APK on your phone. It works on Windows, macOS and Linux.

## What you need

- **JDK 17 or newer.** If you have Android Studio, its bundled JDK works. On Windows it's at `C:\Program Files\Android\Android Studio\jbr`.
- **The Android SDK** with the Android 37 platform. Android Studio installs this for you. Gradle downloads anything else it needs on the first build.
- **Git**
- About 4 GB of free disk space for Gradle, the SDK and the build output.

You don't need Android Studio itself. The Gradle wrapper in the repository is enough.

## 1. Get the code

```sh
git clone https://github.com/Fef0/Sentrek.git
cd Sentrek
```

## 2. Tell Gradle where the SDK is

Create a file called `local.properties` in the project folder with one line:

```properties
# Windows (note the double backslashes)
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
# macOS
sdk.dir=/Users/you/Library/Android/sdk
# Linux
sdk.dir=/home/you/Android/Sdk
```

Setting the `ANDROID_HOME` environment variable works too. If you open the project in Android Studio, it creates this file for you.

If Gradle says `JAVA_HOME is not set`, point it at your JDK:

```sh
# macOS / Linux
export JAVA_HOME=/path/to/jdk
# Windows, PowerShell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

## 3. Build

```sh
./gradlew assembleDebug        # macOS / Linux
gradlew.bat assembleDebug      # Windows
```

The first build downloads Gradle and the libraries, which can take ten minutes or more. Later builds are much faster.

The APKs end up in `app/build/outputs/apk/debug/`. There's one per processor type, plus a universal one:

| File | For |
|---|---|
| `app-arm64-v8a-debug.apk` | Almost every phone made in the last ten years |
| `app-armeabi-v7a-debug.apk` | Older 32-bit phones |
| `app-x86_64-debug.apk` | The Android emulator on a PC |
| `app-universal-debug.apk` | Any of the above (bigger) |

## 4. Install it

Turn on USB debugging on the phone, connect it, and run:

```sh
./gradlew installDebug
```

Or copy the APK to the phone and open it there. Android will ask you to allow installs from that app.

A debug build can't update a release build from the Releases page, because they're signed with different keys. Uninstall the other one first, after backing up your data in Settings › Data.

## Release builds

Debug builds are fine for trying things out, but they're slower. For real use, build the optimized release version:

```sh
./gradlew assembleRelease
```

Without a signing key, the APKs in `app/build/outputs/apk/release/` come out unsigned, and Android won't install them. To sign them:

1. Create a key once, and keep it safe. Every future update has to be signed with the same key.

   ```sh
   keytool -genkeypair -v -keystore sentrek-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias sentrek
   ```

2. Create `keystore.properties` in the project folder. Git ignores it, so it never gets committed.

   ```properties
   storeFile=/full/path/to/sentrek-release.jks
   storePassword=your store password
   keyAlias=sentrek
   keyPassword=your key password
   ```

3. Run `./gradlew assembleRelease` again.

## Running the tests

```sh
./gradlew test
```

Most of the logic has plain JVM tests and doesn't need a phone. That covers GPX and KML files, routing, the elevation profile, pace, the sun, the weather and the battery forecast. It all lives in `core/geo`.

## How the project is laid out

| Module | What's in it |
|---|---|
| `app` | The main screen, settings, navigation and theme |
| `core/geo` | Plain Kotlin with no Android code: geometry, GPX/KML, routing, terrain, sun and weather maths |
| `core/data` | Settings storage |
| `core/map` | The MapLibre map: style, layers, icons, contour lines |
| `core/ui` | Shared buttons, colours and formatting |
| `feature/maps` | Downloading areas, map files and trail details |
| `feature/record` | GPS, recording, following routes, tracks, places, warnings |
| `feature/planner` | The route planner |
| `baselineprofile` | Generates the startup profile that makes the app open faster |

The map style lives in `core/map/src/main/assets/styles/hiking.json`.

## Optional: refresh the startup profile

After big changes to the screens, regenerate the baseline profile on a connected emulator or phone:

```sh
./gradlew :app:generateReleaseBaselineProfile
```

This installs its own release build, so uninstall any debug build from that device first. The result is committed under `app/src/release/generated/baselineProfiles/`.
