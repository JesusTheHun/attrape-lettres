plugins {
    alias(libs.plugins.android.library)
}

// :platform holds every Android adapter behind a :core interface —
// SharedPreferences, SoundPool/AudioTrack, OkHttp-free HTTP, Play Billing.
// Imported by :app only, never by :ui. Mirrors ALPlatform.
//
// The baked voice-over is STAGED into src/main/assets/vo by
// scripts/stage-vo.sh, never committed: the 855 clips live once, in
// apps/game-web/src/vo/clips/. What IS committed is src/main/assets/vo-manifest.txt,
// so the coverage test ("every utterance the app can speak has a clip") runs on
// a machine that has never staged the audio.

android {
    namespace = "fr.dappit.attrapelettres.platform"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // See :art — `kotlin.test.Test` needs a runner to alias onto, and an
    // Android library has no `useJUnitPlatform()`.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
