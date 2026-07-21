import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Version lives in //version.properties and is bumped by `./gradlew release`.
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}

/**
 * Release signing credentials, kept out of the repository.
 *
 * Create `keystore.properties` at the project root (it is gitignored) with:
 *
 *     storeFile=/absolute/path/to/guardapp-release.jks
 *     storePassword=...
 *     keyAlias=guardapp
 *     keyPassword=...
 *
 * Absent, the release build still assembles — it is simply unsigned, which is what CI and a
 * `-PdryRun` want. It cannot be installed on a device until it is signed.
 */
val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.exists() }?.let {
    Properties().apply { it.inputStream().use(::load) }
}

android {
    namespace = "com.minsu.guardapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.minsu.guardapp"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Maps reads its key from a manifest meta-data. Sourced from the MAPS_API_KEY Gradle
        // property (put it in a gitignored gradle.properties or ~/.gradle/gradle.properties) so the
        // key is never committed. Absent, the map tiles render blank but the app still builds and runs.
        manifestPlaceholders["MAPS_API_KEY"] = (project.findProperty("MAPS_API_KEY") as String?).orEmpty()
    }

    signingConfigs {
        // Only registered when keystore.properties is present, so a clone without the credentials
        // still builds rather than failing at configuration time.
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Production. It has a real Let's Encrypt certificate, so nothing special is needed to
            // reach it — LocalBackendModule's DNS and certificate overrides switch themselves off
            // for any host that is not `.test`.
            buildConfigField("String", "API_BASE_URL", "\"https://guard.minsu.edu.ph/api/\"")
            // To develop against the local WSL backend instead, swap the line above for
            //     https://guard.test/api/
            // and first run:  adb reverse tcp:443 tcp:443
            // That tunnels the handset's :443 to the host's loopback, where WSL already forwards
            // nginx. It is the one setting that works on an emulator and a physical phone alike:
            // 10.0.2.2 is emulator-only, and the host's LAN address does not work at all, because
            // WSL forwards loopback but not the LAN interface. The tunnel does not survive a
            // reconnect — re-run it after replugging.
            buildConfigField("String", "BACKEND_HOST", "\"127.0.0.1\"")
            // Flip to true to work against canned responses with no backend at all. The mock
            // interceptor only exists in the debug source set, so a release build cannot serve
            // fake data even by accident.
            buildConfigField("boolean", "USE_MOCK_API", "false")
        }
        release {
            // R8: shrink, optimise, obfuscate. See proguard-rules.pro — several of this app's own
            // names are load-bearing at runtime (persisted enum constants, reflectively-located
            // Moshi adapters, the worker class name in WorkManager's database) and R8 would rename
            // them silently.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "API_BASE_URL", "\"https://guard.minsu.edu.ph/api/\"")

            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // MigrationTestHelper reads the exported schemas at runtime, from the test APK's assets — so
    // the checked-in JSON has to be packaged into it. Without this, a migration test cannot run at
    // all, which on a database holding unsynced attendance is not a gap worth having.
    sourceSets.getByName("androidTest") {
        assets.srcDirs("$projectDir/schemas")
    }
}

// Room schemas are checked in so migrations can be diffed and tested (T-11).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}