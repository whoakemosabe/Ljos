import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM: the scoring model, sun/moon maths and feed parsers. No Android here, so the
// same code compiles into the app and into the server.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Android ships org.json in the platform, so the app must not bundle it; the server adds it.
    compileOnly("org.json:json:20250517")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
