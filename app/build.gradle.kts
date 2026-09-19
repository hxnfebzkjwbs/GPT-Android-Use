plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hxnfebzkjwbs.gptandroiduse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hxnfebzkjwbs.gptandroiduse"
        minSdk = 30
        targetSdk = 35
        versionCode = 90
        versionName = "0.15.5"
    }

    flavorDimensions += "controlMode"

    productFlavors {
        create("adb") {
            dimension = "controlMode"
            buildConfigField("boolean", "USE_ADB_BACKEND", "true")
            buildConfigField("boolean", "USE_ACCESSIBILITY_BACKEND", "false")
            resValue("string", "app_name", "GPT Android Use ADB")
        }

        create("accessibility") {
            dimension = "controlMode"
            applicationIdSuffix = ".accessibility"
            buildConfigField("boolean", "USE_ADB_BACKEND", "false")
            buildConfigField("boolean", "USE_ACCESSIBILITY_BACKEND", "true")
            resValue("string", "app_name", "GPT Android Use Accessibility")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
