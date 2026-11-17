"""One-time asset/localization import; reads the Flutter tree without modifying it."""
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / "ecu_vue_flutter"

def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)

for name in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
    copy(SOURCE / "android" / name, ROOT / name)
(ROOT / "local.properties").write_text("\n".join(line for line in (SOURCE / "android/local.properties").read_text().splitlines() if line.startswith("sdk.dir=")) + "\n", encoding="utf-8")
copy(SOURCE / "android/app/google-services.json", ROOT / "androidApp/google-services.json")
for item in (SOURCE / "android/app/src/main/res").rglob("*"):
    if item.is_file():
        copy(item, ROOT / "androidApp/src/main/res" / item.relative_to(SOURCE / "android/app/src/main/res"))
for name in ("codes.v1.json", "vehicle_manufacturers.v1.json", "NOTICE.txt"):
    copy(SOURCE / "assets/reference" / name, ROOT / "composeApp/src/commonMain/composeResources/files/reference" / name)
copy(SOURCE / "assets/branding/app-icon-light.png", ROOT / "composeApp/src/commonMain/composeResources/drawable/brand_light.png")
copy(SOURCE / "assets/branding/app-icon.png", ROOT / "composeApp/src/commonMain/composeResources/drawable/brand_dark.png")
copy(SOURCE / "android/app/src/main/res/drawable/ic_launcher_monochrome.xml", ROOT / "composeApp/src/commonMain/composeResources/drawable/brand_mark.xml")

# Preserve the translated copy and placeholder names. ICU plurals aren't used by this app.
locales = {}
for item in sorted((SOURCE / "lib/l10n").glob("app_*.arb")):
    data = json.loads(item.read_text(encoding="utf-8"))
    locales[data["@@locale"]] = {k: v for k, v in data.items() if not k.startswith("@")}
locales["en"].update({
    "busy": "The adapter is busy. Wait for the current operation to finish, then try again.",
    "reading": "Reading vehicle data…",
    "aiAccessDisabled": "Enable AI assistant access in Settings first.",
    "invalidCode": "Enter a five-character OBD code, such as P0301.",
    "aiAccess": "AI assistant access",
    "aiAccessDescription": "Allow assistants to read reports and request fresh readings from your connected adapter. They cannot clear codes or change vehicle settings. Your VIN is not included in tool results.",
    "aiConnectionHint": "Connect your adapter in ECU Vue first. Assistants can read the latest saved report when a live connection is unavailable. Tool results may be processed by your assistant's service.",
    "aiVersionHint": "System assistant tools require Android 16 or later.",
    "credits": "Reference credits",
})
for locale, copy_overrides in json.loads((ROOT / "tools/connection_copy.json").read_text(encoding="utf-8")).items():
    locales[locale].update(copy_overrides)
target = ROOT / "composeApp/src/commonMain/composeResources/files/translations.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(locales, ensure_ascii=False), encoding="utf-8")

# Keep the native launcher aliases; remove Flutter-only activity configuration.
android = "http://schemas.android.com/apk/res/android"
tools = "http://schemas.android.com/tools"
ET.register_namespace("android", android)
ET.register_namespace("tools", tools)
tree = ET.parse(SOURCE / "android/app/src/main/AndroidManifest.xml")
manifest = tree.getroot()
app = manifest.find("application")
app.set(f"{{{android}}}theme", "@style/AppTheme")
app.set(f"{{{android}}}supportsRtl", "true")
app.set(f"{{{android}}}name", ".EcuVueApplication")
for permission in ("FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "POST_NOTIFICATIONS", "CHANGE_NETWORK_STATE"):
    ET.SubElement(manifest, "uses-permission", {f"{{{android}}}name": "android.permission." + permission})
for child in list(app):
    if child.tag == "meta-data" and "flutter" in child.get(f"{{{android}}}name", "").lower():
        app.remove(child)
activity = app.find("activity")
activity.set(f"{{{android}}}theme", "@style/AppStartingTheme")
activity.attrib.pop(f"{{{android}}}taskAffinity", None)
for child in list(activity):
    activity.remove(child)
ET.SubElement(app, "service", {
    f"{{{android}}}name": ".DiagnosticConnectionService",
    f"{{{android}}}exported": "false",
    f"{{{android}}}foregroundServiceType": "connectedDevice",
})
service = ET.SubElement(app, "service", {
    f"{{{android}}}name": ".ai.DiagnosticAppFunctionService",
    f"{{{android}}}permission": "android.permission.BIND_APP_FUNCTION_SERVICE",
    f"{{{android}}}exported": "true",
    f"{{{tools}}}targetApi": "36",
})
ET.SubElement(service, "property", {f"{{{android}}}name": "android.app.appfunctions.schema", f"{{{android}}}value": "app_functions_schema.xsd"})
ET.SubElement(service, "property", {f"{{{android}}}name": "android.app.appfunctions", f"{{{android}}}value": "ecu_vue_functions.xml"})
ET.SubElement(service, "property", {f"{{{android}}}name": "android.app.appfunctions.v2", f"{{{android}}}value": "ecu_vue_functions.xml"})
intent = ET.SubElement(service, "intent-filter")
ET.SubElement(intent, "action", {f"{{{android}}}name": "android.app.appfunctions.AppFunctionService"})
ET.SubElement(app, "property", {f"{{{android}}}name": "android.app.appfunctions.app_metadata", f"{{{android}}}resource": "@xml/app_metadata"})
target = ROOT / "androidApp/src/main/AndroidManifest.xml"
ET.indent(tree)
tree.write(target, encoding="unicode", xml_declaration=False)

# Small Kotlin compatibility helper has no Flutter dependency.
for name in ("RfcommConnector.kt",):
    copy(SOURCE / "android/app/src/main/kotlin/app/leximo/ecu_vue" / name,
         ROOT / "androidApp/src/main/kotlin/app/leximo/ecu_vue" / name)
print(f"Imported {len(locales)} languages, diagnostic reference data, and Android branding.")
