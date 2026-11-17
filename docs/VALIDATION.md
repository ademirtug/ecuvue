# Migration validation — 5 November 2026

## Saved-adapter assistant connection (build 13)

Implemented seven default-disabled AppFunctions, saved successful adapter profiles, explicit per-device reconnection approval/forget controls, and a bounded assistant reconnection path. Fault reports include available offline reference meanings, possible causes and inspection guidance, with language/fallback metadata. Package remains `app.leximo.ecu_vue`; release is `0.2.0`, version code `13`.

- 32 core tests, 5 shared presentation/DI tests and 13 Android unit tests passed (50 total). New checks cover approval reset, simulator exclusion, stable USB identity, persistence failure, concurrent reconnect requests, runtime opt-in, approval revocation during the handshake, and pausing automatic polling on an assistant-only cold start.
- All 10 emulator integration cases passed together (`OK (10 tests)`, 107.6 seconds) on Android 16.1. A subsequent focused foreground-service denial test also passed (`OK (1 test)`, 8.568 seconds), giving 11 distinct emulator cases and 61 checks overall.
- The native controller/foreground service connected while the Activity was stopped using an injected diagnostic adapter; the emulator allowed this start. Repeated calls reused the session. A forced immediate handshake failure cleaned up without crashing. The separate denial test injects Android's `ForegroundServiceStartNotAllowedException` at the Context boundary and verifies the actionable `assistantForegroundRequired` result; it does not claim the OS denied the successful background case.
- OS AppFunctions indexing and execution verified all seven IDs, status/reconnect reuse and explained fault results with the Activity stopped, missing-profile and unapproved-profile errors, and rejection after disabling AI access. No VIN was returned.
- Saved-profile approval persisted across repository recreation. Settings approval, forgetting, existing navigation, palettes/languages, simulator scans, live data, clear confirmation and discovery cases passed. The new Settings section was visually inspected from an emulator screenshot.
- Android debug/test APKs and the signed release AAB built successfully. Packaged APK/AAB metadata contains exactly seven functions, all disabled by default, with connection descriptions and code-explanation fields. The release upload certificate SHA-256 still matches the existing identity: `A9:ED:3C:63:A5:25:36:DD:69:8E:0F:68:E2:9E:AB:7A:BA:74:95:D7:B6:D6:67:BF:CC:2D:B8:26:51:75:B1:48`.
- Debug lint: zero errors, 90 warnings. New assistant settings copy uses English fallback where translations have not been provided.

The first service test exposed stopping a foreground service before promotion on a fast handshake failure. `ConnectionServiceLauncher` now waits for service acknowledgement after `startForeground` before opening the adapter; the regression scenario passed after this fix.

`DiagnosticLifecycleObserver` initializes polling as paused before the first Activity starts. This covers cold starts from an assistant where an Activity never ran and there was no `onStop` event. Explicit diagnostic reads remain available without enabling automatic polling.

After that final lifecycle guard, all 50 JVM/unit tests passed again, the debug/test APKs, signed AAB and lint passed, and the three affected emulator cases (native connection/service lifecycle, real OS function execution and foreground-service denial) passed together again (`OK (3 tests)`, 16.949 seconds).

Physical Bluetooth/BLE/Wi-Fi/USB hardware and actual Gemini selection/conversations remain unverified. Tests use generated data/injected transports and real Android service/registry infrastructure. Google Connected Console onboarding, account eligibility, privacy/terms URLs, upload and publication are external account actions; source, signed bundle, proposed listing copy and testing instructions are prepared in ANDROID_AI.md. No external registration or publication was performed. iOS/native App Intents and cloud MCP relay remain separate future work.

## Earlier migration checks

- `:core:jvmTest`: 26 tests (protocols 9, reference 3, session 9, settings observers 3, adapter factory 2).
- `:composeApp:jvmTest`: 5 tests (DI graph 2, discovery presenter 3).
- `:androidApp:testDebugUnitTest`: 12 tests (RFCOMM fallback/cancellation 7, prompt framing/deadlines 4, Activity bridge ownership 1).
- Android instrumentation: all 8 tests passed together on Android 16.1 (`Medium_Phone_API_36.1`). The expanded suite was run with `adb am instrument` to retain screenshots.
- Android debug APK build and release AAB build, including release vital lint.
- Android debug lint: zero errors. Warnings include retained launcher artwork, newer dependency notices and the deliberately retained target SDK 36.
- Compose shared UI compilation for JVM desktop.
- Visual inspection of Android overview, diagnostic report, live data and Arabic/dark theme screenshots.
- Android OS discovery and execution of the generated AppFunctions service, including an actual simulated fault scan and rejection after disabling AI access.
- Packaged AppFunctions metadata: exactly six functions, all disabled by default; no clear/reset/raw-command function.
- Packaged APK identity: `app.leximo.ecu_vue`, version `0.2.0`, version code `12`, minimum SDK `24`, target SDK `36`, compile SDK `37`.
- Release bundle signature verified. Its SHA-256 certificate fingerprint matches the original `android/upload-certificate.pem`:
  `A9:ED:3C:63:A5:25:36:DD:69:8E:0F:68:E2:9E:AB:7A:BA:74:95:D7:B6:D6:67:BF:CC:2D:B8:26:51:75:B1:48`.
