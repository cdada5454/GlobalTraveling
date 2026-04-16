# Shadow

Android LSPosed module for location spoofing and environment masking.

## Build

Debug build:

```bash
./gradlew assembleDebug
```

Signed release build:

```bash
./gradlew assembleRelease
```

`assembleRelease` only produces a signed APK when the keystore and passwords are provided through environment variables or Gradle properties.

## GitHub Actions

This repository includes a workflow at [.github/workflows/android-release.yml](C:\Users\Administrator\Desktop\shadow\.github\workflows\android-release.yml).

It builds a signed `release` APK, uploads it as a workflow artifact, and publishes the APK to the GitHub **Release** page.

### Required GitHub Secrets

Create these repository secrets before running the workflow:

| Secret | Purpose |
| --- | --- |
| `AMAP_API_KEY` | AMap Android key used by the app at runtime |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of your `release.keystore` |
| `KEYSTORE_PASSWORD` | Password for the keystore |
| `KEY_ALIAS` | Alias of the release key inside the keystore |
| `KEY_PASSWORD` | Password for the alias/private key |

### Workflow behavior

The workflow:

1. Checks out the repository
2. Sets up Java 17
3. Verifies that all required secrets exist
4. Restores `release.keystore` from `ANDROID_KEYSTORE_BASE64`
5. Builds `app-release.apk`
6. Prints the APK certificate information
7. Uploads the APK as a workflow artifact
8. Creates/updates a GitHub Release and attaches the APK asset

### Trigger behavior

- `push` tag `v*` (for example `v1.0.0`): publishes APK to that tag's Release automatically.
- `workflow_dispatch`: provide `release_tag` (for example `v1.0.1`) to publish APK to that Release.

## AMap API Configuration

The app reads the AMap key from the manifest placeholder `AMAP_API_KEY`.

Manifest entry:

```xml
<meta-data
    android:name="com.amap.api.v2.apikey"
    android:value="${AMAP_API_KEY}" />
```

### How to make the APK usable after GitHub Actions builds it

For AMap Android SDK, the key must match both:

1. The package name: `com.kankan.globaltraveling`
2. The SHA1 of the certificate that signs the APK

That means the release APK must be signed with a fixed keystore that you control.

If you change the keystore, you must update the SHA1 binding in the AMap console.

### Steps in AMap console

1. Create an Android app key in AMap Open Platform
2. Set package name to `com.kankan.globaltraveling`
3. Set SHA1 to the SHA1 of your release keystore certificate
4. Save the generated Android key
5. Put that key into the GitHub secret `AMAP_API_KEY`

## Keystore

Use one long-term release keystore. Do not generate a new one for every build.

### Generate a release keystore

Run this on a machine with JDK or Android Studio installed:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias shadow_release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 36500
```

On Windows PowerShell:

```powershell
keytool -genkeypair -v `
  -keystore release.keystore `
  -alias shadow_release `
  -keyalg RSA `
  -keysize 2048 `
  -validity 36500
```

Record these values:

- `release.keystore`
- keystore password
- alias, for example `shadow_release`
- key password

### Get the SHA1 for AMap

```bash
keytool -list -v -keystore release.keystore -alias shadow_release
```

Look for the `SHA1:` line and register that SHA1 in the AMap Android application configuration.

### Convert the keystore to Base64 for GitHub Secrets

Linux/macOS:

```bash
base64 -w 0 release.keystore > release.keystore.base64
```

Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | Set-Content -NoNewline release.keystore.base64
```

Copy the full contents of `release.keystore.base64` into the GitHub secret `ANDROID_KEYSTORE_BASE64`.

## Local release build with environment variables

Linux/macOS:

```bash
export AMAP_API_KEY="your_amap_key"
export KEYSTORE_FILE="$PWD/release.keystore"
export KEYSTORE_PASSWORD="your_keystore_password"
export KEY_ALIAS="shadow_release"
export KEY_PASSWORD="your_key_password"
./gradlew assembleRelease
```

Windows PowerShell:

```powershell
$env:AMAP_API_KEY="your_amap_key"
$env:KEYSTORE_FILE="$PWD\\release.keystore"
$env:KEYSTORE_PASSWORD="your_keystore_password"
$env:KEY_ALIAS="shadow_release"
$env:KEY_PASSWORD="your_key_password"
.\gradlew.bat assembleRelease
```

## Output

Signed release APK path:

`app/build/outputs/apk/release/app-release.apk`

Unsigned local release APK path when no keystore is provided:

`app/build/outputs/apk/release/app-release-unsigned.apk`

Debug APK path:

`app/build/outputs/apk/debug/app-debug.apk`
