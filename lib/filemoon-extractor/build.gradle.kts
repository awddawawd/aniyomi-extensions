plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlinx-serialization")
}

dependencies {
    // Core Aniyomi dependencies (provided by the app)
    compileOnly("eu.kanade.tachiyomi:extensions-lib:14.0.0")
    compileOnly("eu.kanade.tachiyomi.lib:playlist-utils:1.0.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Testing (optional)
    testImplementation("junit:junit:4.13.2")
}