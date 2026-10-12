import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.animetv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.animetv"
        minSdk = 23          // Android TV 6.0+
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Llave de depuración fija dentro del repo: cada APK que compile GitHub Actions
    // queda firmado igual, así la TV acepta actualizaciones sin desinstalar.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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

    buildFeatures {
        buildConfig = true   // usado para activar la depuración del WebView en debug
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val media3 = "1.9.0"

dependencies {
    // AndroidX base
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // UI para TV (BrowseSupportFragment, VerticalGridSupportFragment, ImageCardView)
    implementation("androidx.leanback:leanback:1.0.0")

    // Reproductor Media3 (ExoPlayer + HLS + UI)
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // Carga de imágenes de las tarjetas
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
