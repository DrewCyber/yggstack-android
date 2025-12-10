# Quick Start Guide - Yggstack Android

## Prerequisites

1. **Android Studio** (latest version recommended)
   - Download from: https://developer.android.com/studio

2. **JDK 17** (for building)
   - Usually included with Android Studio

3. **Android SDK**
   - API 23 (Android 6.0) minimum
   - API 34 (Android 14) target

## Opening the Project

1. Launch Android Studio
2. Select **File → Open**
3. Navigate to: `/Users/atregu/Documents/github/yggdrasil/yggstack-android`
4. Click **Open**
5. Wait for Gradle sync to complete (may take a few minutes on first open)

## Running the App

### Option 1: Using Emulator

1. In Android Studio, select **Tools → Device Manager**
2. Create a new virtual device (if needed):
   - Select a device definition (e.g., Pixel 5)
   - Select API 34 system image
   - Click Finish
3. Click the green **Run** button (▶️) in toolbar
4. Select your emulator from the list
5. Wait for app to build and install

### Option 2: Using Physical Device

1. Enable Developer Options on your Android device:
   - Go to **Settings → About Phone**
   - Tap **Build Number** 7 times
   - Go back to **Settings → System → Developer Options**
   - Enable **USB Debugging**

2. Connect your device via USB

3. In Android Studio, click the green **Run** button (▶️)

4. Select your device from the list

5. Accept the USB debugging prompt on your device

## Building APK

### Debug APK (unsigned)

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (requires signing)

First, create a keystore:
```bash
keytool -genkey -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000
```

Then add to `app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "your-password"
            keyAlias = "release"
            keyPassword = "your-password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ...
        }
    }
}
```

Build:
```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Testing the App

### Phase 1 Features to Test

1. **Navigation**
   - Tap between Configuration, Diagnostics, and Settings tabs
   - Verify all screens load correctly

2. **Configuration Screen**
   - Add peers:
     - Enter: `tcp://example.com:1514`
     - Tap the (+) button
     - Verify peer appears in list
   
   - Remove peers:
     - Tap the (×) button next to a peer
     - Verify peer is removed
   
   - Private key:
     - Tap the eye icon to show/hide
     - Verify text is masked/visible
   
   - Proxy configuration:
     - Toggle the switch off/on
     - Enter SOCKS proxy: `127.0.0.1:1080`
     - Enter DNS server: `[324:71e::53]:53`
     - Verify values persist
   
   - Expose local port:
     - Toggle the switch on
     - Tap "Add Mapping"
     - Select TCP, enter: Local Port: 80, Local IP: 127.0.0.1, Ygg Port: 8080
     - Tap "Add"
     - Verify mapping appears in list
   
   - Forward remote port:
     - Toggle the switch on
     - Tap "Add Mapping"
     - Select TCP, enter values
     - Tap "Add"
     - Verify mapping appears in list
   
   - Start/Stop service:
     - Tap "Start Service"
     - Verify button changes to "Stop Service"
     - Verify all inputs are disabled
     - Verify Yggdrasil IP appears (placeholder for now)
     - Tap "Stop Service"
     - Verify button changes back
     - Verify inputs are enabled again

3. **Settings Screen**
   - Theme selection:
     - Try Light/Dark/System options
     - Verify selection persists
   
   - About section:
     - Verify version numbers display

4. **Data Persistence**
   - Configure several settings
   - Close the app completely
   - Reopen the app
   - Verify all settings are restored

### Expected Behavior (Phase 1)

- ✅ UI should be fully functional
- ✅ All inputs should save/restore correctly
- ✅ Navigation should be smooth
- ✅ Service start/stop should work (simulated)
- ⚠️ Service will NOT actually connect to Yggdrasil yet (Phase 2)
- ⚠️ Yggdrasil IP is a placeholder until Phase 2
- ⚠️ Diagnostics screens show "To be implemented" messages

## Troubleshooting

### Gradle Sync Fails

