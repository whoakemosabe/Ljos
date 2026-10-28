import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Ljós web server: scores the sky for any location with the shared core, and serves the
// web app (phone and TV layouts). Uses only the JDK's built-in HTTP server, no framework.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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

application {
    mainClass.set("app.ljos.server.MainKt")
    applicationName = "ljos-server"
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20250517")

    testImplementation("junit:junit:4.13.2")
}
