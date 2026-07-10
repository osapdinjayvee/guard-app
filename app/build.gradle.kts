import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Version lives in //version.properties and is bumped by `./gradlew release`.
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}

android {
    namespace = "com.example.guardapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.guardapp"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}