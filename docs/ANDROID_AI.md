# Android AppFunctions

The implementation is `androidApp/src/main/kotlin/app/leximo/ecu_vue/ai/DiagnosticAppFunctions.kt`. KSP generates `DiagnosticAppFunctionService` and `assets/ecu_vue_functions.xml` for debug and release builds. Do not hand-edit generated output.

App-level metadata is separate from the generated function catalog. With the pinned
AppFunctions 1.0.0-alpha13 library, `res/xml/app_metadata.xml` must use an
`AppFunctionAppMetadata` root with `appfn:description` and `appfn:displayDescription`
attributes in the `res-auto` namespace. The display description references a nonempty
string resource. Child `<description>` elements are not read by the library or Play.
The build checks this contract before packaging, and `AppFunctionMetadataTest` verifies
that the actual library parser reads both descriptions from compiled Android resources.
Do not rename `description` to the newer `instructions` attribute without migrating the
library version and its metadata contract together.

The service is exported only with `android.permission.BIND_APP_FUNCTION_SERVICE`. Android-certified assistant callers require the platform execution permission; access-level metadata also restricts callers. Every tool is additionally gated by the application's persisted AI opt-in. There is no exported raw-command bridge.

The manifest declares both the Android 16 legacy `android.app.appfunctions` property and the newer v2/schema properties, pointing to generated metadata. The legacy property is necessary for discovery on the tested Android 16.1 image. The compiler's schema contains both ID representations. The runtime gate remains authoritative if indexing is delayed or dynamic enabled state has not synchronized yet.

## Local testing

Install the debug app on Android 16+ with platform AppFunctions support. Launch ECU Vue and enable AI access. For physical reconnection, connect your adapter once, then enable **Settings > AI assistant access > Allow AI to reconnect this adapter**. Disconnect in the app to test reconnection. The explicit simulator can test diagnostic tools but is never saved as a physical adapter. Then:

```powershell
adb shell cmd app_function list-app-functions
adb shell cmd app_function execute-app-function --package app.leximo.ecu_vue --function 'app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#getConnectionStatus' --parameters '{}'
adb shell cmd app_function execute-app-function --package app.leximo.ecu_vue --function 'app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#connectSavedAdapter' --parameters '{}' --timeout-duration 120
adb shell cmd app_function execute-app-function --package app.leximo.ecu_vue --function 'app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#readFaultCodes' --parameters '{}' --timeout-duration 120
adb shell cmd app_function execute-app-function --package app.leximo.ecu_vue --function 'app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#lookupCode' --parameters '{"code":"P0301","language":"en"}'
```

Shell quoting varies by host; use the command syntax printed by `adb shell cmd app_function help` if JSON arguments need escaping. Indexing is asynchronous after installation. Reopening Settings and toggling access resynchronizes the enabled state. Enabling a function with a shell command cannot bypass the application's own opt-in check.

Fresh readings require a connection. Use `getConnectionStatus` then, if needed, `connectSavedAdapter` before `readFaultCodes` or `readLiveSnapshot`. Reconnection accepts no address, never replaces an active connection, and requires global opt-in plus per-adapter approval. Changing adapter identities resets approval. Forgetting removes the local profile and approval; neither action disconnects an already active session. Disabling global AI access blocks every tool even if the saved device remains approved.

Bluetooth and Wi-Fi must already be configured; no pairing, network selection, or permission prompt is attempted in the background. USB reconnection uses vendor/product/serial rather than the transient device ID and requires existing USB permission. An adapter without a unique serial is manual-only. Android foreground-service denial returns an actionable permission-required error asking the assistant to have the user open ECU Vue and connect. The tool never launches an Activity unsolicited or retries another device. A connection handshake is bounded to 120 seconds, rechecks approval and closes the new transport if approval is revoked during it.

Disconnected read tools do not silently return cached readings. Saved reports are retrieved only with `getLatestReport`. Tool results contain numeric units, collection times, mode availability and simulation provenance. A freeze-frame collection time is retrieval time; the actual fault-recording time is unknown.

## Exposed function catalog

All seven functions are disabled by default. The platform service permits Android-trusted callers, and every invocation checks the app's own opt-in before and after execution. The full IDs have prefix `app.leximo.ecu_vue.ai.BaseDiagnosticAppFunctions#`.

