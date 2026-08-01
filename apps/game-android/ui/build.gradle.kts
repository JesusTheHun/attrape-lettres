plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// :ui is every screen — hub, exercises, shop, and the three adult screens.
//
// It deliberately does NOT depend on :platform. Audio, storage, purchases and
// the clock arrive as :core interfaces injected at the root, which is the only
// reason invariant 1 (feedback fires inside the pointer-down handler, before
// recomposition) is enforceable: an async adapter cannot implement a
// synchronous interface, so the compiler refuses the shape that would break it.

android {
    namespace = "fr.dappit.attrapelettres.ui"
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
    api(project(":art"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
