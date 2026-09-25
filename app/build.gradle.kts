plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.seba.einkauf"
    compileSdk = 34

    // Fester Schlüssel: neue Versionen lassen sich über die alte installieren
    signingConfigs {
        create("fixed") {
            storeFile = file("einkauf.keystore")
            storePassword = "android"
            keyAlias = "einkauf"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "de.seba.einkauf"
        minSdk = 28
        targetSdk = 34
        versionCode = 8
        versionName = "3.0"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
