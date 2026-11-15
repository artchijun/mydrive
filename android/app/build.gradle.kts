import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서명 열쇠: android/keystore.properties (저장소에 넣지 않음). 없으면 서명하지 않은 판만 만듦
val ks = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "kr.artchijun.mydrive"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.artchijun.mydrive"
        minSdk = 29          // 안드로이드 10 이상 (다운로드 폴더에 바로 저장)
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
    }
    signingConfigs {
        if (ks.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // 안드로이드에 들어 있는 org.json 을 PC 시험에서도
}
