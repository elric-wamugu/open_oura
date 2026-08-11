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
        // Pinned to the target device (Pixel 5, Android 14 = API 34). compileSdk stays on
        // the newest SDK — that only affects what APIs are visible — but targetSdk selects
        // which runtime behaviour changes apply, and there is no Android 15+ hardware here
        // to test those on.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only the ABI we build the Rust core for. JNA otherwise packages its
        // dispatch library for mips/x86/armeabi too, none of which we can ever run.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Rust core: generated UniFFI bindings (app/src/main/java/uniffi/) reach
    // jniLibs/arm64-v8a/liboura_core.so through JNA. Both are produced by
    // apps/android/build-aar.sh — run it before the first Gradle sync.
    implementation(variantOf(libs.jna) { artifactType("aar") })
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}