# AGENTS.md

Instructions for AI coding agents working in this repository. Keep this file short — link out instead of duplicating docs.

## What this is

Native Android app (Kotlin + Jetpack Compose) that wraps the [Yggstack](https://github.com/yggdrasil-network/yggstack) Go CLI (SOCKS5 proxy / port forwarder over the Yggdrasil network). The Go code is compiled into a `.aar` via `gomobile` and consumed as a prebuilt library.

## Repository layout

```
app/                      # Android app module (Kotlin, Gradle)
  src/main/java/io/github/yggstack/android/
    data/                 # Data models and repositories
    ui/{configuration,diagnostics,settings,theme}/
    MainActivity.kt
    YggstackApplication.kt
  libs/yggstack.aar        # PREBUILT — do not hand-edit; rebuilt from lib/yggstack
  build/                   # Generated Gradle output — never read or search here
lib/yggstack/              # Git submodule: upstream Go source, own AGENTS.md/toolchain
Docs/                      # DEV_README.md, DEV_QUICKSTART.md, PRD.md, etc. — MAY BE STALE,
                            # do not trust build/toolchain versions there; this file and
                            # .github/workflows/build-release.yml are the source of truth
.github/workflows/build-release.yml  # Canonical CI build — authoritative version pins
```

`app/build/**` is generated output (intermediates, caches, reports) — do not read, grep, or index it.

## Toolchain versions (pinned — match `.github/workflows/build-release.yml`)

- JDK 17 (temurin)
- Go 1.25.11
- gomobile + gobind `golang.org/x/mobile/cmd/{gomobile,gobind}@v0.0.0-20260203041319-574ceaa2f723`
- Android NDK `26.1.10909125`
- compileSdk / targetSdk 34, minSdk 23

If any doc under `Docs/` or `lib/yggstack/` states different versions, the workflow file wins.

Note: never run `gomobile init` — it fetches `gobind@latest`, which now requires Go ≥ 1.26. The workflow and `lib/yggstack/mobile/build-android.sh` create `$GOPATH/pkg/gomobile` directly instead, which is all `gomobile bind` needs.

## Build commands

```bash
# Android app only (uses whatever yggstack.aar is already in app/libs/)
./gradlew assembleDebug
./gradlew assembleRelease   # needs KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD env vars

# Rebuild the Go library and refresh the AAR consumed by the app
cd lib/yggstack
./mobile/build-android.sh
cp android-build/yggstack.aar ../../app/libs/
```

Release APKs are produced per-ABI (arm64-v8a, armeabi-v7a, x86, x86_64, universal) and published on tag push (`[0-9]+.[0-9]+.[0-9]+`) via `build-release.yml`.

## Conventions

- Editing Go code under `lib/yggstack/` has no effect on the app until the AAR is rebuilt and copied into `app/libs/`. See [lib/yggstack/AGENTS.md](lib/yggstack/AGENTS.md).
- `lib/yggstack` is a separate git submodule with its own history/remote — don't assume root-repo git commands apply there.
- Prefer editing existing Kotlin files under `app/src/main/java/...` over creating new top-level packages.
