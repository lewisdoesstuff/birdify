plugins {
    id("com.android.application")
}

android {
    namespace = "ooo.lew.birdify.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "ooo.lew.birdify.overlay"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
