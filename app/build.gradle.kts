import java.util.Properties

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
    compileSdk = 35

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
    kotlinOptions {
        jvmTarget = "17"
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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
