import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// iOS + watchOS targets need Xcode. Turn on with -Phercules.apple=true (or in gradle.properties).
val appleEnabled = providers.gradleProperty("hercules.apple").orNull == "true"

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    if (appleEnabled) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
            watchosArm64(),          // Apple Watch devices (arm64_32)
            watchosSimulatorArm64(), // Apple Silicon watch simulator
        ).forEach { target ->
            target.binaries.framework {
                baseName = "HerculesShared"
                isStatic = true
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
        }
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            api(libs.datetime)
            api(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android)
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.sqldelight.jvm)
                implementation(libs.junit)
            }
        }
        if (appleEnabled) {
            appleMain.dependencies {
                implementation(libs.sqldelight.native)
            }
        }
    }
}

android {
    namespace = "com.hercules.shared"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("HerculesDatabase") {
            packageName.set("com.hercules.shared.db")
        }
    }
}
