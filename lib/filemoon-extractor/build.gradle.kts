plugins {
    id("com.android.library")
    kotlin("android")
    // Corrected the serialization plugin ID for Kotlin Gradle DSL
    kotlin("plugin.serialization") 
}

android {
    namespace = "eu.kanade.tachiyomi.lib.filemoonextractor"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 1. Corrected the version from 14.0.0 to 1.4
    compileOnly("eu.kanade.tachiyomi:extensions-lib:1.4") 

    // 2. Changed to a local project dependency instead of a remote JitPack fetch
    implementation(project(":lib:playlist-utils"))

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Testing (optional)
    testImplementation("junit:junit:4.13.2")
}