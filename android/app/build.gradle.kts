plugins {
    id("com.android.application")
}

// Ключ подписи хранится ВНЕ репозитория: Документы\XPhone-signing-key\xphone-release.jks.
// Пароли — в %USERPROFILE%\.gradle\gradle.properties (xphoneKeystorePassword, xphoneKeyAlias, xphoneKeyPassword).
val keystorePath = File(System.getProperty("user.home"), "Documents/XPhone-signing-key/xphone-release.jks")
    .takeIf { it.exists() && providers.gradleProperty("xphoneKeystorePassword").isPresent }?.path

android {
    namespace = "ru.xphone.booster"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.xphone.booster"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("xphoneKeystorePassword").get()
                keyAlias = providers.gradleProperty("xphoneKeyAlias").get()
                keyPassword = providers.gradleProperty("xphoneKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
