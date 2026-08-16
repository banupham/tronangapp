plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.banupham.tronangapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.banupham.tronangapp"
        minSdk = 29
        targetSdk = 35
        versionCode = 23
        versionName = "0.19.0"
    }

    val ciKeyStoreFile = providers.environmentVariable("TRONANGAPP_KEYSTORE_FILE").orNull
    if (!ciKeyStoreFile.isNullOrBlank()) {
        signingConfigs {
            create("persistent") {
                storeFile = file(ciKeyStoreFile)
                storePassword = providers.environmentVariable("TRONANGAPP_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TRONANGAPP_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TRONANGAPP_KEY_PASSWORD").get()
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("persistent")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("persistent")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
