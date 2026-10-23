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

// Repositório do GitHub com as versões publicadas (ex.: "lucas/tempo-de-tela"). Vazio = sem atualização automática.
val updateRepo: String = localProps.getProperty("UPDATE_REPO", "").trim()

// Versão em version.properties (o publicar.ps1 aumenta a cada publicação).
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// Chave de assinatura FIXA: o Android só instala a atualização por cima se a chave for a mesma.
val keystoreFile = localProps.getProperty("KEYSTORE_FILE", "").trim()

android {
    namespace = "com.lucas.kidscreentime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lucas.kidscreentime"
        minSdk = 26
        targetSdk = 34
        versionCode = versionProps.getProperty("VERSION_CODE").toInt()
        versionName = versionProps.getProperty("VERSION_NAME")

        buildConfigField("String", "GROQ_API_KEY", "\"$groqKey\"")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (keystoreFile.isNotEmpty()) {
            create("app") {
                storeFile = rootProject.file(keystoreFile)
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = localProps.getProperty("KEY_ALIAS")
                keyPassword = localProps.getProperty("KEY_PASSWORD") ?: localProps.getProperty("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // Debug e release com a MESMA chave, para um poder instalar por cima do outro.
        val appSigning = signingConfigs.findByName("app")
        debug {
            if (appSigning != null) signingConfig = appSigning
        }
        release {
            isMinifyEnabled = false
            if (appSigning != null) signingConfig = appSigning
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

    testImplementation("junit:junit:4.13.2")
}
