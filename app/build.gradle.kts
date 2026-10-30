import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing secrets live outside version control: keystore.properties (local) or
// env vars (CI). A hard-coded fallback would defeat the point of the leak fix.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun ks(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.utility.toolkit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.utility.toolkit"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "3.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = ks("storePassword", "RELEASE_STORE_PASSWORD")
            keyAlias = ks("keyAlias", "RELEASE_KEY_ALIAS") ?: "key0"
            keyPassword = ks("keyPassword", "RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Sign with the release key when secrets are present (keeps debug
            // installs in-place-updatable), else fall back to the default debug
            // key so a fresh clone can still build without the keystore.
            signingConfig = if (ks("storePassword", "RELEASE_STORE_PASSWORD") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            applicationIdSuffix = ""
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
