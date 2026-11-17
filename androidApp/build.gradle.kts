import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
}

// Replacement upload key confirmed active in Play Console by the owner on 2026-11-15.
val expectedUploadCertificateSha256 = "F1:E2:B7:D8:B6:AC:FF:65:C0:62:58:A1:8F:C0:0A:71:10:B5:41:EB:15:F3:DC:9A:5C:6B:E8:6F:61:D1:F6:D0"
val signingRoot = providers.environmentVariable("SIGNING_ROOT")
    .getOrElse("${System.getProperty("user.home")}/.signing")
val persistentSigningDirectory = File(signingRoot, "ecu-vue")
val persistentSigningProperties = persistentSigningDirectory.resolve("key.properties")
val signingPropertiesFile = providers.gradleProperty("ecuVueSigningProperties")
    .orElse(providers.environmentVariable("ECU_VUE_SIGNING_PROPERTIES"))
    .map { rootProject.file(it) }
    .getOrElse(persistentSigningProperties)
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}
val requiredSigningFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

fun verifyUploadIdentity(propertiesFile: File): Properties {
    check(propertiesFile.isFile) {
        "Upload signing configuration missing: $propertiesFile. " +
            "Restore your existing key using :androidApp:importUploadSigning " +
            "-PecuVueImportSigningProperties=<path>, or set ECU_VUE_SIGNING_PROPERTIES. See docs/RELEASE_SIGNING.md."
    }
    val properties = Properties().apply { propertiesFile.inputStream().use { load(it) } }
    val missing = requiredSigningFields.filter { properties.getProperty(it).isNullOrBlank() }
    check(missing.isEmpty()) { "Signing configuration is missing fields: ${missing.joinToString()}. No passwords are logged." }
    val keystoreFile = propertiesFile.parentFile.resolve(properties.getProperty("storeFile"))
    check(keystoreFile.isFile) { "Upload keystore missing: $keystoreFile. Restore it from your secure backup; .der/.pem certificates cannot sign releases." }
    val keystore = try {
        KeyStore.getInstance(keystoreFile, properties.getProperty("storePassword").toCharArray())
    } catch (_: Exception) {
        error("Cannot open the upload keystore. Check its format and store password locally; do not paste passwords into chat.")
    }
    val alias = properties.getProperty("keyAlias")
    check(keystore.isKeyEntry(alias)) { "The configured alias does not contain a private key." }
    val privateKey = try {
        keystore.getKey(alias, properties.getProperty("keyPassword").toCharArray())
    } catch (_: Exception) {
        error("Cannot unlock the upload private key. Check the key password locally.")
    }
    check(privateKey is PrivateKey) { "The configured alias must contain a private signing key." }
    val certificate = checkNotNull(keystore.getCertificate(alias)) { "Upload certificate missing from the keystore." }
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    check(fingerprint == expectedUploadCertificateSha256) {
        "Wrong upload key. Expected SHA-256 $expectedUploadCertificateSha256; found $fingerprint. " +
            "Use the existing Play upload key. Change the pinned fingerprint only after an approved Play upload-key reset."
    }
    return properties
}

fun copyUploadSigning(source: File, destination: File) {
    val properties = verifyUploadIdentity(source)
    check(!destination.canonicalFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        "Signing material must be stored outside the project. Choose a secure private backup directory."
    }
    val targetKey = destination.resolve("upload-keystore.jks")
    val targetProperties = destination.resolve("key.properties")
    check(!targetKey.exists() && !targetProperties.exists()) {
        "Signing files already exist in $destination. Existing private keys are never overwritten."
    }
    check(destination.isDirectory || destination.mkdirs()) { "Could not create signing directory: $destination" }
    source.parentFile.resolve(properties.getProperty("storeFile")).copyTo(targetKey)
    properties.setProperty("storeFile", targetKey.name)
    Files.newOutputStream(targetProperties.toPath(), StandardOpenOption.CREATE_NEW).use {
        properties.store(it, "ECU Vue private upload signing configuration. Keep outside source control and back up securely.")
    }
    verifyUploadIdentity(targetProperties)
}

