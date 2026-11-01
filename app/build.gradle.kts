import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionCode = 6
        versionName = "1.3.0"
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

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        // Sideloaded app: don't fail release builds on Play Store target-SDK policy checks.
        checkReleaseBuilds = false
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
