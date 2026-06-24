import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.compose.screenshot")
}

android {
    namespace = "com.example.phoneins"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.phoneins"
        minSdk = 33
        targetSdk = 36
        versionCode = 8
        versionName = "0.3.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "SIMULATOR_AVAILABLE", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "SIMULATOR_AVAILABLE", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
        managedDevices {
            allDevices {
                create<ManagedVirtualDevice>("mediumPhoneApi36") {
                    device = "Medium Phone"
                    apiLevel = 36
                    systemImageSource = "google"
                }
            }
        }
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":navigation"))
    implementation(project(":protocol"))

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha15")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
