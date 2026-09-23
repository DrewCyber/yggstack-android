# AGENTS.md

Instructions for AI coding agents working in this repository. Keep this file short — link out instead of duplicating docs.

## What this is

Native Android app (Kotlin + Jetpack Compose) that wraps Yggstack (SOCKS5 proxy / port forwarder over the Yggdrasil network). One codebase, two engine flavors selected by Gradle product flavor:

- **`go`** (default) — the Go [Yggstack](https://github.com/yggdrasil-network/yggstack), compiled into a `.aar` via `gomobile`
- **`ng`** — Rust [yggstack-ng](https://github.com/DrewCyber/yggstack-ng) over yggdrasil-ng, consumed as `.so` + UniFFI Kotlin bindings

Both apps can be installed side by side: `ng` appends an `.ng` applicationId suffix and is labeled "Yggstack NG". Shared Kotlin code lives in `app/src/main`; engine-specific code in `app/src/go` / `app/src/ng`, each providing an `EngineFactory` + `NativeEngine` implementation (`app/src/main/.../engine/NativeEngine.kt` is the seam). CKR is Rust-only; gate UI on `BuildConfig.ENGINE_SUPPORTS_CKR`.

## Repository layout

```
app/                      # Android app module (Kotlin, Gradle)
  src/main/java/link/yggdrasil/yggstack/android/
    data/                 # Data models and repositories
    engine/               # NativeEngine interface (flavor seam)
    ui/{configuration,diagnostics,settings,theme}/
  src/go/                 # go flavor: GoEngine + NativeConfigJson (JSON config)
  src/ng/                 # ng flavor: RustEngine + NativeConfigToml + uniffi bindings
    jniLibs/              # .so per ABI — gitignored, built from lib/yggstack-ng
  libs/yggstack.aar        # go flavor PREBUILT — do not hand-edit; rebuilt from lib/yggstack
  build/                   # Generated Gradle output — never read or search here
lib/yggstack/              # Git submodule: upstream Go source, own AGENTS.md/toolchain
lib/yggstack-ng/           # Git submodule: Rust yggstack-ng (branch main)
Docs/                      # DEV_README.md, DEV_QUICKSTART.md, PRD.md, etc. — MAY BE STALE,
                            # do not trust build/toolchain versions there; this file and
                            # .github/workflows/build-release.yml are the source of truth
gradle/libs.versions.toml   # Version catalog — all app dependency/plugin versions live here
.github/workflows/build-release.yml  # Canonical CI build — authoritative version pins
```

`app/build/**` is generated output (intermediates, caches, reports) — do not read, grep, or index it.

## Toolchain versions (pinned — match `.github/workflows/build-release.yml`)

- JDK 17 (temurin)
- Go 1.27.1 (go flavor)
- gomobile + gobind `golang.org/x/mobile/cmd/{gomobile,gobind}@v0.0.0-20260821190718-4776eadac327`
- Rust stable + `cargo-ndk` (ng flavor), Android targets: aarch64, armv7, i686, x86_64 `-linux-android`
- Android NDK `28.2.13676358`
- compileSdk 37, targetSdk 34, minSdk 23

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
# App only (go flavor uses whatever yggstack.aar is in app/libs/;
# ng flavor needs .so in app/src/ng/jniLibs/ — see below)
./gradlew assembleGoDebug
./gradlew assembleNgDebug
./gradlew assembleGoRelease   # needs KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD env vars
./gradlew assembleNgRelease

# Refresh the go engine AAR
cd lib/yggstack
./mobile/build-android.sh
cp android-build/yggstack.aar ../../app/libs/

# Refresh the ng engine native libs (4 ABIs)
cd lib/yggstack-ng
for t in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android; do
  cargo ndk -t $t -o ../../app/src/ng/jniLibs build -p yggstack-mobile --release
done
```

Editing engine sources has no effect until the AAR / `.so` are rebuilt as above. After changing the UniFFI UDL, also regenerate the Kotlin bindings (`crates/yggstack-mobile` README in the submodule) and update `app/src/ng/java/uniffi/`.

Release APKs are produced per-ABI (arm64-v8a, armeabi-v7a, x86, x86_64, universal) for both flavors and published on tag push (`[0-9]+.[0-9]+.[0-9]+`) via `build-release.yml`. go APKs keep the legacy names `yggstack-<version>-<abi>.apk`; ng APKs are `yggstack-ng-<version>-<abi>.apk`.

## Conventions

- Commit subjects must state which part of the app changed. Prefix with the affected area(s):
  - `kotlin:` — app Kotlin (`app/src/**`, including the `go`/`ng` flavor source sets, plus resources/gradle)
  - `go:` — Go engine (`lib/yggstack` submodule, rebuilt `app/libs/yggstack.aar`)
  - `rust:` — Rust engine (`lib/yggstack-ng` submodule, rebuilt `app/src/ng/jniLibs`, UniFFI bindings)
  - Combine areas for multi-part changes (`kotlin+rust:`); use `all:` only when everything changed.
- `lib/yggstack` and `lib/yggstack-ng` are separate git submodules with their own histories/remotes — don't assume root-repo git commands apply there.
- Prefer editing existing Kotlin files under `app/src/main/java/...` over creating new top-level packages.
- Shared behavior belongs in `src/main`; anything touching the native engine goes through `NativeEngine` and lives in the matching flavor source set.
