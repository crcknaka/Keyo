import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Read the Groq API key from local.properties (gitignored) or the GROQ_API_KEY env var.
// Never commit the real key to source control.
val groqApiKey: String = run {
    val props = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { props.load(it) }
    }
    props.getProperty("GROQ_API_KEY") ?: System.getenv("GROQ_API_KEY") ?: ""
}

// Release signing material lives in keystore.properties (gitignored) next to the keystore itself.
// Without the file the release build is simply unsigned — debug builds are unaffected.
val keystoreProps: Properties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.keyo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.keyo"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.9.8"

        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystoreProps.isEmpty) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Human-friendly APK names: Keyo.apk for release, Keyo-debug.apk for debug.
    applicationVariants.all {
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                if (buildType.name == "release") "Keyo.apk" else "Keyo-${buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    // Held back on purpose: core-ktx 1.19 and Compose BOM 2026.08 (ui 1.12) need compileSdk 37 /
    // AGP 9.1. Everything below is the newest that builds on AGP 8.11 / compileSdk 36. The
    // lifecycle/savedstate lines state the versions Compose already pulls in transitively — the
    // older numbers that used to be here pinned nothing.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // org.json is part of Android. This artifact exists ONLY so the JVM unit tests can run the
    // code that uses JSONArray/JSONObject; as `implementation` it was bundled into the APK, where
    // R8 renamed it and the release ran a different JSON implementation from debug.
    testImplementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}
