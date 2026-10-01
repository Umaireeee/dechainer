plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.warleysr.urgejournal"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.warleysr.urgejournal"
        minSdk = 30
        targetSdk = 36
        // CI passes -PversionCode=<run number> (and -PversionNameSuffix) so every build installs
        // as an upgrade over the last; a plain local build keeps the defaults.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0" + ((project.findProperty("versionNameSuffix") as String?) ?: "")
    }

    buildTypes {
        release {
            // Shrinks the code to what is used; without it the APK carries every library class.
            isMinifyEnabled = true
            // Resources stay whole: labels are looked up by name at run time.
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The app is English only; drop the other languages' strings that libraries bring along.
        localeFilters += listOf("en")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
