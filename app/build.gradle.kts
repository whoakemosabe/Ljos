import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One fixed signing key so every CI build installs over the previous one. It is never in the
// repo: CI decodes it from the KEYSTORE_B64 secret to a temp file (KEYSTORE_FILE) and reads the
// password from the KEYSTORE_PASSWORD secret. Without them the build falls back to debug signing.
val releaseKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    ?: rootProject.file("signing/ljos-release.p12") // local builds only; signing/ is gitignored
val storePass: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.ljos"
    compileSdk = 37

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
                keyAlias = "ljos"
                keyPassword = storePass
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks and optimizes the release APK. Libraries (Compose, Glance, WorkManager,
            // coroutines) bring their own keep rules; app-specific ones go in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

dependencies {
    implementation(project(":core"))

    val compose = "1.12.1"
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.ui:ui-graphics:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
