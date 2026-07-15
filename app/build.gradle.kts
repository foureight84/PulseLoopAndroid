import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pulseloop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pulseloop"
        minSdk = 26
        targetSdk = 35
        // versionCode/versionName are overridable from Gradle properties so the release CI
        // can drive them straight from the git tag (e.g. -PappVersionCode=5 -PappVersionName=1.0.0).
        // Local builds fall back to the literals below.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 13
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Repo the self-updater polls for new releases.
        buildConfigField("String", "GITHUB_REPO", "\"foureight84/PulseLoopAndroid\"")
    }

    buildFeatures {
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            // Signing credentials come from the environment (CI supplies the decoded
            // keystore + repo secrets) or from the untracked local.properties (local
            // release builds) — NEVER from committed literals: with self-update shipped,
            // this key is the app's whole trust chain. A release build without
            // credentials fails at :app:packageRelease with a keystore password error.
            val localProps = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            fun credential(env: String, property: String): String? =
                System.getenv(env)?.takeIf { it.isNotEmpty() } ?: localProps.getProperty(property)

            storeFile = file(credential("RELEASE_STORE_FILE", "releaseStoreFile") ?: "pulseloop-release.keystore")
            storePassword = credential("RELEASE_STORE_PASSWORD", "releaseStorePassword")
            // The alias is the key's name, not a secret.
            keyAlias = credential("RELEASE_KEY_ALIAS", "releaseKeyAlias") ?: "pulseloop"
            keyPassword = credential("RELEASE_KEY_PASSWORD", "releaseKeyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Distinct applicationId so a debug build installs ALONGSIDE the release app
            // (com.pulseloop.debug) instead of replacing it — which would wipe the release
            // app's data, since the two are signed with different keys.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    lint {
        checkReleaseBuilds = false
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
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Phase 1 only needs kotlin stdlib + serialization
    // Phase 2+ will add: Compose, Room, OkHttp

    // Phase 2: BLE, permissions
    implementation("androidx.core:core-ktx:1.15.0")

    // Phase 3: Room persistence
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Phase 4: Compose UI
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // Widgets: publish snapshots on app foreground/background transitions (iOS scene-phase parity).
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    // Home-screen widgets (iOS #44): Glance app widgets. 1.1.0 matches compose BOM 2024.06.00
    // (compose 1.6.x) and Kotlin 2.0.21.
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")

    // Phase 5: Coach — OkHttp for OpenAI API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Frosted-glass top/bottom bars (iOS .ultraThinMaterial parity): real backdrop blur
    // via RenderEffect on Android 12+, translucent scrim fallback on older devices.
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("dev.chrisbanes.haze:haze-materials:1.6.10")

    // Phase 6: Location for GPS route recording
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Phase 7: Polish — WorkManager, EncryptedSharedPreferences
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
