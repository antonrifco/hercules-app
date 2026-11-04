import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hercules.mobile"
    compileSdk = 36

    defaultConfig {
        // Must equal the watch app's applicationId so the Wearable Data Layer connects them.
        applicationId = "com.hercules.app"
        minSdk = 28
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.offline", "true"); it.systemProperty("robolectric.dependency.dir", "/tmp/robo") }
        }
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

    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    // Optional JVM screenshots of the Compose UI (-Phercules.screenshots=true). Off by default.
    if (providers.gradleProperty("hercules.screenshots").isPresent) {
        testImplementation("org.robolectric:robolectric:4.14.1")
        testImplementation(platform(libs.compose.bom))
        testImplementation("androidx.compose.ui:ui-test-junit4")
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
    implementation(libs.androidx.work)
    implementation(libs.health.connect)
}
