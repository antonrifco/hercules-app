import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hercules.wear"
    compileSdk = 36

    defaultConfig {
        // Must equal the phone app's applicationId so the Wearable Data Layer connects them.
        applicationId = "com.hercules.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG gates the demo-data loader
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.health.services)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.coroutines.play.services)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work)
    implementation(libs.wear.ongoing)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.splashscreen)

    testImplementation(libs.junit)
}
