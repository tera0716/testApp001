plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.testapp001"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.testapp001"
        minSdk = 24
        targetSdk = 36
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
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // ▼▼▼ここからバーコードリーダーのために追加▼▼▼

    // ML Kit Barcode Scanning
    // バーコードの検出と解析を行います。
    implementation(libs.mlkit.barcodeScanning)
    // 注意: バージョン番号は最新の安定版を確認し、適宜更新してください。
    // https://developers.google.com/ml-kit/vision/barcode-scanning/android#dependencies

    // CameraX
    // カメラの操作を簡単にするためのJetpackライブラリです。
    // https://developer.android.com/jetpack/androidx/releases/camera
// CameraX
    // バンドルを使用する場合: implementation(libs.bundles.camerax)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.google.guava)
    // ▲▲▲ここまでバーコードリーダーのために追加▲▲▲
}