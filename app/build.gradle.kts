import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Mirrors the iOS app's "no GoogleService-Info.plist → setup notice" behaviour:
// google-services.json is gitignored (CI restores it from a secret), and the
// build still succeeds without it — the app then shows a "Firebase not
// configured" screen instead of crashing.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

// Release signing comes from keystore.properties (local) or env vars (CI).
// Without either, release builds fall back to unsigned so CI still verifies them.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "DROKPO_UPLOAD_STORE_FILE")
val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).exists()

android {
    namespace = "app.drokpo.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.drokpo.android"
        minSdk = 26
        targetSdk = 36
        // CI overrides versionCode with run_number + offset (see
        // .github/workflows/release.yml); this value is for local builds only.
        versionCode = (project.findProperty("drokpo.versionCode") as String?)?.toInt() ?: 1
        // Keep in step with the iOS MARKETING_VERSION (drokpo-app/project.yml).
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"https://drokpo-backend.web.app\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://drokpo-backend.web.app/privacy.html\"")
        buildConfigField("boolean", "HAS_FIREBASE_CONFIG", hasGoogleServices.toString())
        // Sign in with Apple on Android uses Firebase's web OAuth flow, which
        // needs an Apple Services ID configured on the Firebase apple.com
        // provider. Flip to true once that's set up (see README).
        buildConfigField("boolean", "APPLE_SIGN_IN_ENABLED", "false")
    }

    signingConfigs {
        getByName("debug") {
            // Shared, committed debug key so every machine and CI produce
            // debug builds with the same SHA-1 — the one registered in Firebase
            // for Google sign-in. Not a secret (standard debug credentials).
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "DROKPO_UPLOAD_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DROKPO_UPLOAD_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DROKPO_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)
    // Not used directly. Play services / credentials request androidx.fragment 1.1.0 and 1.2.5
    // transitively (resolved to 1.5.7), and the activity library's
    // InvalidFragmentVersionForActivityResult lint check fails MainActivity's
    // registerForActivityResult on those requested versions. A direct >= 1.3.0 dependency
    // (the version already resolved, so nothing changes at runtime) satisfies it.
    implementation(libs.androidx.fragment)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.location)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
