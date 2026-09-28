import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

// Compose compiler reports for any module that applies this, on demand:
//
//   ./gradlew :feature:ime:compileFullDebugKotlin --rerun -PcomposeMetrics=true
//   scripts/compose-reports.sh feature/ime        # the same, then a summary
//
// They land in build/compose/reports (per composable: restartable, skippable,
// and every parameter's stability) and build/compose/metrics (per-module
// counts, as JSON). `--rerun` is not optional: the destinations are not task
// inputs, so an up-to-date or cached compile writes nothing.
//
// Inert without the property, so ordinary builds neither slow down nor write
// anything. Applied beside the compose plugin rather than through a shared
// `subprojects {}` block, for the same isolated-projects reason the detekt
// convention is.
//
// Read the parameter lists, not the `skippable` flag: under strong skipping
// every restartable composable is marked skippable, and what actually decides
// whether it skips is an unstable parameter arriving as a fresh instance.
val composeMetrics = providers.gradleProperty("composeMetrics")
    .map { it.isEmpty() || it.toBoolean() }
    .getOrElse(false)

if (composeMetrics) {
    pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            metricsDestination.set(layout.buildDirectory.dir("compose/metrics"))
            reportsDestination.set(layout.buildDirectory.dir("compose/reports"))
        }
    }
}
