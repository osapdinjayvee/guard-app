// Imported rather than fully qualified: in a Kotlin build script `java` resolves to the Java
// plugin's extension, so `java.security.MessageDigest` does not name the package at all.
import java.security.MessageDigest
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
 * Where the app looks to find out whether a newer build exists.
 *
 * This app is sideloaded, not shipped through Play, so nothing tells a guard that an update
 * is out — it has to ask. The manifest is a small JSON file uploaded beside the APK by
 * `./gradlew packageUpdate`, and it carries the APK's own URL, so this is the only address
 * the app needs to know. Moving distribution elsewhere (GitHub Releases, say) is a change to
 * this one line; nothing in the app cares where the manifest is served from.
 *
 * Must be HTTPS: the app sets `usesCleartextTraffic="false"`, so plain HTTP is refused.
 */
val UPDATE_MANIFEST_URL = "https://minsu.edu.ph/app/guard-version.json"

/** How many changelog bullets reach the update sheet before the rest become a count. */
val MAX_RELEASE_NOTE_LINES = 8

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
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$UPDATE_MANIFEST_URL\"")
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
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$UPDATE_MANIFEST_URL\"")

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
        kotlin.srcDirs("$projectDir/src/sharedTest/java")
    }

    // Test doubles both suites need. One copy, because a fake that drifts between the unit and
    // instrumented versions is a fake that is lying to one of them — and the lie surfaces as a
    // test that passes against a stub the production code no longer matches.
    sourceSets.getByName("test") {
        kotlin.srcDirs("$projectDir/src/sharedTest/java")
    }
}

// Room schemas are checked in so migrations can be diffed and tested (T-11).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * Assembles everything that has to be uploaded for a release, into one directory.
 *
 * The manifest is *generated* rather than written by hand, and that is the entire point. The two
 * files are uploaded manually, and a hand-maintained `guard-version.json` drifts: someone bumps
 * the version, forgets the JSON, and every handset is told it is up to date — or worse, the JSON
 * advertises a versionCode the APK does not carry and the update installs and then offers itself
 * again forever. Both come out of this task, from the same `version.properties` and the same
 * bytes, so they cannot disagree.
 */
tasks.register("packageUpdate") {
    group = "release"
    description = "Builds the signed release APK and the update manifest that describes it."
    dependsOn("assembleRelease")

    val apkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val outputDirectory = layout.buildDirectory.dir("distribution")
    val versionName = appVersion.getProperty("versionName")
    val versionCode = appVersion.getProperty("versionCode").toInt()
    val minSupported = appVersion.getProperty("minSupportedVersionCode")?.toInt() ?: 1
    val signed = keystoreProperties != null
    val changelog = rootProject.file("CHANGELOG.md")
    val manifestUrl = UPDATE_MANIFEST_URL

    outputs.dir(outputDirectory)

    doLast {
        // An unsigned APK is not a lesser artefact, it is an unusable one: Android refuses to
        // install it at all. Caught here rather than on a handset, because the only way to
        // discover it there is to hand a guard a file that does nothing.
        if (!signed) {
            throw GradleException(
                "Release APK is unsigned — keystore.properties is missing. An unsigned APK " +
                    "cannot be installed, and an update signed with a different key than the " +
                    "installed app cannot replace it. See the signing notes in app/build.gradle.kts."
            )
        }

        val apk = apkFile.get().asFile
        if (!apk.isFile) throw GradleException("Expected a release APK at $apk")

        val target = outputDirectory.get().asFile
        target.mkdirs()

        val publishedApk = File(target, "guard.apk")
        apk.copyTo(publishedApk, overwrite = true)

        val sha256 = MessageDigest.getInstance("SHA-256").let { digest ->
            publishedApk.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        // The newest changelog section, rewritten for the person who will read it.
        //
        // Not the raw section: that is written for developers, and the first release's runs to
        // four kilobytes of every commit ever made. What lands in the update sheet is a short
        // list of what changed — no commit hashes, no section headings, no scope prefixes, and a
        // hard cap, because a guard deciding whether to spend their data on a download is not
        // going to read forty bullet points.
        val notes = changelog.takeIf(File::isFile)?.readText()?.let { text ->
            val start = text.indexOf("## [")
            if (start < 0) return@let null

            val entries = text.substring(start).lineSequence()
                .drop(1)
                .takeWhile { !it.startsWith("## [") }
                .filter { it.startsWith("- ") }
                .map { line ->
                    line.replace(Regex(""" \([0-9a-f]{7,40}\)$"""), "") // trailing commit hash
                        .replace(Regex("""^- \*\*[^*]+:\*\* """), "- ") // **scope:** prefix
                        .trim()
                }
                .filter { it.length > 2 }
                .toList()

            val shown = entries.take(MAX_RELEASE_NOTE_LINES)
            val remainder = entries.size - shown.size
            buildList {
                addAll(shown)
                if (remainder > 0) add("- …and $remainder more change${if (remainder == 1) "" else "s"}")
            }.joinToString("\n").takeIf(String::isNotEmpty)
        }

        // Hand-rolled rather than via a JSON library: this file is three scalars and a string,
        // and the build script has no serialiser on its classpath.
        fun quote(value: String) = '"' + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t") + '"'

        File(target, "guard-version.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"version_code\": $versionCode,")
                appendLine("  \"version_name\": ${quote(versionName)},")
                appendLine("  \"apk_url\": ${quote(manifestUrl.substringBeforeLast('/') + "/guard.apk")},")
                appendLine("  \"sha256\": ${quote(sha256)},")
                appendLine("  \"min_supported_version_code\": $minSupported,")
                appendLine("  \"size_bytes\": ${publishedApk.length()},")
                appendLine("  \"release_notes\": ${quote(notes.orEmpty())}")
                append("}")
            } + "\n"
        )

        logger.lifecycle("")
        logger.lifecycle("  Version:     $versionName (versionCode $versionCode)")
        logger.lifecycle("  Floor:       minSupportedVersionCode $minSupported")
        logger.lifecycle("  APK:         ${publishedApk.length() / 1_048_576.0} MB, sha256 $sha256")
        logger.lifecycle("  Output:      $target")
        logger.lifecycle("")
        logger.lifecycle("  Upload both files to ${manifestUrl.substringBeforeLast('/')}/")
        logger.lifecycle("  Upload guard.apk FIRST — a manifest pointing at a file that is not")
        logger.lifecycle("  there yet tells every handset to download a 404.")
    }
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