- No `libflutter` or Dart source files packaged in the APK.
- Bundled code-reference SHA-256 matches the retained Flutter source asset.

## Remaining external verification

- Physical Bluetooth Classic/BLE/Wi-Fi/USB adapter and vehicle testing. Emulator/unit tests do not prove compatibility with real hardware or every ECU.
- Gemini account/device availability, Connected Console registration, tester setup and Play review. No external registration, upload or publication was performed.
- iOS compilation on macOS/Xcode, native iOS transports and Swift App Intents. The current iOS target is a shared UI/engine demo entry point.
- Desktop native transports. Its current host is a simulator preview.

The Flutter directory was read for migration and retained unchanged. Private upload signing material was reused by reference and was not copied into this project.

## Startup and connection picker regressions

- Confirmed the startup failure in Android's event log: `.launcher.System` was finished with `disabled-package` when startup changed the enabled launcher alias. Startup now only stages the icon preference; alias changes happen from the process background lifecycle callback. System/monochrome uses the resource-aware System alias.
- Cold launches through `.launcher.System` and the saved appearance's `.launcher.Light` each remained the top resumed activity after startup and another six seconds. Captured the native splash and visually confirmed that the car artwork has no square background. Splash configuration follows the [Android splash migration guide](https://developer.android.com/develop/ui/views/launch/splash-screen/migrate).
- Simulator restored as an adapter row, with its own simulation label/icon. Wi-Fi, BLE and USB panels start collapsed. The picker retains the bottom tabs and the originating tab selection. Reopening Connections cannot create a Back loop. Collapsing BLE stops discovery.
- All six instrumentation tests passed using `adb shell am instrument -w -r app.leximo.ecu_vue.test/androidx.test.runner.AndroidJUnitRunner` on the existing Android 16.1 emulator. Two added checks cover connection layout/navigation/expansion/input retention and keeping launcher component states unchanged during foreground appearance changes. Existing simulator, localization, session and OS AppFunctions checks also passed.
- Core tests: 21 passed; Android unit tests: 11 passed. Android APK/test APK and shared desktop compilation passed. Debug lint: zero errors, 89 warnings.
- After final copy cleanup, rebuilt the APK and reran the connection picker regression successfully; retained screenshots show the simulator row, persistent tabs and Wi-Fi expansion. The final debug APK was installed on the emulator and left open successfully.
- Cleared generated Gradle output after discovering a dex cache entry containing the project's old `ecu_vue_kotlin` path. The rebuild uses the moved `ecu_vue` directory. The existing interactive emulator was retained.

## Page headings without the branded title bar

Removed the shared ECU Vue top app bar. Page headings now contain the connection action and simulation indicator; Connections and fault details have inline, RTL-aware Back buttons. Bottom navigation remains visible. Android system bar icons now follow the saved app theme, including when it differs from the device theme.

Android APK/test APK and shared desktop compilation passed. Existing connection picker, simulator diagnostics/details/live/clear, and appearance/Arabic instrumentation checks passed. After the system bar contrast adjustment, the connection and appearance checks passed again; light and dark screenshots were visually reviewed. No diagnostic engine changes were made.

## Unified Bluetooth adapter list

Classic and BLE now share Available adapters and a single Find adapters action. Paired devices appear on entry; searching refreshes that list and performs BLE discovery. Rows retain their transport kind, show a Classic/BLE label, and are deduplicated by transport/address. Bonded LE-only devices are classified as BLE. Stop search, selection and navigation cancel the page-owned discovery job. Wi-Fi and USB retain their compact expansion cards, and the simulator remains an adapter row.

All seven instrumentation tests passed, including a deterministic adapter fixture verifying both connection routes, paired-device visibility during scanning, duplicate removal, Stop search and cancellation on navigation. Existing simulator, localization and OS AppFunctions tests passed. Android unit tests: 11 passed. Android APK/test APK and JVM UI compilation passed; debug lint: zero errors, 89 warnings. Screenshots of the combined list and native empty state were visually reviewed. Physical adapter compatibility remains external verification.

Updated connection text is bundled in all nine languages and preserved by `tools/connection_copy.json` during asset reimport.

## Connection-dependent pages and offline lookup

