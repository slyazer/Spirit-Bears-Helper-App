import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- Release signing (external credentials only) --------------------------
// Credentials are NEVER stored in this repository or in gradle.properties.
// Supply them externally via environment variables or Gradle properties
// (e.g. ~/.gradle/gradle.properties or `-PMM_KEYSTORE=...`):
//   MM_KEYSTORE            path to the release keystore (.jks/.keystore)
//   MM_KEYSTORE_PASSWORD   keystore password
//   MM_KEY_ALIAS           key alias
//   MM_KEY_PASSWORD        key password
// If any value is missing the release variant stays UNSIGNED. Debug builds are
// unaffected and keep using the debug keystore. See RELEASE.md.
fun secret(name: String): String? =
    System.getenv(name) ?: (project.findProperty(name) as String?)

fun resolveKeystore(path: String): File {
    val candidate = File(path)
    // Relative paths are resolved against the repository root, not android/app.
    return if (candidate.isAbsolute) candidate else rootProject.file(path)
}

val releaseStorePath = secret("MM_KEYSTORE")
val releaseStorePassword = secret("MM_KEYSTORE_PASSWORD")
val releaseKeyAlias = secret("MM_KEY_ALIAS")
val releaseKeyPassword = secret("MM_KEY_PASSWORD")
val releaseStoreFile = releaseStorePath?.takeIf { it.isNotBlank() }?.let { resolveKeystore(it) }

val hasReleaseSigning = releaseStoreFile != null &&
    releaseStoreFile.exists() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

if (hasReleaseSigning) {
    logger.lifecycle("Release signing: ENABLED (keystore '${releaseStoreFile?.name}').")
} else {
    logger.lifecycle("Release signing: not configured - the release APK will be UNSIGNED. See RELEASE.md.")
}

android {
    namespace = "com.macromonitor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.macromonitor.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // QR pairing: CameraX preview + ML Kit barcode scanning (bundled model).
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
