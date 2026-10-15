plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.gccammelli.meowstache"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.gccammelli.meowstache"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
    }

    buildTypes {
        release {
            // R8: shrink and optimise code and resources (smaller APK; F-Droid asks for it).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    // Release signing only when CI provides the key (GitHub Releases); F-Droid and local builds stay unsigned.
    val ks = System.getenv("MEOWSTACHE_KEYSTORE")
    if (ks != null) {
        signingConfigs.create("release") {
            storeFile = file(ks)
            storePassword = System.getenv("MEOWSTACHE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MEOWSTACHE_KEY_ALIAS")
            keyPassword = System.getenv("MEOWSTACHE_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    // F-Droid: no encrypted Google dependency-metadata blob in the APK.
    dependenciesInfo { includeInApk = false; includeInBundle = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)

}
