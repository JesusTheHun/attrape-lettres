plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// :art is the drawing layer — the SVG path runtime, the five mascots, the
// exercise icons. Compose Canvas only; it knows nothing about game state
// beyond the domain types it is handed. Mirrors ALArt.

android {
    namespace = "fr.dappit.attrapelettres.art"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 26
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
    api(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.annotation)

    testImplementation(libs.kotlin.test)
}