android {
    namespace = "app.leximo.ecu_vue"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.leximo.ecu_vue"
        minSdk = 24
        targetSdk = 36
        versionCode = 14
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (requiredSigningFields.all { !signingProperties.getProperty(it).isNullOrBlank() }) {
        signingConfigs.create("playUpload") {
            storeFile = signingPropertiesFile.parentFile.resolve(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            if (signingConfigs.findByName("playUpload") != null) signingConfig = signingConfigs.getByName("playUpload")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
val verifyAppFunctionAppMetadata = tasks.register("verifyAppFunctionAppMetadata") {
    group = "verification"
    description = "Check app-level AppFunctions descriptions before packaging for Google Play."
    val metadataFile = layout.projectDirectory.file("src/main/res/xml/app_metadata.xml")
    val stringsFile = layout.projectDirectory.file("src/main/res/values/strings.xml")
    inputs.files(metadataFile, stringsFile)
    doLast {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val parser = factory.newDocumentBuilder()
        val root = parser.parse(metadataFile.asFile).documentElement
        check(root.tagName == "AppFunctionAppMetadata") {
            "AppFunctions app metadata must use the AppFunctionAppMetadata root element."
        }
        val namespace = "http://schemas.android.com/apk/res-auto"
        val description = root.getAttributeNS(namespace, "description")
        check(description.isNotBlank() && !description.startsWith("@")) {
            "AppFunctions requires a nonempty appfn:description attribute with inline agent instructions."
        }
        val displayDescription = root.getAttributeNS(namespace, "displayDescription")
        check(displayDescription.startsWith("@string/")) {
            "AppFunctions requires appfn:displayDescription referencing a nonempty string resource."
        }
        val resourceName = displayDescription.removePrefix("@string/")
        val strings = parser.parse(stringsFile.asFile).getElementsByTagName("string")
        check((0 until strings.length).any { index ->
            val element = strings.item(index) as org.w3c.dom.Element
            element.getAttribute("name") == resourceName && element.textContent.isNotBlank()
        }) { "AppFunctions display description string is missing or empty: $resourceName" }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyAppFunctionAppMetadata) }

val verifyUploadSigning = tasks.register("verifyUploadSigning") {
    group = "verification"
    description = "Verify the private upload key against the fingerprint registered with Google Play."
    doLast {
        verifyUploadIdentity(signingPropertiesFile)
        logger.lifecycle("Verified ECU Vue upload key: {}", expectedUploadCertificateSha256)
        logger.lifecycle("Signing configuration: {}", signingPropertiesFile)
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyUploadSigning) }

tasks.register("importUploadSigning") {
    group = "publishing"
    description = "Copy an existing verified signing identity to the persistent user signing directory."
    doLast {
        val source = providers.gradleProperty("ecuVueImportSigningProperties").orNull
        check(!source.isNullOrBlank()) { "Pass -PecuVueImportSigningProperties=<existing key.properties path>." }
        copyUploadSigning(rootProject.file(source), persistentSigningDirectory)
        logger.lifecycle("Imported signing material to {}. Back it up using :androidApp:backupUploadSigning; see docs/RELEASE_SIGNING.md.", persistentSigningDirectory)
    }
}

tasks.register("backupUploadSigning") {
    group = "publishing"
    description = "Copy verified signing files to an explicitly selected private backup directory."
    doLast {
        val destination = providers.gradleProperty("ecuVueSigningBackupDirectory").orNull
        check(!destination.isNullOrBlank()) { "Pass -PecuVueSigningBackupDirectory=<secure backup directory outside the project>." }
        copyUploadSigning(signingPropertiesFile, rootProject.file(destination))
        logger.lifecycle("Verified signing backup created in {}. Protect it as private key material.", destination)
    }
}

tasks.register("preparePlayUpload") {
    group = "publishing"
    description = "Build a signed bundle and copy it with its version and checksum for manual Play upload."
    dependsOn("bundleRelease")
    doLast {
        val bundle = layout.buildDirectory.file("outputs/bundle/release/androidApp-release.aab").get().asFile
        check(bundle.isFile) { "Release bundle was not produced." }
        val destination = layout.buildDirectory.dir("outputs/play").get().asFile.apply { mkdirs() }
        val uploadBundle = destination.resolve("ECU-Vue-${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}.aab")
        bundle.copyTo(uploadBundle, overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256")
        uploadBundle.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            var count = stream.read(buffer)
            while (count != -1) { digest.update(buffer, 0, count); count = stream.read(buffer) }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        destination.resolve("${uploadBundle.name}.sha256").writeText("$checksum  ${uploadBundle.name}\n")
        logger.lifecycle("Play upload bundle: {}", uploadBundle)
        logger.lifecycle("Version {} (build {}); upload certificate SHA-256 {}", android.defaultConfig.versionName, android.defaultConfig.versionCode, expectedUploadCertificateSha256)
    }
}
dependencies {
    implementation(project(":composeApp"))
    implementation("io.insert-koin:koin-android:4.2.2")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha13")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha13")
    implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.8.0-alpha03")
    androidTestImplementation("androidx.test.ext:junit:1.4.0-alpha03")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha03")
    androidTestImplementation("androidx.test:rules:1.8.0-alpha03")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")
}
