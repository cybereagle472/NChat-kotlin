# NChat UI Lab: one-time update signing setup

Android only accepts an in-place APK update when the signing certificate matches the installed app. Keep the release keystore backed up permanently.

## 1. Create a permanent signing key (Termux)

Install Java if needed:

```sh
pkg update
pkg install openjdk-17
```

Create the keystore. Replace the example password with a strong password and remember it:

```sh
keytool -genkeypair -v -keystore nchat-release.jks -alias nchat -keyalg RSA -keysize 2048 -validity 10000 -storepass 'CHOOSE_A_STRONG_PASSWORD' -keypass 'CHOOSE_A_STRONG_PASSWORD' -dname 'CN=NChat, OU=Mobile, O=CyberEagle, L=Mansehra, ST=KPK, C=PK'
base64 nchat-release.jks | tr -d '\\n'
```

Copy the long base64 output. Keep both the original `nchat-release.jks` file and password in a safe backup. Never commit the keystore or share it publicly.

## 2. Add GitHub Actions secrets

Open the repository on GitHub, then Settings -> Secrets and variables -> Actions -> New repository secret. Add:

- `SIGNING_KEY_BASE64`: the complete base64 output from the command above
- `KEY_STORE_PASSWORD`: the keystore password
- `KEY_ALIAS`: `nchat`
- `KEY_PASSWORD`: the key password

## 3. Build and install the first signed release

After adding all four secrets, open Actions -> Build NChat UI Lab APK -> Run workflow. Download the generated release APK artifact or the GitHub Release asset and install it. You can uninstall the older unsigned/debug build first as planned.

Future releases must keep the same keystore and increase both `versionCode` in `app/build.gradle` and `versionCode` in `version.json`. The app checks the version manifest at launch and offers the APK update; Android still asks you to confirm installation.
