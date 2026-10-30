import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "net.kgtkr.polytwi"
    compileSdk = 34

    val localPropertiesFile = rootProject.file("local.properties")
    val localProperties = Properties()
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    val customVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull()
        ?: System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
    val customVersionName = project.findProperty("versionName")?.toString()
        ?: System.getenv("VERSION_NAME") ?: "1.0"
    val pushBridgeUrl = (project.findProperty("pushBridgeUrl")?.toString()
        ?: System.getenv("PUSH_BRIDGE_URL")
        ?: localProperties.getProperty("pushBridgeUrl")
        ?: localProperties.getProperty("PUSH_BRIDGE_URL")
        ?: "").trimEnd('/')

    defaultConfig {
        applicationId = "net.kgtkr.polytwi"
        minSdk = 29
        targetSdk = 34
        versionCode = customVersionCode
        versionName = customVersionName

        buildConfigField("String", "PUSH_BRIDGE_URL", "\"$pushBridgeUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    signingConfigs {
        val storeFilePath = keystoreProperties.getProperty("storeFile")
            ?: System.getenv("KEYSTORE_FILE")
        val storePasswordVal = keystoreProperties.getProperty("storePassword")
            ?: System.getenv("KEYSTORE_PASSWORD")
        val keyAliasVal = keystoreProperties.getProperty("keyAlias")
            ?: System.getenv("KEY_ALIAS")
        val keyPasswordVal = keystoreProperties.getProperty("keyPassword")
            ?: System.getenv("KEY_PASSWORD")

        val keystoreFile = if (storeFilePath != null) file(storeFilePath) else null

        if (keystoreFile != null && keystoreFile.exists() && !storePasswordVal.isNullOrBlank()) {
            create("customRelease") {
                storeFile = keystoreFile
                storePassword = storePasswordVal
                keyAlias = keyAliasVal
                keyPassword = keyPasswordVal
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("customRelease")?.let {
                signingConfig = it
            }
        }
        debug {
            // Sign debug builds with custom release key when available to preserve data across upgrades
            signingConfigs.findByName("customRelease")?.let {
                signingConfig = it
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
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // AndroidX WebKit
    implementation("androidx.webkit:webkit:1.12.0")

    // AndroidX Security Crypto (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp (HTTP)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlinx Serialization JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")

    // QR Code Generation & Scanning (ZXing + CameraX)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
