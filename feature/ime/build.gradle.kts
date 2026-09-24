import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Same channel flag :app reads (see the flag() helper there): a Play-store
// build resolves it true, every other channel false. Here it swaps the two
// bundled ML Kit artifacts for their unbundled play-services twins — a Play
// install implies GMS, so the models can come from Play services instead of
// shipping ~18 MB per ABI in the AAB. Sideload and F-Droid builds keep the
// bundled artifacts (or none, for lite) so scanners never depend on GMS there.
// Both variants expose the identical com.google.mlkit.vision.* API, so no
// source changes ride on this flag.
val playStoreChannel = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    (providers.gradleProperty("wmkb.enablePlayStore").orNull
        ?: localProperties.getProperty("wmkb.enablePlayStore")
        ?: System.getenv("WMKB_ENABLE_PLAY_STORE")
        ?: "false").toBoolean()
}

android {
    namespace = "com.wasimaster.wmkeyboard.ime"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
    }

    flavorDimensions += "capabilities"
    productFlavors {
        create("full") { dimension = "capabilities" }
        create("lite") { dimension = "capabilities" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
    // Robolectric gives each test class its own sandbox classloader, which the
    // default 512m worker does not survive alongside this module's 486 plain
    // tests. 2g matches :app, where the same ceiling was found the hard way —
    // see the EOFException note there.
    testOptions.unitTests.all { it.maxHeapSize = "2g" }
    // Without the merged resources Robolectric knows no string at all, and
    // composables under test now resolve real ones: SearchQueryText reaches
    // PublishFieldSelection, which reads :core:common's cut/copy/paste labels.
    // Costs an AAPT2 link per test run of this module; :app keeps it off.
    testOptions.unitTests.isIncludeAndroidResources = true
}

// Compose compiler skippability/stability report, on demand:
//   ./gradlew :feature:ime:assembleFullDebug -PcomposeMetrics=true
// then read build/compose/reports/*-composables.txt. Inert without the flag, so
// ordinary builds neither slow down nor write the reports.
if (providers.gradleProperty("composeMetrics").isPresent) {
    composeCompiler {
        metricsDestination.set(layout.buildDirectory.dir("compose/metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose/reports"))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        extraWarnings.set(true)
        freeCompilerArgs.addAll("-Xreport-all-warnings")
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

dependencies {
    api(project(":core:config"))
    api(project(":core:common"))
    api(project(":core:language"))
    api(project(":core:input"))
    api(project(":core:keyman"))
    api(project(":core:prediction"))
    api(project(":core:emoji"))
    api(project(":core:theme"))
    api(project(":core:icons"))
    api(project(":core:content"))
    api(project(":core:tools"))
    api(project(":core:kdeconnect"))
    api(project(":core:settings"))
    api(project(":core:addons"))
    api(project(":core:voice"))
    api(project(":core:feedback"))
    api(project(":core:plugins"))
    api(project(":core:intelligence"))
    api(project(":feature:tools"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    if (playStoreChannel) {
        "fullImplementation"(libs.mlkit.text.recognition.unbundled)
        "fullImplementation"(libs.mlkit.barcode.scanning.unbundled)
    } else {
        "fullImplementation"(libs.mlkit.text.recognition)
        "fullImplementation"(libs.mlkit.barcode.scanning)
    }
    "fullImplementation"(libs.mlkit.document.scanner)

    testImplementation(libs.junit)
    // A virtual clock for the coroutine plumbing around the input connection.
    testImplementation(libs.kotlinx.coroutines.test)
    // An Android runtime on the JVM, so a test can drive WMKeyboardService
    // itself rather than only the pure helpers around it.
    testImplementation(libs.robolectric)
    // Compose's test rule and clock, for layout behaviour no plain test can
    // see: when RowRevealHeadroom lets the frame's height change while a bar
    // row animates.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The rule asks for older androidx.test artifacts than anything else here
    // resolves; pin the versions :app's instrumented tests already use.
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.espresso.core)
}
