plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val vName = (project.findProperty("versionName") as String?) ?: "1.0.0"
val vCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "id.sengkuni.aphodius"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.sengkuni.aphodius"
        minSdk = 26
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/aphodius.jks")
            storePassword = "aphodius-release"
            keyAlias = "aphodius"
            keyPassword = "aphodius-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.biometric:biometric:1.1.0")
}
