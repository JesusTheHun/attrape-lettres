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

    // `kotlin.test.Test` is a typealias that needs a runner behind it. :core
    // gets its binding from `useJUnitPlatform()`; an Android library variant has
    // no equivalent switch, so the JUnit 4 binding is named here or every
    // `import kotlin.test.Test` in this module is unresolved.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
