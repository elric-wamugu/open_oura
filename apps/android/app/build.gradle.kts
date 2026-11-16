plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.openoura.android"
    compileSdk {
        version = release(37)
    }
    // Lets AGP find the strip tool for liboura_core.so. Without it the unstripped ~7.7 MB
    // library ships as-is (build-aar.sh must keep symbols so UniFFI's bindgen can read
    // .symtab — see the comment there).
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "org.openoura.android"
        minSdk = 26
        // Pinned to the target device (Pixel 9a, Android 17 = API 37). compileSdk stays on
        // the newest SDK — that only affects what APIs are visible — but targetSdk selects
        // which runtime behaviour changes apply, so only raise it once the new level's
        // changes have been tested on the phone.
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only the ABI we build the Rust core for. JNA otherwise packages its
        // dispatch library for mips/x86/armeabi too, none of which we can ever run.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // R8 shrinks, inlines and strips what the debug build ships untouched, and a
            // release build is not debuggable, so ART compiles it ahead of time from the
            // baseline profiles Compose and the AndroidX libraries carry. The debug build
            // gets neither: it is what the phone ran until 2026-11, and the slower of the two
            // to start and to scroll.
            // The app's own keep rules are in src/main/keepRules/, which AGP picks up itself.
            optimization {
                enable = true
            }
            // The same key as the debug build, on purpose. Android only installs an update
            // over an app whose signature matches, and the copy on the phone is signed with
            // this one. A separate release key would mean uninstalling first — which deletes
            // oura.db, months of history the ring no longer holds. With one key either build
            // installs over the other with `adb install -r`.
            signingConfig = signingConfigs.getByName("debug")
            // Lets simpleperf and Perfetto attach over adb without the build being
            // debuggable, so what gets profiled is the code that actually runs.
            isProfileable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        // MaterialShapes and LoadingIndicator are still experimental in material3 1.5.0;
        // opting in once here keeps @OptIn noise out of every screen.
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Rust core: generated UniFFI bindings (app/src/main/java/uniffi/) reach
    // jniLibs/arm64-v8a/liboura_core.so through JNA. Both are produced by
    // apps/android/build-aar.sh — run it before the first Gradle sync.
    implementation(variantOf(libs.jna) { artifactType("aar") })
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.health.connect.client)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}