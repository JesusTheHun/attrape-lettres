import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core is a PLAIN JVM module on purpose — it does not apply the Android
// plugin and cannot reference `android.*`. That is the Kotlin analogue of
// ALCore being "pure Swift, no SwiftUI, no UIKit, no StoreKit, no network":
// `./gradlew :core:test` runs the entire game's logic as ordinary JUnit, in
// milliseconds, with no emulator and no SDK on the test path.
//
// The platform is reached through interfaces declared here (KVStore,
// TimeSource, RandomSource, PurchaseStore, SyncTransport, TelemetryTransport,
// AudioEngine, Haptics, ReduceMotionSource, AppVersionProvider) and injected at
// the root. Adding an Android dependency to this module breaks that, so don't.

kotlin {
    // Compiled BY 21, FOR 17 — :platform, :ui and :app consume this jar as an
    // ordinary Android dependency, and Android's floor is Java 17 bytecode.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
