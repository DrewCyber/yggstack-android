# How to Build Yggstack Android

This guide explains how to build the Yggstack Android application from source.

## Prerequisites

Before building, ensure you have the following installed:

1. **Java Development Kit (JDK)** 17
2. **Android SDK** - Typically installed via Android Studio
3. **Android NDK** - Required for native libraries
4. **Go toolchain**
5. **gomobile + gobind** - pinned versions, installed automatically by the build script if missing

Toolchain versions (JDK, Go, gomobile/gobind, NDK) are pinned in
[`.github/workflows/build-release.yml`](.github/workflows/build-release.yml) — that file is the
source of truth; this guide does not duplicate them. Run `./scripts/check-environment.sh` from
the repo root to verify your local JDK, Go, and NDK match the CI pins.

### Installing Prerequisites

#### 1. Install Go

Visit [https://golang.org/dl/](https://golang.org/dl/) and download the Go version pinned in
`.github/workflows/build-release.yml` for your platform.

Verify installation:
```bash
go version
```

#### 2. Install gomobile

Usually you can skip this: `lib/yggstack/mobile/build-android.sh` installs the pinned
gomobile/gobind versions automatically if they are not on your PATH. To install them manually,
use the exact version pinned in `.github/workflows/build-release.yml`:

```bash
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260203041319-574ceaa2f723
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260203041319-574ceaa2f723
```

> **Never run `gomobile init` and never install with `@latest`.** `@latest` pulls a `gobind`
> that requires a newer Go than the pinned toolchain, and `gomobile init` fetches it. The
> build script creates `$GOPATH/pkg/gomobile` directly, which is all `gomobile bind` needs.

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
cd lib/yggstack/mobile
./build-android.sh
```

This will:
- Install the pinned gomobile/gobind if missing and prepare `$GOPATH/pkg/gomobile` directly (no `gomobile init`)
- Detect your Android SDK
- Build the native library for all Android architectures (arm64, arm, amd64, 386)
- Create `lib/yggstack/android-build/yggstack.aar` (approximately 37 MB)

**Build Output:**
- Location: `lib/yggstack/android-build/yggstack.aar`
- Package name: `link.yggdrasil.yggstack`
- Main class: `link.yggdrasil.yggstack.Yggstack`

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

**Build Output (ABI splits are enabled, so one APK per ABI is produced):**
- Debug APKs: `app/build/outputs/apk/debug/app-<abi>-debug.apk` plus `app-universal-debug.apk`
- Release APKs: `app/build/outputs/apk/release/app-<abi>-release.apk` plus `app-universal-release.apk`
- `<abi>` is one of `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

## Complete Build Script

Here's a complete script to build everything from scratch:

```bash
#!/bin/bash

# Navigate to project root
cd /path/to/yggstack-android

# Step 1: Build mobile bindings
echo "Building yggstack mobile bindings..."
cd lib/yggstack/mobile
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
echo "APK location: app/build/outputs/apk/debug/ (one APK per ABI plus universal)"
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

- [Mobile bindings API guide](lib/yggstack/mobile/API_USAGE.md)
- [Mobile build modes](lib/yggstack/mobile/BUILD_MODES.md)
- [Yggstack README](lib/yggstack/README.md)
- [Project README](README.md)

## Quick Reference

| Task | Command |
|------|---------|
| Build mobile bindings | `cd lib/yggstack/mobile && ./build-android.sh` |
| Copy AAR | `cp lib/yggstack/android-build/yggstack.aar app/libs/` |
| Build debug APK | `./gradlew assembleDebug` |
| Build release APK | `./gradlew assembleRelease` |
| Clean build | `./gradlew clean` |
| Install on device | `./gradlew installDebug` |
| Run tests | `./gradlew test` |
| Verify local toolchain matches CI | `./scripts/check-environment.sh` |

## Output Locations

- **Mobile bindings AAR**: `lib/yggstack/android-build/yggstack.aar`
- **App library**: `app/libs/yggstack.aar`
- **Debug APKs**: `app/build/outputs/apk/debug/app-<abi>-debug.apk` (+ universal)
- **Release APKs**: `app/build/outputs/apk/release/app-<abi>-release.apk` (+ universal)
