import java.util.Properties

// Version: "0.1.9" here; release builds get it from the git tag (-PappVersion=1.2.3 in .github/workflows/release.yml).
// versionCode follows from it (major * 10000 + minor * 100 + patch), so every release installs over the previous one.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: "0.1.9"
val appVersionCode = (appVersion.split('.', '-').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)).let { it[0] * 10000 + it[1] * 100 + it[2] }

// Over-the-air updates: the GitHub repository ("owner/name") whose releases the app checks. In GitHub Actions it is the
// repository being built (so forks update from themselves); elsewhere `updateRepo` from gradle.properties or -PupdateRepo=….
val updateRepo = System.getenv("GITHUB_REPOSITORY")?.takeIf { it.isNotBlank() } ?: (findProperty("updateRepo") as String?).orEmpty()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.techkrzysiek.betternvr"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.techkrzysiek.betternvr"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    // Release key lives outside version control: keystore.properties + keystore/ locally (back both up),
    // RELEASE_KEYSTORE* environment variables in the release workflow (from the repository's secrets).
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    val envKeystore = System.getenv("RELEASE_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        } else if (envKeystore != null) create("release") {
            storeFile = file(envKeystore)
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR codes of recorders (offline, no Google services)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
