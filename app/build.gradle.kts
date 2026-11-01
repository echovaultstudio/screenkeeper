plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release key, supplied by CI secrets or your shell. Without it the APK is signed with the debug key.
val releaseKeystore: String? = System.getenv("SK_KEYSTORE")

android {
    namespace = "app.screenkeeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.screenkeeper"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SK_KEY_ALIAS")
                keyPassword = System.getenv("SK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
