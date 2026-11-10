plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.bloodlines.hud"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.bloodlines.hud"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-hud-preview"
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
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }
}

dependencies {
    // The only dependency: no AndroidX, no Compose, no third-party runtime code.
    testImplementation("junit:junit:4.13.2")
}
