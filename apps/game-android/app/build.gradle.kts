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

    testImplementation(libs.kotlin.test)
}
