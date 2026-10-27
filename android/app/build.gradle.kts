import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// google-services.json is a per-developer Firebase file and is never committed. Without it the app still
// builds; push simply stays off (see FirebaseTokenProvider).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(name: String, default: String): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: default)

android {
    namespace = "dev.agenthub.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.agenthub.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // No secrets here: the Google *Web client ID* is a public identifier, supplied locally.
        // Verified App Link host for the OAuth return (architecture 1.3). The domain is still undecided,
        // so this placeholder never verifies until a real host is set in local.properties.
        manifestPlaceholders["appLinkHost"] = localProp("APP_LINK_HOST", "agenthub.invalid")
        buildConfigField("String", "APP_LINK_HOST", "\"${localProp("APP_LINK_HOST", "agenthub.invalid")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProp("GOOGLE_WEB_CLIENT_ID", "")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${localProp("API_BASE_URL", "http://10.0.2.2:8000/")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release must be HTTPS; the build fails otherwise.
            val url = localProp("API_BASE_URL", "https://api.example.invalid/")
            require(url.startsWith("https://")) { "Release API_BASE_URL must use https://" }
            buildConfigField("String", "API_BASE_URL", "\"$url\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.id)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}
