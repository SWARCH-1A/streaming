plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
}
kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "VaultMobile"; isStatic = true }
    }
    sourceSets {
        commonMain { kotlin.srcDir("../../../contracts/generated/mobile") }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("io.ktor:ktor-client-core:3.3.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
            implementation("io.ktor:ktor-client-websockets:3.3.3")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.3.3")
            implementation("androidx.media3:media3-exoplayer:1.9.0")
            implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
            implementation("androidx.media3:media3-ui:1.9.0")
            implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
            implementation("androidx.activity:activity-compose:1.12.2")
        }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.3.3") }
        getByName("desktopMain").dependencies { implementation("io.ktor:ktor-client-cio:3.3.3") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation("io.ktor:ktor-client-mock:3.3.3")
        }
    }
}
android {
    namespace = "streaming.mobile.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

compose.resources { packageOfResClass = "streaming.mobile.resources" }
