plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing values come from environment variables (set by the GitHub workflow from repository secrets).
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val hasKeystore = keystorePath != null && File(keystorePath).exists()

android {
    namespace = "com.majid.callrecorder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.majid.callrecorder"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
