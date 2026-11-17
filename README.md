# ECU Vue — Kotlin Multiplatform

A new Kotlin/Compose port of `../ecu_vue_flutter`. The Flutter source is retained unchanged and is not required at runtime. Android is the first supported hardware and assistant target.

## Open and run

Open this directory in Android Studio, select the `androidApp` configuration, and run on an Android device or emulator. JDK 21 and Android SDK platform 37 are used by the current toolchain. Android 7/API 24 remains the minimum; the app targets API 36, matching the Flutter release. No Flutter SDK is needed.

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:installDebug
```

The debug APK is `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Choose **Connections → Simulated car → Connect** to exercise scans and live data without hardware. A physical connection uses an ELM327-compatible OBD adapter and a responding vehicle.

Available adapters combines paired Classic/BLE devices and discovered BLE OBD adapters, with a small transport label on each row. **Find adapters** refreshes paired devices and searches for nearby BLE adapters; **Stop search**, choosing an adapter, or leaving Connections cancels discovery. The simulated car remains a virtual adapter row. Wi-Fi and USB options start collapsed and expand when tapped. The connection picker keeps the main navigation visible. Appearance settings update the UI immediately; the launcher icon changes after the app goes into the background so switching launcher aliases cannot close the visible activity. The splash uses the transparent car logo rather than the launcher icon background.

Pages use their own headings without a separate ECU Vue title bar. The connection action and simulation indicator sit beside the heading; Connections and fault details include an inline back arrow.

Connected Overview includes Change connection and Disconnect actions. Android keeps the native splash visible until the main interface is composed, with no time cutoff or second loading screen. Startup errors release the splash to an actionable Retry screen. The code catalog loads independently in the application scope on a background dispatcher. Navigation and connections are available while it loads; offline search is disabled until its StateFlow observer receives the complete catalog. Loading failures offer a catalog-only retry. Fault descriptions and reference credits appear when the catalog is ready.

Settings uses a System/Light/Dark dropdown and a native-name language dropdown. Palette choices show their actual primary/container color swatches for the current light or dark appearance, with a visible selected marker.

Diagnostics and Live Data are enabled only with an active adapter or simulator connection. Losing the connection returns those pages to Overview. Offline code search lives on Overview and remains available without an adapter; its query is retained when opening and returning from code details. Diagnostics uses the original scan action card, fault-category counts and filters. Clearing is disabled when only permanent codes remain.

### VS Code: F5 run

Open this project folder in VS Code. F5 runs Android without attaching a debugger. The recommended **Kotlin by JetBrains** extension (`jetbrains.kotlin-server`) provides Kotlin language support.

1. Press **F5** (the only launch configuration is **ECU Vue: Android run (no debugger)**).
2. The launcher builds the APK, starts an emulator if no device is connected, waits for Android to boot, installs the app and opens it.
3. The app launches without waiting for a debugger. Closing the launch terminal afterward does not stop the emulator or app.

**Ctrl+Shift+B** also builds, installs and runs Android without attaching a debugger. **Terminal → Run Task → ECU Vue: Android logcat** displays logs for the running app.

The emulator starts as an independent process with its console hidden and its device window visible. The launch task only waits for Android to boot, installs the app and starts it; no terminal needs to remain open afterward. Emulator output is saved to `.gradle/vscode/emulator.log` and `emulator-error.log`. Logcat is a separate task that stays open only when explicitly requested.

The scripts derive the project directory from their own location, so renaming/moving the project does not require editing absolute paths. The SDK is located through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `local.properties`, or the usual local Android SDK installation. If `JAVA_HOME` is missing, the Windows helper uses Android Studio's bundled JDK.

With multiple devices, set `ANDROID_SERIAL` to the value from `adb devices`, or run the helper with `-DeviceSerial`. With multiple virtual devices, set `ECU_VUE_AVD` to the desired AVD name. The helper never uninstalls an existing app to work around a signing mismatch.

```powershell
.\tools\android-dev.ps1 -Mode Run
.\tools\android-dev.ps1 -Mode Debug -DeviceSerial emulator-5554
.\tools\android-dev.ps1 -Mode Stop
```

Debugging is an explicit developer action through Android Studio or the helper's `-Mode Debug` option; F5 never uses that mode. This setup does not add Flutter-style hot reload—run again after changing code.

