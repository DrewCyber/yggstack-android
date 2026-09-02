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
gradle/libs.versions.toml   # Version catalog — all app dependency/plugin versions live here
.github/workflows/build-release.yml  # Canonical CI build — authoritative version pins
```

`app/build/**` is generated output (intermediates, caches, reports) — do not read, grep, or index it.

## Toolchain versions (pinned — match `.github/workflows/build-release.yml`)

- JDK 17 (temurin)
- Go 1.26.8
- gomobile + gobind `golang.org/x/mobile/cmd/{gomobile,gobind}@v0.0.0-20260821190718-4776eadac327`
- Android NDK `28.2.13676358`
- compileSdk 36, targetSdk 34, minSdk 23

**targetSdk must stay 34 — critical app requirement.** Do not raise it as part of
dependency/toolchain upgrades: Android 15+ behavior gates (notably the ~6-hour dataSync
foreground-service runtime limit, enforced edge-to-edge, predictive back) conflict with the
always-on service model. compileSdk moves independently of targetSdk and may be bumped for
newer libraries. Accepted consequence: Google Play's target-API policy cannot be met;
releases ship as GitHub APKs. `scripts/check-environment.sh` fails if targetSdk drifts from 34.

If any doc under `Docs/` or `lib/yggstack/` states different versions, the workflow file wins. Run `./scripts/check-environment.sh` to verify the local JDK/Go/NDK against these pins.

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
