import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One fixed signing key so every CI build installs over the previous one.
// It lives in signing/ (this repo is private). KEYSTORE_PASSWORD in the environment overrides the file.
val releaseKeystore = rootProject.file("signing/ljos-release.p12")
val signingProps = Properties().apply {
    val f = rootProject.file("signing/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val storePass: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: signingProps.getProperty("storePassword")
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.ljos"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.ljos"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists() && storePass != null) {
                storeFile = releaseKeystore
                storeType = "PKCS12"
                storePassword = storePass
                keyAlias = signingProps.getProperty("keyAlias") ?: "ljos"
                keyPassword = storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists() && storePass != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose 1.10, as the liquid glass library (Kyant's Backdrop 1.0.4) is built against it.
    val compose = "1.10.0"
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.ui:ui-graphics:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("io.github.kyant0:backdrop:1.0.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
