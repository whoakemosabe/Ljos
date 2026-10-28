// AGP 9 compiles Kotlin itself (built-in Kotlin), so only the Compose compiler plugin is added.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // Plain JVM module for the shared scoring core (core/).
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}
