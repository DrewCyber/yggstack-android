# Release Build Setup Guide

This guide explains how to set up release builds for both local development and GitHub Actions CI/CD.

## Overview

- **Keystore**: Single keystore file (`release.keystore`) used for both local and CI builds
- **Version Management**: Automatic versionCode calculated from git tags (semantic versioning)
- **Security**: Keystore and passwords stored in GitHub Secrets and local properties file

## Version Management

### versionCode Calculation
The app automatically calculates `versionCode` from semantic version tags:
- Format: `MAJOR.MINOR.PATCH` (e.g., `1.2.3`)
- Calculation: `MAJOR * 10000 + MINOR * 100 + PATCH`
- Examples:
  - `1.0.0` → versionCode `10000`
  - `1.2.3` → versionCode `10203`
  - `2.5.10` → versionCode `20510`

### versionName
- Format: `MAJOR.MINOR.PATCH-COMMITHASH` (e.g., `1.2.3-a1b2c3d`)
- Displayed in the app and release notes

### Creating Version Tags
```bash
# Create and push a new version tag
git tag 1.0.0
git push origin 1.0.0
```

## Local Setup

### Step 1: Create Keystore (First Time Only)

```bash
cd /Users/atregu/Documents/github/yggdrasil/yggstack-android

keytool -genkey -v -keystore release.keystore \
  -alias release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

You'll be prompted for:
- **Keystore password**: Choose a strong password (you'll need this for CI setup)
- **Key password**: Press Enter to use the same as keystore password
- **Your details**: Name, organization, etc.

⚠️ **IMPORTANT**: 
- Keep the keystore file and passwords safe! 
- If you lose them, you cannot update the app on Google Play
- Never commit the keystore to git (it's already in `.gitignore`)

### Step 2: Create Local Properties File

Create `keystore.properties` in the project root:

```bash
cat > keystore.properties << 'EOF'
KEYSTORE_PASSWORD=your_keystore_password_here
KEY_ALIAS=release
KEY_PASSWORD=your_key_password_here
EOF
```

Replace the passwords with the ones you used when creating the keystore.

### Step 3: Add to .gitignore

Verify these lines are in `.gitignore` (already present):
```
*.keystore
*.jks
keystore.properties
```

### Step 4: Build Release APK Locally

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Step 5: Install on Device

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## GitHub Actions Setup

### Step 1: Encode Keystore to Base64

```bash
# macOS
base64 -i release.keystore | pbcopy

# Linux
base64 release.keystore | xclip -selection clipboard

# Or just output to terminal
base64 release.keystore
```

### Step 2: Add GitHub Secrets

1. Go to your GitHub repository
2. Navigate to: **Settings** → **Secrets and variables** → **Actions**
3. Click **"New repository secret"** for each:

| Secret Name | Value | Description |
|-------------|-------|-------------|
| `KEYSTORE_FILE` | Base64 encoded keystore | Output from step 1 |
| `KEYSTORE_PASSWORD` | Your keystore password | Same as in keystore.properties |
| `KEY_ALIAS` | `release` | Keystore alias |
| `KEY_PASSWORD` | Your key password | Same as in keystore.properties |

### Step 3: Test GitHub Actions Build

Push a version tag to trigger the release workflow:

```bash
git tag 1.0.0
git push origin 1.0.0
```

The workflow will:
1. Build the yggstack AAR
2. Decode the keystore from secrets
3. Build and sign the release APK
4. Create a GitHub Release with the APK attached

### Step 4: Verify the Release

1. Go to: **Releases** tab on GitHub
2. You should see the new release with the APK attached
3. Download and verify the APK is signed properly

## Troubleshooting

### Build fails with "Keystore not found"

**Local build:**
- Verify `release.keystore` exists in project root
- Check `keystore.properties` has correct values

**GitHub Actions:**
- Verify `KEYSTORE_FILE` secret contains base64 encoded keystore
- Check workflow logs for decode errors

### "Wrong password" error

- Double-check `KEYSTORE_PASSWORD` matches the keystore
- Verify `KEY_PASSWORD` matches the key alias password
- Passwords are case-sensitive

### versionCode conflict on Play Store

If Play Store says versionCode already exists:
- Increment the version tag (e.g., `1.0.0` → `1.0.1`)
- versionCode must always increase

### Can't update existing app

If you get "signature mismatch" error:
- You must use the **same keystore** for all updates
- If keystore is lost, you cannot update the app (need to publish new app)

## Security Best Practices

✅ **DO:**
- Keep keystore file backed up in a secure location (password manager, encrypted drive)
- Use strong passwords for keystore and key
- Regularly update GitHub secrets rotation
- Share keystore only with trusted team members via secure channels

❌ **DON'T:**
- Commit keystore to git
- Share keystore via email or chat
- Use weak passwords
- Store passwords in code or unencrypted files

## Backup Checklist

Before distributing the app, ensure you have:
- [ ] `release.keystore` file backed up securely
- [ ] Keystore password documented securely
- [ ] Key alias name documented
- [ ] Key password documented securely
- [ ] GitHub Secrets configured
- [ ] Tested local release build
- [ ] Tested GitHub Actions release

## Additional Resources

- [Android App Signing Documentation](https://developer.android.com/studio/publish/app-signing)
- [GitHub Encrypted Secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets)
- [Google Play Console](https://play.google.com/console/)