Diagnostics and Live Data tabs/rail items are disabled without a connected adapter or simulator. Their repeated connection cards were removed. A disconnect while either page is active returns to Overview; Back from code details also avoids returning to a disconnected vehicle page. Clear confirmation closes on disconnect.

Offline reference search moved to Overview and keeps its query when opening/returning from details. Diagnostics restores the original action card, stored/pending/permanent counts, category filters and fault list. Clear is disabled when no stored/pending codes remain. Connection capabilities are available on Overview and Live Data.

The seven existing instrumentation cases passed with the updated UI. The new offline-navigation case passed in a targeted run, covering disconnected tabs, offline lookup/details/query retention, both tabs becoming enabled after connecting, and returning to Overview after disconnecting from each tab. Existing simulator clearing verification also checks that the clear button disables when only the permanent code remains. Android/JVM UI builds and debug lint passed; disconnected Overview and restored Diagnostics screenshots were visually reviewed. No diagnostic engine changes were required.

## Overview disconnect and single startup screen

Restored Disconnect beside Change connection on connected Overview, using the same session disconnect operation as Connections. The offline navigation test now presses this action and verifies that the session disconnects, vehicle tabs disable, and the reference query survives.

Removed the branded Compose loading page. Android preloads and caches translations/reference resources in the Activity lifecycle while retaining the native splash, then displays the app. Startup readiness does not depend on Compose's first draw. Shared loading failures retain the Retry UI; other platforms retain a neutral progress indicator.

Android APK/test APK and JVM compilation passed. Three targeted instrumentation cases passed: Overview disconnect/offline navigation, simulator diagnostics/live/clear, and theme/Arabic/session preservation. A cold launcher start and screenshots were reviewed for the direct native-splash-to-app transition and restored Overview action.

## Settings palettes and dropdowns

Restored the palette grid with primary/container color swatches derived from the actual light/dark color schemes, selected borders/checkmarks and radio selection semantics. Theme selection uses a System/Light/Dark dropdown. Language selection uses a dropdown with native language names and a system-default option.

Android APK/test APK and JVM compilation passed. The existing appearance/Arabic instrumentation case passed after updating its dropdown interactions, verifying palette selection, theme/language updates and retention of the active simulator session. Light/dark and Arabic screenshots were reviewed. Verification used a temporary read-only emulator after the original emulator closed; the temporary instance was shut down afterwards. No Koin or broader architecture refactor was introduced by this UI change.

## Koin, SOLID boundaries and observers

Replaced manual application-host construction with Koin 4.2.2 shared/Android modules. Framework entry points inject the application session/tools and platform ports. Settings, reports, permissions, discovery, transport creation, connection lifetime, AI availability and lifecycle handling have separate implementations. Resource loading uses one injected cache. Shared screens were separated, and discovery state moved into a factory-created StateFlow presenter. See [architecture](ARCHITECTURE.md).

All 51 checks passed: 26 core tests, 5 presentation/DI graph tests, 12 Android unit tests and all 8 emulator cases. The appearance case now recreates the Activity and verifies settings/session retention. The real OS AppFunctions case executes status and scanning with the Activity stopped, waits for observer-driven availability, and verifies revocation. Existing navigation, palettes/dropdowns, simulator, disconnect and discovery checks passed.

Android debug APK/test APK, JVM compilation, debug lint and signed release AAB passed. Lint: zero errors, 89 warnings. The bundle's signing fingerprint still matches the existing upload certificate. Packaged AppFunctions metadata still contains six functions, all disabled by default. iOS source uses the shared graph, but compilation still requires macOS/Xcode and was not run on Windows. Physical adapter verification remains outstanding.

## VS Code run/debug setup after moving the project

The project was located at `C:/Users/akn/source/ecuvue/ecu_vue`. The workspace launch/task definitions use `${workspaceFolder}`; the Windows Android helper derives the root from its own location.

Verified on Windows PowerShell with the installed JetBrains Kotlin extension's debugger configuration schema:

- All workspace JSON parses, and pre/post-launch task references resolve.
- The helper parses without PowerShell syntax errors.
- The run task builds from the moved directory, automatically boots the available emulator, installs the debug APK, and starts the activity successfully.
- The debug task starts a waiting Android process and forwards its JDWP endpoint to localhost port 5005.
- A real JDI debugger attached through that forwarded endpoint, hit `EcuVueApplication.onStart` at `EcuVueApplication.kt:17`, and displayed the Kotlin source line and stack trace.
- Debug-forwarding cleanup and subsequent debug preparation both succeeded.

The breakpoint check used the JDK debugger against the same connection configured in VS Code; the VS Code F5 UI itself was not automated. The JetBrains extension's Android/KMP integration is experimental. The temporary headless emulator used for verification was stopped afterwards so a subsequent F5 can open an interactive emulator normally.
