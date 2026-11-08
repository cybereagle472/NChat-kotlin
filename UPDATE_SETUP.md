# NChat: one-time update signing setup

Android only accepts an in-place APK update when the signing certificate matches the installed app. Keep the release keystore backed up permanently.

**Current setup:** the workflow uses the committed demo signing key so existing installs can update. CI also verifies that the signed APK's certificate SHA-256 is `429c0bb9664496faaa275638852d4e6b366bfb203c4e5c6d6b60101106e67912`; the pinned fingerprint in `.github/scripts/verify_release_apk.py` must be deliberately updated as part of a signing-key migration. This key and password are public and must not be treated as secure production credentials. The steps below are for a later migration to a private signing key. Android normally cannot update the existing demo-signed installation with a differently signed APK; plan the migration and warn users that uninstalling may remove local-only data.

## 1. Create a permanent signing key (Termux)

Install Java if needed:

```sh
pkg update
pkg install openjdk-17
```

Create the keystore. Replace the example password with a strong password and remember it:

```sh
keytool -genkeypair -v -keystore nchat-release.jks -alias nchat -keyalg RSA -keysize 2048 -validity 10000 -storepass 'CHOOSE_A_STRONG_PASSWORD' -keypass 'CHOOSE_A_STRONG_PASSWORD' -dname 'CN=NChat, OU=Mobile, O=CyberEagle, L=Mansehra, ST=KPK, C=PK'
base64 nchat-release.jks | tr -d '\n'
```

Copy the long base64 output. Keep both the original `nchat-release.jks` file and password in a safe backup. Never commit the keystore or share it publicly.

## 2. Add GitHub Actions secrets

Open the repository on GitHub, then Settings -> Secrets and variables -> Actions -> New repository secret. Add:

- `SIGNING_KEY_BASE64`: the complete base64 output from the command above
- `KEY_STORE_PASSWORD`: the keystore password
- `KEY_ALIAS`: `nchat`
- `KEY_PASSWORD`: the key password

## 3. Build and install the first signed release

These secrets are not used by the current demo-signing workflow. When migrating, update the workflow to use them before building. Then open Actions -> Build NChat APK -> Run workflow. Download the generated release APK artifact or the GitHub Release asset and install it. You can uninstall the older unsigned/debug build first as planned.

Future releases must keep the same keystore and increase both `versionCode` in `app/build.gradle` and `versionCode` in `version.json`. The app checks the version manifest at launch and offers the APK update; Android still asks you to confirm installation.

## Update downloads and mirrors

The updater retries temporary network and server failures such as HTTP 504. If the GitHub download remains inaccessible, use the release page in a browser or try a different network. A browser fallback is not an independent mirror: both routes can still be affected by the same CDN or network outage.

For installations that have a separately operated HTTPS mirror, the update manifest may optionally include an `apkUrls` array of up to three alternate APK URLs. Each URL must serve the **same signed APK for the exact advertised version**; the updater checks the package, version and signing compatibility. The existing `apkUrl` remains the primary source and is the only URL older app versions understand. Do not add a mirror until it has been provisioned and verified. The current manifest does not include a mirror.

The release workflow publishes after the `main` commit, while the manifest is read directly from `main`. There can therefore be a delay between advertising a new version and the matching APK becoming available; verify publication before telling users to install. Never uninstall an existing app to work around a signing error without first protecting local-only data.
