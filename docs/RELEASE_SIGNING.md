# ECU Vue release signing

The Android application is `app.leximo.ecu_vue`. Its current version is **0.2.0 (build 14)**.
Check Play Console for the highest uploaded version code before preparing another release.
An already-used version code must be increased in `androidApp/build.gradle.kts`; the display
version can remain 0.2.0. Build 14 corrects the app-level AppFunctions metadata rejected
by Play in the older build 13 bundle. The build helper does not access or publish to Play Console.

## Registered upload identity

Release builds require the registered upload certificate's public SHA-256 fingerprint:

```text
F1:E2:B7:D8:B6:AC:FF:65:C0:62:58:A1:8F:C0:0A:71:10:B5:41:EB:15:F3:DC:9A:5C:6B:E8:6F:61:D1:F6:D0
```

The certificate is named `CN=ECU Vue Upload`. Release builds verify the private key and its
certificate against this fingerprint before signing. A debug key, a different app's key,
or a public `.der`/`.pem` certificate cannot substitute for the registered private key.
Signing in to Google Cloud CLI does not restore a missing private keystore.

## Active replacement upload key

A replacement RSA 3072-bit upload key was created on 2026-11-13 in `~/.signing/ecu-vue/`
and is now active in Play Console. `upload_certificate.pem` is its public certificate.
The private keystore is `upload-keystore.jks`; the matching alias and passwords are in
`key.properties`. The project already discovers that configuration automatically.

Replacement certificate SHA-256:

```text
F1:E2:B7:D8:B6:AC:FF:65:C0:62:58:A1:8F:C0:0A:71:10:B5:41:EB:15:F3:DC:9A:5C:6B:E8:6F:61:D1:F6:D0
```

The Gradle release guard now selects this replacement identity. It supersedes the previous
upload certificate with SHA-256
`A9:ED:3C:63:A5:25:36:DD:69:8E:0F:68:E2:9E:AB:7A:BA:74:95:D7:B6:D6:67:BF:CC:2D:B8:26:51:75:B1:48`.

## Restore once, keep signing outside the project

Store the original private keystore and `key.properties` outside the project. A properties
file needs these four fields, filled in locally:

```properties
storeFile=upload-keystore.jks
storePassword=YOUR_EXISTING_STORE_PASSWORD
keyAlias=YOUR_EXISTING_KEY_ALIAS
keyPassword=YOUR_EXISTING_KEY_PASSWORD
```

`storeFile` is relative to the properties file's directory, or an absolute path. Use forward
slashes in Windows paths in Java properties files, or escape backslashes as `\\`.

Once you recover the existing files, import them with:

```powershell
.\gradlew.bat :androidApp:importUploadSigning "-PecuVueImportSigningProperties=C:/private/original/key.properties"
```

This verifies the private key, then copies the keystore and properties to
`%USERPROFILE%/.signing/ecu-vue/`. It never overwrites existing signing files. That location
survives moving or replacing this project folder. Do not delete it when cleaning or replacing project folders. Files in this directory contain passwords and private key material;
keep the Windows account and storage protected.

Signing configuration is selected in this order:

1. Gradle property `-PecuVueSigningProperties=<path>` (also supported in private Gradle user properties).
2. Environment variable `ECU_VUE_SIGNING_PROPERTIES`.
3. `%USERPROFILE%/.signing/ecu-vue/key.properties` (`~/.signing/ecu-vue/key.properties` on other systems).
The default base directory is `~/.signing`. Set `SIGNING_ROOT` to use another base directory containing the `ecu-vue` subfolder.

An explicitly selected missing or invalid file fails with an actionable error; it does not
silently select another key. Debug builds remain available without release credentials.

## Make a recoverable backup

After import, copy the verified signing material to a secure location you control:

```powershell
.\gradlew.bat :androidApp:backupUploadSigning "-PecuVueSigningBackupDirectory=E:/PrivateBackups/ECU-Vue"
```

Use an encrypted removable drive or another protected backup location. The task copies the
keystore and properties together, verifies the copy, refuses to overwrite existing files,
and rejects destinations inside the project. It does not upload anything. The copied
`key.properties` contains passwords, so protect the entire backup. Keep at least one copy
on a separate device or secured backup service; another directory on the same disk does
not protect against disk loss. Keep the backup location and recovery instructions in your
password manager. To check a restored backup without modifying it:

```powershell
.\gradlew.bat :androidApp:verifyUploadSigning "-PecuVueSigningProperties=E:/PrivateBackups/ECU-Vue/key.properties"
```

Source-control ignore rules exclude common keystore formats and `key.properties`. They
are a safeguard, not a backup. Do not commit private signing files, passwords, or backups.

## Prepare the upload

```powershell
.\tools\android-release.ps1
# Or select an existing private signing configuration explicitly:
.\tools\android-release.ps1 -SigningProperties C:/private/key.properties
```

The helper verifies the key, runs core/shared UI/Android unit tests and release lint, builds
the signed AAB, and writes the versioned bundle plus a SHA-256 checksum to
`androidApp/build/outputs/play/`. For the current configuration the file is
`ECU-Vue-0.2.0-14.aab`. Failed checks stop the command; existing files from an earlier build
are not evidence of a successful new build. Upload only after the command succeeds.

VS Code also exposes **Terminal > Run Task > ECU Vue: prepare Play upload**.

For a direct Gradle build, `:androidApp:preparePlayUpload` creates the versioned artifact;
`:androidApp:bundleRelease` still creates the standard
`androidApp/build/outputs/bundle/release/androidApp-release.aab`. Both require the verified
signing identity. Neither publishes the app.

## If the private key is lost

Search secure backups and the original Flutter project's signing files first. The public
certificate fingerprint confirms identity but cannot reconstruct the private key. Google
Cloud account credentials and downloaded public certificates cannot do that either.

If no private-key backup exists, use Play Console's **App signing > Upload key certificate >
Request upload key reset** process. See [Google's Play App Signing instructions](https://support.google.com/googleplay/android-developer/answer/9842756).
Only after Google accepts the replacement upload key, update
`expectedUploadCertificateSha256` in `androidApp/build.gradle.kts` to the fingerprint shown
in Play Console, import the replacement signing files, and create a verified backup.
Do not bypass the fingerprint check to make an unregistered key build successfully.
