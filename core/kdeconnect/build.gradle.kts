plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.serialization)
    // Applied at the root with their versions; see build.gradle.kts there.
    id("org.jetbrains.kotlinx.kover")
    id("com.autonomousapps.dependency-analysis")
}

// Coverage for the root's merged report (`./gradlew koverHtmlReportUnit`):
// this module's full-flavour debug unit tests.
kover {
    currentProject {
        createVariant("unit") { add("fullDebug") }
    }
}

// The KDE Connect protocol engine (issue #285). An Android library because
// every module here carries the `capabilities` dimension, but nothing under
// src/main touches an Android class: it is java.net, javax.net.ssl,
// java.security and coroutines, so the whole protocol — TLS handshakes and
// pairing included — runs in plain JVM unit tests, two engines on localhost.
android {
    namespace = "com.wasimaster.wmkeyboard.kdeconnect"
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
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }

    testOptions {
        unitTests.all {
            // The interop tests talk to a real KDE Connect daemon on the LAN
            // and are skipped unless asked for: -Pkde.interop=1
            it.systemProperty("kde.interop", providers.gradleProperty("kde.interop").getOrElse(""))
            it.systemProperty("kde.interop.dir", providers.gradleProperty("kde.interop.dir").getOrElse(""))
            it.systemProperty("kde.interop.tls", providers.gradleProperty("kde.interop.tls").getOrElse(""))
        }
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
