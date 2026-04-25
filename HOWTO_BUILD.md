# How to Build Yggstack Android

This guide explains how to build the Yggstack Android application from source.

## Prerequisites

Before building, ensure you have the following installed:

1. **Java Development Kit (JDK)** - Version 17 or later
2. **Android SDK** - Typically installed via Android Studio
3. **Android NDK** - Required for native libraries
4. **Go** - Version 1.22 or later
5. **gomobile tools** - For building mobile bindings

### Installing Prerequisites

#### 1. Install Go

Visit [https://golang.org/dl/](https://golang.org/dl/) and download Go 1.22 or later for your platform.

Verify installation:
```bash
go version
```

#### 2. Install gomobile

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
```

#### 3. Set up Android SDK

The build script will attempt to auto-detect your Android SDK at:
- macOS: `$HOME/Library/Android/sdk`
- Linux: `$HOME/Android/Sdk`

If your SDK is in a different location, set the `ANDROID_HOME` environment variable:
```bash
export ANDROID_HOME=/path/to/your/android/sdk
```

## Build Steps

### Step 1: Build the Yggstack Mobile Bindings

The first step is to build the yggstack mobile bindings as an Android AAR library:

```bash
cd lib/yggstack
chmod +x build-android.sh
./build-android.sh
```

This will:
- Initialize gomobile
- Detect your Android SDK
- Build the native library for all Android architectures (arm64, arm, amd64, 386)
- Create `android-build/yggstack.aar` (approximately 37 MB)

**Build Output:**
- Location: `lib/yggstack/android-build/yggstack.aar`
- Package name: `link.yggdrasil.yggstackng`
- Main class: `link.yggdrasil.yggstackng.Yggstack`

### Step 2: Copy AAR to App Libraries

Copy the generated AAR to the Android app's libs directory:

```bash
mkdir -p app/libs
cp lib/yggstack/android-build/yggstack.aar app/libs/
```

### Step 3: Build the Android APK

Build the debug APK using Gradle:

```bash
./gradlew assembleDebug
```

For a release build (requires signing configuration):
```bash
./gradlew assembleRelease
```

**Build Output:**
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk` (if building release)

## Complete Build Script

Here's a complete script to build everything from scratch:

```bash
#!/bin/bash

# Navigate to project root
cd /path/to/yggstack-android

# Step 1: Build mobile bindings
echo "Building yggstack mobile bindings..."
cd lib/yggstack
chmod +x build-android.sh
./build-android.sh

# Step 2: Copy AAR to app
echo "Copying AAR to app/libs..."
cd ../..
mkdir -p app/libs
cp lib/yggstack/android-build/yggstack.aar app/libs/

# Step 3: Build APK
echo "Building Android APK..."
./gradlew assembleDebug

echo "Build complete!"
echo "APK location: app/build/outputs/apk/debug/app-debug.apk"
```

## Building with Android Studio

Alternatively, you can build using Android Studio:

1. Build the mobile bindings first (Steps 1-2 above)
2. Open the project in Android Studio
3. Wait for Gradle sync to complete
4. Click **Build > Make Project** or **Build > Build Bundle(s) / APK(s) > Build APK(s)**

## Troubleshooting

### gomobile not found

If you get "gomobile: command not found", ensure Go's bin directory is in your PATH:
```bash
export PATH=$PATH:$(go env GOPATH)/bin
```

### Android SDK not found

Set the ANDROID_HOME environment variable:
```bash
export ANDROID_HOME=$HOME/Library/Android/sdk  # macOS
# or
export ANDROID_HOME=$HOME/Android/Sdk  # Linux
```

### NDK not found

Install Android NDK via Android Studio:
1. Open Android Studio
2. Go to **Tools > SDK Manager**
3. Click **SDK Tools** tab
4. Check **NDK (Side by side)** and click **Apply**

### Build fails with "checklinkname" error

This is normal and handled by the build script with the `-checklinkname=0` flag. If you see this error, ensure you're using the latest build script.

### Gradle build fails

Clean and rebuild:
```bash
./gradlew clean
./gradlew assembleDebug
```

## Build Types

### Debug Build
- Includes debug symbols
- Larger file size (~37 MB for AAR)
- Faster build time
- Used for development and testing

```bash
./gradlew assembleDebug
```

### Release Build
- Symbols stripped (`-s -w` LDFLAGS)
- Optimized and smaller size
- Requires signing configuration
- Used for production releases

```bash
./gradlew assembleRelease
```

## CI/CD Builds

The build script automatically detects CI environments (GitHub Actions, etc.) and builds optimized release versions with stripped symbols.

## Additional Resources

- [Android Mobile Bindings Documentation](lib/yggstack/mobile/ANDROID.md)
- [Yggstack README](lib/yggstack/README.md)
- [Project README](README.md)

## Quick Reference

| Task | Command |
|------|---------|
| Build mobile bindings | `cd lib/yggstack && ./build-android.sh` |
| Copy AAR | `cp lib/yggstack/android-build/yggstack.aar app/libs/` |
| Build debug APK | `./gradlew assembleDebug` |
| Build release APK | `./gradlew assembleRelease` |
| Clean build | `./gradlew clean` |
| Install on device | `./gradlew installDebug` |
| Run tests | `./gradlew test` |

## Output Locations

- **Mobile bindings AAR**: `lib/yggstack/android-build/yggstack.aar`
- **App library**: `app/libs/yggstack.aar`
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`
