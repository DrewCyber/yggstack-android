# Quick Setup Steps - Release Signing

## 1. Create Keystore (One Time)

```bash
keytool -genkey -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000
```

**Save these values securely:**
- Keystore password: _____________
- Key password: _____________
- Key alias: `release`

## 2. Local Development Setup

Create `keystore.properties` in project root:

```properties
KEYSTORE_PASSWORD=your_password_here
KEY_ALIAS=release
KEY_PASSWORD=your_password_here
```

**Test local build:**
```bash
./gradlew assembleRelease
```

## 3. GitHub Secrets Setup

**Encode keystore:**
```bash
base64 -i release.keystore | pbcopy
```

**Add to GitHub:**
1. Go to repo Settings → Secrets and variables → Actions
2. Add these secrets:

| Name | Value |
|------|-------|
| `KEYSTORE_FILE` | Paste base64 output |
| `KEYSTORE_PASSWORD` | Your keystore password |
| `KEY_ALIAS` | `release` |
| `KEY_PASSWORD` | Your key password |

## 4. Create First Release

```bash
git tag 1.0.0
git push origin 1.0.0
```

GitHub Actions will automatically build and publish the release APK.

## 5. Verify

- Check GitHub Releases tab for the APK
- Download and install on device
- Verify app works correctly

---

**See RELEASE_SETUP.md for detailed documentation**
