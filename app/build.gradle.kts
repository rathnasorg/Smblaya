plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rathnas.smblay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rathnas.smblay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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
    defaultConfig {
        javaCompileOptions {
            annotationProcessorOptions {
                argument("lombok.config", "lombok.config")
            }
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    //implementation(libs.m3ExoPlayer)
    //implementation(libs.m3ExoUi)
    //implementation(libs.exoPlayer)
    //implementation(libs.libVlc)
    implementation(libs.lombok)
    annotationProcessor(libs.lombok)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)
    implementation(libs.androidx.security)
    implementation(libs.jcifs.ng)
}