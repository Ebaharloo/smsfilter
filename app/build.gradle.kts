plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ir.smsfilter"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "ir.smsfilter"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.4"
    }

    // single source of truth: the same rules.json the Python mirror reads
    sourceSets["main"].assets.srcDirs("src/main/assets", "../rules")

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }

    buildTypes {
        // A debug APK runs Compose with no R8 and no optimisation - that is where the scroll jank
        // came from. The release variant is the one to install for daily use; it is signed with the
        // debug key because this app is installed by hand, never through Play.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
