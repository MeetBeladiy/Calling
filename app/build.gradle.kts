plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kapt)
}

// Local builds work without Firebase credentials. Once app/google-services.json
// is added, the official plugin automatically configures Firebase Messaging.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.drivercall"
    compileSdk = 36

    val apiBaseUrl = providers.gradleProperty("CALLING_APP_BASE_URL")
        .orElse("http://10.0.2.2:8080/")
    val signalingUrl = providers.gradleProperty("CALLING_APP_SIGNALING_URL")
        .orElse("ws://10.0.2.2:8080/ws")
    val turnUrl = providers.gradleProperty("CALLING_APP_TURN_URL")
        .orElse("")
    val turnUsername = providers.gradleProperty("CALLING_APP_TURN_USERNAME")
        .orElse("")
    val turnCredential = providers.gradleProperty("CALLING_APP_TURN_CREDENTIAL")
        .orElse("")

    defaultConfig {
        applicationId = "com.drivercall"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.8.2-mvp"

        buildConfigField("String", "BASE_URL", "\"${apiBaseUrl.get()}\"")
        buildConfigField("String", "SIGNALING_URL", "\"${signalingUrl.get()}\"")
        buildConfigField("String", "TURN_URL", "\"${turnUrl.get()}\"")
        buildConfigField("String", "TURN_USERNAME", "\"${turnUsername.get()}\"")
        buildConfigField("String", "TURN_CREDENTIAL", "\"${turnCredential.get()}\"")
        manifestPlaceholders["usesCleartextTraffic"] = "true"
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.security.crypto)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.service)
    implementation(libs.core.telecom)
    implementation(libs.webrtc)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
