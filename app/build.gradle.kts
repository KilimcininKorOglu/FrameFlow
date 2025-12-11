plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.keremgok.frameflow"
    compileSdk = 35

    buildFeatures { 
        buildConfig = true 
        compose = true
    }

    defaultConfig {
        applicationId = "com.keremgok.frameflow"
        minSdk = 31  // Required for Meta DAT SDK
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    packaging { 
        resources { 
            excludes += "/META-INF/{AL2.0,LGPL2.1}" 
        } 
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    
    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    
    // DataStore for preferences (stream key storage)
    implementation(libs.androidx.datastore.preferences)

    // Security Crypto for encrypted storage
    implementation(libs.androidx.security.crypto)
    
    // EXIF for photo handling
    implementation(libs.androidx.exifinterface)
    
    // Meta Wearables DAT SDK
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.mockdevice)
    
    // RootEncoder for RTMP streaming
    implementation(libs.rootencoder)
    
    // Kotlin collections
    implementation(libs.kotlinx.collections.immutable)
    
    // Debug
    debugImplementation(libs.androidx.ui.tooling)
}
