plugins {
    // Version-less: the Kotlin plugin is already on the build classpath via the
    // app module, as it is for :tools:dictc.
    kotlin("jvm")
}

// House rules as tests, read off the sources with Konsist; see
// ArchitectureTest. Run with every other suite by `./gradlew unitTests`, or
// alone:
//
//     ./gradlew :tools:architecture:test
//
// A plain JVM module on purpose. Konsist parses the sources it checks, among
// them the two biggest files in the project, and :app's test JVM is already
// close to its memory ceiling.

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.konsist)
}

tasks.test {
    maxHeapSize = "3g"
    // The rules read the whole source tree, not this module's classes, so the
    // tree is the task's real input: without it the task would stay up to date
    // through any change the rules exist to catch.
    inputs.files(
        fileTree(rootDir) {
            include("**/*.kt", "**/*.kts", "**/robolectric.properties")
            exclude("**/build/**", ".claude/**", ".gradle/**", "**/node_modules/**", "native/**/target/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sources")
}
