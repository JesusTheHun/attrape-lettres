plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// :app is the entry point and the composition root, and close to nothing else —
// the Android twin of App/AttrapeLettresApp.swift. It is the ONLY module that
// may see both :ui and :platform, because it is the one place that wires a real
// device adapter into an interface :ui only knows abstractly.

android {
    namespace = "fr.dappit.attrapelettres"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        // Not `fr.dappit.attrape-lettres` (the iOS bundle id): a hyphen is
        // illegal in an Android package segment. This matches the StoreKit
        // product prefix `fr.dappit.attrapelettres.*` instead.
        applicationId = "fr.dappit.attrapelettres"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":platform"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // :core and :platform both take coroutines as `implementation`, so the
    // types are not on this module's compile classpath transitively — and the
    // composition root names them directly: the process-lifetime CoroutineScope
    // telemetry sends run on is built here.
    implementation(libs.kotlinx.coroutines.core)
    // The Main dispatcher's ServiceLoader binding — see the catalog note. It is
    // on the classpath transitively today; naming it here is what stops a future
    // dependency tidy-up from turning it into a crash in Application.onCreate.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
    // A9 — `kotlin.test.Test` is an expect-typealias and an Android variant has
    // no `useJUnitPlatform()` to pick the actual for it. Same artifact family
    // and version as the line above, testImplementation only, nothing in the APK.
    testImplementation(libs.kotlin.test.junit)
}
