# My Pi Cloud (Android)

Companion Android client for the personal Nextcloud described in the [root README](../README.md).

- Application id / namespace: `com.example.picloud`
- Display name: **My Pi Cloud** (`app_name` in `src/main/res/values/strings.xml`)
- First launch asks for the Nextcloud URL (for example `http://<PI_TAILSCALE_IP>:8080`). The value is stored in EncryptedSharedPreferences; use **Change server** in the menu to edit it.
- minSdk 24, targetSdk / compileSdk 34.

Build with Android Studio (open this folder) or:

```bash
cp local.properties.example local.properties   # set sdk.dir
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

How to rename the package is documented in the root README, section 10.
