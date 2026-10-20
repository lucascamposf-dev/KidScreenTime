import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Lê a chave do Groq do local.properties (esse arquivo NÃO vai pro Git).
// Adicione nele a linha:  GROQ_API_KEY=gsk_xxxxxxxxxxxxxxxx
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val groqKey: String = localProps.getProperty("GROQ_API_KEY", "").trim()

android {
    namespace = "com.lucas.kidscreentime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lucas.kidscreentime"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"

        buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = false
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
