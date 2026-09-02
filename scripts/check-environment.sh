#!/usr/bin/env bash
# Compares the local toolchain against the pins in .github/workflows/build-release.yml
# (the repo's declared single source of truth — see AGENTS.md).
# Exits non-zero if any pinned tool is missing or has a different major.minor.patch.
#
# Usage: ./scripts/check-environment.sh

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/build-release.yml"

fail=0

if [[ ! -f "$WORKFLOW" ]]; then
    echo "ERROR: CI workflow not found at $WORKFLOW"
    exit 1
fi

# --- Pins from CI -----------------------------------------------------------

ci_java="$(grep -oE "java-version: '[^']+'" "$WORKFLOW" | head -1 | sed "s/java-version: '//;s/'//")"
ci_go="$(grep -oE "go-version: '[^']+'" "$WORKFLOW" | head -1 | sed "s/go-version: '//;s/'//")"
ci_ndk="$(grep -oE 'ndk;[0-9][0-9.]*' "$WORKFLOW" | head -1 | sed 's/ndk;//')"

echo "CI pins (from $(basename "$WORKFLOW")):"
echo "  JDK  : ${ci_java:-<not found>}"
echo "  Go   : ${ci_go:-<not found>}"
echo "  NDK  : ${ci_ndk:-<not found>}"
echo

# --- JDK --------------------------------------------------------------------

java_version_line="$(java -version 2>&1 | head -1)"
if [[ -z "$java_version_line" ]]; then
    echo "FAIL: java not found on PATH (CI pins JDK ${ci_java:-?})"
    fail=1
else
    local_java_major="$(echo "$java_version_line" | grep -oE 'version "[0-9]+' | grep -oE '[0-9]+' | head -1)"
    # Pre-9 releases look like 1.8.0_xxx — normalize the leading 1
    [[ "$local_java_major" == "1" ]] && local_java_major="$(echo "$java_version_line" | grep -oE 'version "1\.[0-9]+' | grep -oE '\.[0-9]+' | tr -d '.' )"
    if [[ "$local_java_major" == "${ci_java}" ]]; then
        echo "OK   JDK: local major $local_java_major == CI $ci_java ($java_version_line)"
    else
        echo "FAIL JDK: local major ${local_java_major:-unknown} != CI ${ci_java:-?} ($java_version_line)"
        fail=1
    fi
fi

# --- Go ---------------------------------------------------------------------

go_version_line="$(go version 2>/dev/null)"
if [[ -z "$go_version_line" ]]; then
    echo "FAIL: go not found on PATH (CI pins Go ${ci_go:-?})"
    fail=1
else
    local_go="$(echo "$go_version_line" | awk '{print $3}' | sed 's/^go//' | sed 's/[^0-9.].*$//')"
    if [[ "$local_go" == "$ci_go" ]]; then
        echo "OK   Go: local $local_go == CI $ci_go"
    else
        echo "WARN Go: local ${local_go:-unknown} != CI ${ci_go:-?} (exact match expected; install the pinned version)"
        fail=1
    fi
fi

# --- NDK --------------------------------------------------------------------

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[[ -d "$sdk_root" ]] || sdk_root="$HOME/Android/Sdk"

if [[ -d "$sdk_root/ndk" ]]; then
    installed_ndks="$(ls "$sdk_root/ndk" 2>/dev/null | sort | tr '\n' ' ')"
    echo "INFO NDK: installed under $sdk_root/ndk: ${installed_ndks:-none}"
    if echo " $installed_ndks " | grep -q " $ci_ndk "; then
        echo "OK   NDK: CI pin $ci_ndk is installed"
    else
        echo "FAIL NDK: CI pin ${ci_ndk:-?} NOT installed (run: sdkmanager \"ndk;${ci_ndk}\")"
        fail=1
    fi
else
    echo "FAIL NDK: no NDK directory under $sdk_root (CI pins ${ci_ndk:-?})"
    fail=1
fi

# ANDROID_NDK_HOME resolution matters for lib/yggstack/mobile/build-android.sh
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ "$(basename "$ANDROID_NDK_HOME")" == "$ci_ndk" ]]; then
        echo "OK   ANDROID_NDK_HOME -> $ANDROID_NDK_HOME (matches CI pin)"
    else
        echo "WARN ANDROID_NDK_HOME -> $ANDROID_NDK_HOME != CI pin $ci_ndk (local AAR builds would use a different NDK than CI)"
    fi
else
    ndk_count="$(ls "$sdk_root/ndk" 2>/dev/null | wc -l | tr -d ' ')"
    if [[ "$ndk_count" -gt 1 ]]; then
        echo "WARN ANDROID_NDK_HOME is unset and $ndk_count NDKs are installed; gomobile may pick a different one than CI. Export ANDROID_NDK_HOME=\"$sdk_root/ndk/$ci_ndk\" for local AAR builds."
    else
        echo "INFO ANDROID_NDK_HOME is unset; single NDK installed, gomobile will find it"
    fi
fi

# --- targetSdk constraint (critical app requirement — see AGENTS.md) --------

app_gradle="$ROOT/app/build.gradle.kts"
if [[ -f "$app_gradle" ]]; then
    target_sdk="$(grep -oE 'targetSdk = [0-9]+' "$app_gradle" | grep -oE '[0-9]+' | head -1)"
    if [[ "$target_sdk" == "34" ]]; then
        echo "OK   targetSdk: $target_sdk (critical requirement per AGENTS.md — must stay 34)"
    else
        echo "FAIL targetSdk: ${target_sdk:-not found} — AGENTS.md pins targetSdk 34 as a critical app requirement; do not raise it during upgrades"
        fail=1
    fi
fi

echo
if [[ $fail -eq 0 ]]; then
    echo "Environment matches CI pins."
else
    echo "Environment does NOT match CI pins — fix the FAIL lines above."
fi
exit $fail
