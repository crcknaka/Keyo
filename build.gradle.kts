plugins {
    id("com.android.application") version "8.11.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // Compose compiler is a Kotlin plugin since Kotlin 2.0 (replaces kotlinCompilerExtensionVersion).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
