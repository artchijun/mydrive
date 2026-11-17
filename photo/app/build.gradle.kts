import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서명 열쇠: photo/keystore.properties (저장소에 넣지 않음, Mydrive 휴대폰 앱과 같은 열쇠). 없으면 서명하지 않은 판만 만듦
val ks = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "kr.artchijun.myphoto"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.artchijun.myphoto"
        minSdk = 29          // 안드로이드 10 이상
        targetSdk = 35
        versionCode = 13
        versionName = "0.2.11"
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
            // 시험용: ./gradlew assembleRelease -PshrinkTest — 출시판 그대로를 디버그 열쇠로 서명 (기기에 깔린 디버그판 위에 덮어 깔아 로그인을 지우지 않고 확인)
            if (project.hasProperty("shrinkTest")) signingConfig = signingConfigs.getByName("debug")
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
    testImplementation("org.json:json:20240303")
}
