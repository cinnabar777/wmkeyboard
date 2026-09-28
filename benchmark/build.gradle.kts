// Macrobenchmarks and the baseline profile generator, run on a real device
// against a release-like build of :app. Part of the build only with
//
//     -Pwmkb.benchmark=true
//
// (see settings.gradle.kts), because what it brings with it is not free for
// every other build: the baselineprofile plugin gives :app two more build
// types per flavour pair, and this module's tests install over the app on the
// connected device. Usage is in docs/src/content/docs/development/testing.mdx.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.wasimaster.wmkeyboard.benchmark"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Profile collection needs API 33, or 28 on a rooted device; below
        // that the generator has nothing to read the profile out of.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The same two dimensions :app has, so every app variant has a producer
    // variant to pair with. Only fullIntl is ever worth running; the others
    // exist so the pairing resolves.
    flavorDimensions += listOf("capabilities", "languages")
    productFlavors {
        create("full") { dimension = "capabilities" }
        create("lite") { dimension = "capabilities" }
        create("intl") { dimension = "languages" }
        create("en") { dimension = "languages" }
    }

    targetProjectPath = ":app"
    // Macrobenchmark drives another package from its own process.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

baselineProfile {
    // The phone on the desk, not a Gradle-managed emulator: the keyboard's
    // interesting paths need a real IME window, and profile collection wants a
    // device that behaves like the ones it ships to.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
