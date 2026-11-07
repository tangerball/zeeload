plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.zeeload.carappcenter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zeeload.carappcenter"
        minSdk = 28
        targetSdk = 35
        versionCode = 30
        versionName = "1.24.0-hd"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名参数：优先 keystore.properties（本机文件，不入库），其次 -P 参数
    val keystoreProps = mutableMapOf<String, String>()
    rootProject.file("keystore.properties").takeIf { it.exists() }?.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
            keystoreProps[trimmed.substringBefore("=").trim()] = trimmed.substringAfter("=").trim()
        }
    }
    fun signingValue(key: String, gradleProp: String): String? =
        keystoreProps[key] ?: providers.gradleProperty(gradleProp).orNull

    signingConfigs {
        val store = signingValue("storeFile", "zeeloadKeystore")
        if (store != null) {
            create("release") {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "zeeloadStorePassword")
                keyAlias = signingValue("keyAlias", "zeeloadKeyAlias")
                keyPassword = signingValue("keyPassword", "zeeloadKeyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
