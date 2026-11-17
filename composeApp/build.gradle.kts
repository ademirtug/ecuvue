plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin {
    android {
        namespace = "app.leximo.ecu_vue.ui"
        compileSdk = 37
        minSdk = 24
        androidResources.enable = true
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "EcuVue"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            api("io.insert-koin:koin-core:4.2.2")
            implementation("io.insert-koin:koin-compose:4.2.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
}
compose.resources {
    publicResClass = true
    packageOfResClass = "app.leximo.ecu_vue.resources"
}
compose.desktop {
    application {
        mainClass = "app.leximo.ecu_vue.desktop.MainKt"
        nativeDistributions {
            packageName = "ECU Vue"
            packageVersion = "0.2.0"
        }
    }
}
