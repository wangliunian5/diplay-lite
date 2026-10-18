// T3 底部死区遮蔽微应用（系统级，与 DiPlayLite 无关）：开机常驻 100px 黑条。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.shilapi.blackbar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shilapi.blackbar"
        minSdk = 17
        targetSdk = 19
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // targetSdk=19 触发 ExpiredTargetSdkVersion，主工程同款豁免
        checkReleaseBuilds = false
    }
}

dependencies {
}