1. Check internet connection (Gradle needs to download dependencies)
2. Invalidate caches: **File → Invalidate Caches → Invalidate and Restart**
3. Delete `.gradle` folder and sync again

### Build Fails

1. Check JDK version: **File → Project Structure → SDK Location**
2. Verify Android SDK is installed: **Tools → SDK Manager**
3. Clean and rebuild: **Build → Clean Project**, then **Build → Rebuild Project**

### App Crashes on Launch

1. Check Logcat for error messages: **View → Tool Windows → Logcat**
2. Verify yggstack.aar is in `app/libs/` directory
3. Try uninstalling app from device/emulator and reinstalling

### Emulator Won't Start

1. Check virtualization is enabled in BIOS
2. Try creating a new virtual device
3. Use a device with less RAM (e.g., 2GB instead of 4GB)

## Project Structure Overview

```
app/src/main/java/io/github/yggstack/android/
├── MainActivity.kt                    # App entry point
├── YggstackApplication.kt            # Application class
├── data/                             # Data layer
│   ├── ConfigRepository.kt           # Persistence
│   ├── ServiceState.kt               # Service state model
│   └── YggstackConfig.kt             # Configuration models
└── ui/                               # UI layer
    ├── configuration/                # Configuration screen
    │   ├── ConfigurationScreen.kt    # UI components
    │   └── ConfigurationViewModel.kt # Business logic
    ├── diagnostics/                  # Diagnostics screen
    │   └── DiagnosticsScreen.kt
    ├── settings/                     # Settings screen
    │   └── SettingsScreen.kt
    └── theme/                        # Theming
        ├── Theme.kt
        └── Type.kt
```

## Making Changes

### Adding a New Screen

1. Create new package in `ui/`
2. Create `YourScreen.kt` with `@Composable` function
3. (Optional) Create `YourViewModel.kt`
4. Add navigation in `MainActivity.kt`

### Modifying Configuration

1. Update data model in `YggstackConfig.kt`
2. Update repository in `ConfigRepository.kt`
3. Update ViewModel in `ConfigurationViewModel.kt`
4. Update UI in `ConfigurationScreen.kt`

### Adding Dependencies

1. Edit `app/build.gradle.kts`
2. Add dependency to `dependencies` block
3. Sync Gradle: **File → Sync Project with Gradle Files**

## GitHub Actions (CI/CD)

### Triggering a Build

```bash
# Commit your changes
git add .
git commit -m "Release version 1.0.0"

# Create and push a version tag
git tag 1.0.0
git push origin 1.0.0
```

### Setting Up Secrets

In GitHub repository settings, add these secrets:

1. **KEYSTORE_FILE**: Base64 encoded keystore
   ```bash
   base64 -i release.keystore | pbcopy  # macOS
   base64 release.keystore | clip       # Windows
   ```

2. **KEYSTORE_PASSWORD**: Your keystore password

3. **KEY_ALIAS**: Key alias (e.g., "release")

4. **KEY_PASSWORD**: Key password

### Downloading Built APK

1. Go to your GitHub repository
2. Click **Releases** (right sidebar)
3. Find your version (e.g., "1.0.0")
4. Download `yggstack-android-1.0.0.apk`

## Next Steps (Phase 2)

Once Phase 1 testing is complete, Phase 2 will implement:
- Actual Yggdrasil connection via yggstack.aar
- Background service
- Real peer management
- Logging system
- Diagnostics functionality

See `PRD.md` for complete Phase 2 requirements.

## Getting Help

- **Project Documentation**: See `README.md`
- **PRD**: See `PRD.md` for detailed requirements
- **Phase 1 Summary**: See `PHASE1_SUMMARY.md` for implementation details
- **GitHub Issues**: Create an issue for bugs or feature requests

## Resources

- [Android Developer Documentation](https://developer.android.com)
- [Jetpack Compose Documentation](https://developer.android.com/jetpack/compose)
- [Kotlin Documentation](https://kotlinlang.org/docs/home.html)
- [Material Design 3](https://m3.material.io/)

