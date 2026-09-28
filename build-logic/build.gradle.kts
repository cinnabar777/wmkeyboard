plugins {
    `kotlin-dsl`
}

// Keep in step with gradle/libs.versions.toml (agp / kotlin / detekt).
dependencies {
    implementation("com.android.tools.build:gradle:9.3.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
    // The Compose compiler's Gradle extension, for the wmkeyboard.compose-metrics convention.
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.20")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
}
