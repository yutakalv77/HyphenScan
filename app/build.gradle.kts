plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
//    // ここのバージョンを 8.3.0 以上（例: 8.5.0 や 8.7.0など）にする
//    id("com.android.application") version "8.5.2" apply false
//    id("com.android.library") version "8.5.1" apply false
//    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}

android {
    namespace = "com.mia.hyphenscan"
    compileSdk {
        version = release(36)
    }

    packaging {
        jniLibs {useLegacyPackaging = true
        }
    }
    defaultConfig {
        applicationId = "com.mia.hyphenscan"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
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
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // CameraX
    implementation("androidx.camera:camera-camera2:1.5.2")
    implementation("androidx.camera:camera-lifecycle:1.5.2")
    implementation("androidx.camera:camera-view:1.5.2")
    // ML Kit OCR (最新のv2を使用)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}