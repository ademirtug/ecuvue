# ECU Vue

ECU Vue is an Android OBD-II diagnostic app for reading fault codes and live vehicle data through ELM327-compatible adapters. It includes an offline code reference and a simulated vehicle for development.

Install the published app from Google Play:

<a href="https://play.google.com/store/apps/details?id=app.leximo.ecu_vue">
  <img src="docs/images/google-play-badge.png" alt="Get it on Google Play" width="200">
</a>

## Build

Requirements:

- Android Studio with Android SDK 37 installed.
- JDK 21. Android Studio's bundled JDK can be used.
- A Firebase Android configuration for `app.leximo.ecu_vue`, saved as `androidApp/google-services.json`. This file is excluded from Git.

Open the project in Android Studio, add the Firebase configuration, sync Gradle, and run the `androidApp` configuration. To build from a terminal:

```sh
./gradlew :androidApp:assembleDebug
```

On Windows:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :androidApp:assembleDebug
```

The debug APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Project structure

| Directory | Purpose |
| --- | --- |
| `androidApp` | Android entry points, adapter transports, permissions, and AppFunctions integration |
| `composeApp` | Shared Compose screens, localization, bundled resources, and dependency wiring |
| `core` | Diagnostic engine, protocol parsers, simulator, and repository interfaces |
| `tools` | Local development helpers and asset imports |
| `docs` | Architecture and platform integration notes |

See [Architecture](docs/ARCHITECTURE.md) for module boundaries and dependency lifetimes.
