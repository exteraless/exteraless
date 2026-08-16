# Building exteraless

## Preparation

1. Clone the repository together with submodules:

```bash
git clone --recursive --shallow-submodules https://github.com/exteraless/exteraless.git exteraless
```

If the repository has already been cloned without submodules:

```bash
git submodule update --init --recursive --depth=1
```

2. Get `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` at [my.telegram.org](https://my.telegram.org/auth) and create `local.properties` in the project root:

```properties
TELEGRAM_APP_ID=<your_app_id>
TELEGRAM_APP_HASH=<your_app_hash>
```

3. To sign the APK, put your own `TMessagesProj/release.keystore` and add to `local.properties`:

```properties
KEYSTORE_PASS=<keystore_password>
ALIAS_NAME=<alias_name>
ALIAS_PASS=<alias_password>
```

The keystore is intentionally not in the repository. Without it, the build does not fail — the APK is signed with the Android debug key.

4. For push notifications, put your own `TMessagesProj/google-services.json` (Firebase, package name `com.exteraless.app`).

5. Replace project metadata:
   - Google Maps key in the `com.google.android.maps.v2.API_KEY` entry in `TMessagesProj/src/main/AndroidManifest.xml`
   - `BaseRemoteHelper.CHANNEL_METADATA_ID` — numeric id of your metadata channel, without the `-100` prefix.

## Building

Build with `./gradlew :TMessagesProj:assembleDebug` or open the project in Android Studio.

## ABI

Only 64-bit `arm64-v8a` and `x86_64` are built: Chaquopy builds Python 3.12 only for them, and `armeabi-v7a` configuration fails. The `NATIVE_TARGET` variable sets the target: `arm64-v8a` (one ABI, faster), `universal` (both), `SKIP` (without native part — only Java and resources).

## Building via GitHub Actions

Two repository secrets are required:

- `LOCAL_PROPERTIES` — contents of `local.properties` in base64:

```bash
base64 -w0 local.properties
```

- ```RELEASE_KEYSTORE``` — keystore file in base64:

```bash
base64 -w0 TMessagesProj/release.keystore
```

Then run the **Release Build** workflow. The finished APK is in the run artifacts.