Configuration: `.vscode/launch.json`, `.vscode/tasks.json`, and `tools/android-dev.ps1`. [JetBrains debugger configuration](https://www.jetbrains.com/help/intellij-vscode/Run-and-debug.html).

## Architecture

| Module | Responsibility |
| --- | --- |
| `core` | Framework-independent diagnostic engine, parsers, simulator, settings/discovery/factory ports, settings observers, report persistence contracts and read-only tools. Android/JVM/iOS targets. |
| `composeApp` | Separate Compose screens, StateFlow discovery presenter, bundled-resource repository, shared Koin module, localization and branding. Android/JVM/iOS targets. |
| `androidApp` | Android Koin bindings, framework entry points, native transports/discovery, permission bridge, repositories, connection service, lifecycle observer, analytics and AppFunctions. |

The UI and Android AppFunctions call the same `DiagnosticSession`. One coroutine mutex protects each complete operation, including header-on VIN/freeze-frame sequences. Busy, disconnected, timed-out and unsupported states return explicit results/errors. An Activity is not required for assistant execution.

Koin 4.2.2 starts once in `EcuVueApplication`. Activities, the connection service and AppFunctions inject the same application-scoped diagnostic session and read-only tools. Constructor-injected repositories, discovery, permissions and adapter factories replace the old `AndroidAppHost`. Desktop/iOS preview entry points use the shared Koin graph with simulator bindings.

UI and discovery state use `StateFlow`. A dedicated settings observer stages launcher changes and synchronizes AI availability, cancelling stale updates. A separate lifecycle observer pauses background polling, stops discovery and applies launcher icons only after backgrounding. See [architecture and dependency lifetimes](docs/ARCHITECTURE.md).

## Ported features

- ELM setup, negotiated protocol identification, advertised multi-page PID discovery and repeated-missing-sensor suppression.
- Stored/pending/permanent DTC reads; optional modes distinguish unsupported from unavailable.
- ECU-specific frame-zero freeze frames, header restoration, and safe failure if framing cannot be restored.
- VIN decoding from CAN ISO-TP, numbered ELM output and legacy records; offline manufacturer identification without guessing model/year.
- Six live readings, recent charts, simulator idle/driving controls and simulator reset.
- In-app confirmed Mode 04 reset, acknowledgement classification and verification scan. Never automatically replay a reset. Permanent codes are not claimed cleared.
- Bluetooth Classic RFCOMM compatibility/authenticated fallback; BLE UART profiles FFF0, FFE0 and Nordic UART; Wi-Fi network-bound TCP; USB serial/OTG with explicit permission and baud selection.
- Overview, connection picker, diagnostic filters, code details, offline reference search, live data, light/dark/system themes, four palettes, original launcher aliases and nine languages including Arabic RTL.
- The original 9,532-code CC0 reference and 101-prefix manufacturer snapshot. Reference descriptions do not establish that a fault is present or a component has failed.
- Flutter theme/palette/language preference migration when updating an existing installation. Existing launcher alias names and Android package identity are preserved.
- Latest report persisted locally, explicitly labeled cached/current and simulated/real in tool responses. A disconnected dashboard does not show old data as live.

New AI settings copy currently uses English fallback for languages without new translations. Existing Flutter interface translations are retained.

## Android AI tools

Enable **Settings → AI assistant access** on Android 16+. All seven functions are disabled by default in the generated schema, dynamically enabled after opt-in, and independently checked at execution time. Revocation is checked again after queueing and before fresh results return. VINs and Bluetooth addresses are excluded from tool responses.

| Function | Behavior |
| --- | --- |
| `getConnectionStatus` | Connection/busy/simulation state, latest report time, saved-adapter approval and the next action. |
| `connectSavedAdapter` | Reuse the active session or connect the locally saved, user-approved adapter. No agent-supplied address. |
| `getCapabilities` | Protocol, advertised PID identifiers and diagnostic-mode availability. |
| `readFaultCodes` | Fresh bounded read of codes and available frame-zero data; includes available offline explanations and inspection guidance. |
| `getLatestReport` | Saved report and code explanations without vehicle I/O; includes time, simulation and current-session flags. |
| `readLiveSnapshot` | Fresh supported readings with fixed units and nullable missing values. |
| `lookupCode` | Offline reference lookup with language/fallback metadata. |

No AppFunction connects to an arbitrary adapter, accepts raw commands, clears codes, resets readiness or programs an ECU. Connect successfully through the app once, then separately approve assistant reconnection in Settings. Selecting a different device resets approval; forgetting removes the saved profile. The simulator is never saved. USB reconnection requires a unique serial and existing OS permission. If Android blocks a background connection, the tool instructs the assistant to ask the user to open ECU Vue. The foreground notification provides a user-controlled disconnect action. Background automatic polling pauses; explicit diagnostic reads can use the shared session.

See [Android AI integration and testing](docs/ANDROID_AI.md). AppFunctions registration alone does not make an app publicly available in Gemini: Google Connected Console account eligibility, device/assistant support, testing and Play review still apply.

`ReadOnlyDiagnosticTools` is also the intended boundary for a future authenticated phone-to-cloud MCP relay. A cloud MCP server, authentication and remote relay are not implemented in this Android-first port.

## Play identity and signing

- Application ID/namespace: **`app.leximo.ecu_vue`**.
- Version: **0.2.0**, version code **14**. Check Play Console before uploading; increase the version code if it has already been used.
- Persistent signing configuration: `~/.signing/ecu-vue/key.properties`, outside the project. Explicit Gradle/environment overrides are also supported; `SIGNING_ROOT` selects a different central base directory.
- Release builds verify the private key against the upload certificate fingerprint confirmed in Play Console. Missing credentials or a different key stop the build.
- Import, backup, restore and recovery commands are documented in [release signing](docs/RELEASE_SIGNING.md). Private signing files/passwords stay outside source control.

```powershell
.\tools\android-release.ps1
```

The release helper runs signing verification, unit tests and release lint, then creates the signed versioned bundle and checksum in `androidApp/build/outputs/play/`. The standard Gradle bundle remains at `androidApp/build/outputs/bundle/release/androidApp-release.aab`. Building does not publish it. A debug-signed build cannot replace a Play-signed installation directly. Do not uninstall a user's production app to work around a signature mismatch.

The original public Firebase Android configuration is retained for the same app. Analytics records standard SDK events and stable screen names; custom events do not include VINs, codes, sensor readings, adapter identifiers or search terms. AI/foreground-service changes require an appropriate release review and updated product privacy disclosures before publication.

## Validation

```powershell
.\gradlew.bat :core:jvmTest :androidApp:testDebugUnitTest
.\gradlew.bat :composeApp:jvmTest
.\gradlew.bat :androidApp:lintDebug :androidApp:assembleDebug :androidApp:bundleRelease
.\gradlew.bat :androidApp:connectedDebugAndroidTest
.\gradlew.bat :composeApp:compileKotlinJvm
```

Tests cover protocol edge cases, capabilities, VINs, acknowledgement handling, ECU-specific snapshots, full reference validation, persistence, operation ordering, opt-in/revocation, cancellation and native RFCOMM fallback. Emulator tests exercise navigation, simulator scans/details/live data/confirmed clearing, theme and Arabic changes, native tools without a resumed Activity, and OS-discovered AppFunction execution. These checks do not establish compatibility with a physical adapter/car; perform hardware validation before shipping this replacement.

## Desktop and iOS

`./gradlew :composeApp:run` launches the shared desktop UI with the simulator. Desktop native transports are not implemented yet.

The shared UI and engine declare `iosArm64` and `iosSimulatorArm64` targets. `MainViewController()` supplies an iOS simulator/demo host. iOS hardware transports and Swift App Intents remain a later target. iOS compilation and signing require macOS/Xcode and have not been verified on this Windows machine. Android's raw RFCOMM implementation is not an iOS transport.

## Assets and provenance

`tools/import_flutter_assets.py` imports translations, reference data, original branding and the RFCOMM fallback helper from the sibling Flutter tree. `tools/connection_copy.json` preserves updated connection copy in all nine languages when reimporting. The launcher helper is maintained in this Kotlin project. The importer only reads the Flutter tree and never copies keystores or `key.properties`. Assets are committed with the new project so normal builds do not run the importer or require Flutter. Dataset attribution is in `composeApp/src/commonMain/composeResources/files/reference/NOTICE.txt` and the bundled dataset metadata.

Primary references: [Android AppFunctions](https://developer.android.com/ai/appfunctions/add-appfunctions), [AppFunctions releases](https://developer.android.com/jetpack/androidx/releases/appfunctions), [connected-device services](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device), [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/kmp-overview.html).