| Function | Parameters | Result and intended use |
| --- | --- | --- |
| `getConnectionStatus` | None | Connection, busy and simulation flags, latest report time, saved-profile presence, reconnect support/approval and a plain-language next step. No hardware addresses or serials. |
| `connectSavedAdapter` | None | Reuses any active session or reconnects the saved approved adapter. Returns status; does not scan faults. Ask the user to complete setup if Android requires interaction. |
| `getCapabilities` | None | Connected protocol, supported PID hex identifiers, discovery completeness and pending/permanent/freeze-frame availability. UNKNOWN is not UNSUPPORTED. |
| `readFaultCodes` | None | Fresh stored/pending/permanent codes and available frame-zero snapshot; collection time, simulation and session provenance. Includes reference explanations per code when found. |
| `getLatestReport` | None | Most recent stored report plus available offline explanations, without vehicle I/O. Always report its age and provenance. |
| `readLiveSnapshot` | None | One fresh sensor snapshot: RPM, km/h, coolant Celsius, adapter volts, engine load percent, throttle percent. Missing values are null. |
| `lookupCode` | `code: String`, `language: String? = null` | Offline meaning, possible causes and suggested inspection, with found/language/English-fallback metadata. Code format e.g. P0301; language defaults to English. |

### Tool descriptions and diagnostic explanations

These are two distinct layers:

- Each annotated method has KDoc included in generated AppFunctions metadata (`isDescribedByKDoc = true`): purpose, prerequisites, workflow ordering, expected result, simulation/cached-data rules and limitations. The connection function explains foreground fallback and that an agent cannot pick another device.
- `readFaultCodes` and `getLatestReport` enrich each code with `explanation`: `title`, `meaning`, `possibleCauses`, `suggestedNextStep`, `language`, and `usedEnglishFallback` (using the app language or system language). It is null if there is no bundled definition. `lookupCode` offers the same information independently, including a `found` flag. The bundled reference is generic community material, not proof of a failed part or manufacturer service information. Gemini can turn these structured results into a conversational response.

No raw ELM commands, device-address selection, clearing codes, readiness reset, ECU programming, VIN, USB serial or Bluetooth address is exposed. Connection initialization configures the adapter/diagnostic session; it is separate from diagnostic reads.

## Automated verification

`PortIntegrationTest.androidSystemDiscoversAndExecutesReadOnlyFunctions` uses the real OS function registry and invocation command, rather than calling an annotated Kotlin method directly. It checks all seven IDs, opt-in, connection reuse, returned simulated faults and explanations, VIN exclusion and disabled access with the Activity stopped. Additional cases cover saved-profile persistence and approval/forget UI, and the actual native connection controller/foreground service with an injected transport, including background denial or successful startup. Core tests cover approval reset, no simulator persistence, stable USB identity, failed persistence, concurrent calls and revocation during a handshake.

## Gemini distribution

Connect the existing Play app to an eligible Google Connected Console account using the Android MCP connection type. Upload the AppFunctions-enabled bundle to a test track, configure the connected-app information and testers, test through Gemini, and complete the applicable Play review.

### Connected app configuration

- Existing Play package: `app.leximo.ecu_vue`; prepared version: `0.2.0`, code `14`. Reuse its existing Play listing and upload identity. Choose a Connected Apps category that accurately covers the available functionality and meets its requirements; do not assume category eligibility.
- Suggested public name: **ECU Vue**. Description: **Read your car's generic OBD fault codes and sensor readings through your approved diagnostic adapter, with offline code explanations and saved reports.**
- Capabilities: **Connect an approved saved adapter**; **Read current generic OBD faults and sensor snapshots**; **Explain OBD codes and retrieve saved diagnostic reports**.
- Example prompts: **@ECU Vue check my car's fault codes**; **@ECU Vue what is my coolant temperature?**; **Use ECU Vue to explain P0301**. These are test prompts, not a guarantee of automatic routing.
- Supply the actual publicly accessible privacy-policy and terms URLs; confirm the policies describe diagnostic data processed by an assistant. Supply the required 512 x 512 icon from the app's approved branding.
- Save Connected App details **before uploading the AAB**. Link the existing Play app using Android MCP. Upload the bundle to a test track and confirm seven functions are detected. Add tester accounts and submit the connected-app version for testing.
- On the supported Android phone, install the exact submitted build. Connect the adapter once, enable AI access and approve reconnection. Connect ECU Vue in Gemini's Connected Apps settings. Test explicit app selection first, then the unqualified request **Check the fault codes in my car**.
- Test disconnected/reconnect, already connected, missing permissions, revoked approval, powered-off adapter, simulator disclosure, offline cached report and English fallback. Verify actual Gemini results rather than inferring success from ADB tests. Production publication/review happens with the corresponding Play release.

No developer-hosted cloud MCP server is required for this on-device Android MCP integration. Google account onboarding, real Gemini testing and production publication remain account/device actions outside the local source implementation.

Google's public overview and newer console instructions have differed on rollout eligibility. Confirm what is available for the developer account and target devices before promising universal Gemini access.

References: [AppFunctions implementation](https://developer.android.com/ai/appfunctions/add-appfunctions), [Connected Console workflow](https://developer.android.com/ai/appfunctions/create-manage-connected-apps).
