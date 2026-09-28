// Top-level build file where you can add configuration options common to all sub-projects/modules.

// The Dependency Analysis plugin (3.19.x) brings a Kotlin 2.4 BOM onto the
// build classpath, which drags the Kotlin *daemon client* up to 2.4 while the
// compiler plugin stays 2.2. Every compile then logs "Daemon compilation
// failed: NoSuchMethodError … connectAndLease" and falls back to a separate
// compiler process: the build passes, slower and noisily. Holding the client
// to the compiler's own version puts the daemon back.
buildscript {
    dependencies {
        constraints {
            classpath("org.jetbrains.kotlin:kotlin-daemon-client") {
                version { strictly(libs.versions.kotlin.get()) }
                because("the compiler plugin talks to a daemon of its own version")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Applied per-module rather than through `subprojects {}` so each project
    // configures itself — cross-project configuration is what Gradle's isolated
    // projects mode forbids, and it breaks configuration-cache reuse.
    alias(libs.plugins.detekt) apply false
    // :benchmark and, under -Pwmkb.benchmark=true, :app's profile consumer.
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    // Applied here, and by id in each module: the root is what aggregates.
    //   ./gradlew buildHealth            unused and mis-scoped dependencies
    //   ./gradlew koverHtmlReportUnit    unit-test coverage, all modules merged
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.kover)
}

// Coverage, merged across every module's full-flavour debug unit tests. The
// tests mostly live in :app and exercise :core:* classes, so only the merged
// report says anything useful about a library module.
//
//   ./gradlew koverHtmlReportUnit     build/reports/kover/htmlUnit/index.html
//   ./gradlew koverXmlReportUnit      build/reports/kover/reportUnit.xml
//
// The plain koverHtmlReport would run every variant's tests (lite, release,
// fast…), several times the work for the same classes.
dependencies {
    kover(project(":app"))
    listOf(
        ":core:config", ":core:common", ":core:language", ":core:input", ":core:keyman",
        ":core:prediction", ":core:emoji", ":core:theme", ":core:icons", ":core:tools",
        ":core:kdeconnect", ":core:content", ":core:addons", ":core:voice", ":core:settings",
        ":core:feedback", ":core:plugins", ":core:intelligence",
        ":feature:tools", ":feature:addons", ":feature:ime",
    ).forEach { kover(project(it)) }
}

kover {
    currentProject {
        createVariant("unit") {}
    }
    reports {
        filters {
            excludes {
                // Generated code: resource ids, build flags, serializers and
                // the compose compiler's singletons.
                classes("*.R", "*.R$*", "*.BuildConfig", "*\$\$serializer", "*ComposableSingletons*")
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }
    }
}

// Dependency Analysis gives advice, it does not gate: buildHealth reports and
// passes. Which variants it reads is set in gradle.properties.
dependencyAnalysis {
    issues {
        all {
            onAny { severity("warn") }
        }
    }
}

// One entry point for every analyser, so CI and humans run the same thing:
//   ./gradlew staticAnalysis
// Type-resolution detekt (detektFullDebug) is deliberately included instead of
// the source-only `detekt` task — the rules that find real bugs (nullability,
// unreachable code, ignored return values) need a resolved classpath.
// Everything that guards the converted Keyman layouts:
//   ./gradlew keymanCheck
// The engine's conformance tests, the converter, the package and .js readers,
// and the parity check that the 862 committed grids still match what the
// converter produces. Worth running as a unit after touching the converter,
// because a change there is invisible until the assets are regenerated.
//
// Two things are deliberately absent. The whole-corpus sweep needs a 78 MB
// checkout and skips without one:
//   KEYMAN_CORPUS=<checkout> ./gradlew :core:keyman:testFullDebugUnitTest
// And KeymanConversionParityTest lives in :app, because it reads the committed
// assets; :app's suite is large enough to want running on its own, so it is not
// pulled in here:
//   ./gradlew :app:testFullIntlDebugUnitTest --tests '*KeymanConversionParityTest*'
// Every unit test in the project, under one name.
//
// This exists because :app and the library modules no longer agree on what a
// variant is called. :app carries the `languages` flavour dimension and the
// libraries do not, so :app's suite is testFullIntlDebugUnitTest while theirs
// are still testFullDebugUnitTest. An unqualified `./gradlew
// testFullDebugUnitTest` therefore runs the libraries and silently skips the
// ~560 tests in :app, which is the sort of quiet hole that only shows up as a
// bug in production. Run this instead.
tasks.register("unitTests") {
    group = "verification"
    description = "Runs every module's unit tests, :app included, across the two variant naming schemes."
    dependsOn(":app:testFullIntlDebugUnitTest")
    // Only the library modules. `:core` and `:feature` are bare containers
    // with no build file and no tasks, and `:tools:dictc` is a plain JVM tool
    // whose task is `test`, so neither takes a flavoured task name.
    dependsOn(
        subprojects
            .filter { it.buildFile.exists() }
            .filter { it.path.startsWith(":core:") || it.path.startsWith(":feature:") }
            .map { "${it.path}:testFullDebugUnitTest" },
    )
    // The architecture rules: a plain JVM module, so plain `test`.
    dependsOn(":tools:architecture:test")
}

tasks.register("keymanCheck") {
    group = "verification"
    description = "Runs the Keyman engine, converter, package and seam tests."
    dependsOn(
        ":core:keyman:testFullDebugUnitTest",
        ":core:language:testFullDebugUnitTest",
        ":feature:ime:testFullDebugUnitTest",
    )
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs every static analyser: Android Lint, detekt (with type resolution), and Kotlin compiler diagnostics."
    // :tools:dictc is intentionally absent — its whole source set is symlinked
    // app sources (see tools/dictc/build.gradle.kts), so analysing it would
    // report the same five files twice.
    dependsOn(
        ":app:lintFullIntlDebug",
        ":app:detektFullDebug",
        // The lite flavour compiles a different set of sources (the stubs in
        // src/lite replace the ML Kit / LiteRT implementations), so it needs its
        // own pass — a bug in a stub is invisible to the full-flavour run.
        ":app:detektLiteDebug",
        ":app:detektFullDebugUnitTest",
    )
    // Per-module detekt (registered by the wmkeyboard.detekt convention
    // plugin): each module analyses its own sources against its own compile
    // classpath. :core:config is absent — it has no Kotlin sources. Lite
    // passes only where lite compiles different code (flavored modules);
    // everywhere else the two variants' sources are identical.
    val detektModules = listOf(
        ":core:common", ":core:language", ":core:input", ":core:keyman", ":core:prediction",
        ":core:emoji", ":core:theme", ":core:icons", ":core:tools",
        ":core:content", ":core:addons", ":core:voice", ":core:settings",
        ":core:feedback", ":core:plugins", ":core:intelligence",
        ":feature:tools", ":feature:addons", ":feature:ime",
    )
    dependsOn(detektModules.map { "$it:detektFullDebug" })
    dependsOn(listOf(":core:voice", ":core:intelligence", ":feature:ime").map { "$it:detektLiteDebug" })
}
