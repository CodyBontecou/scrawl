plugins {
    id("com.android.library")
    // Compose modifiers (`Modifier.scrawlScreen` / `scrawlContext`); Compose itself is compileOnly.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kitlangton.scrawl"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // Apps compiling against SDK 34+ can use it (AGP 9 would otherwise require our compileSdk).
        aarMetadata { minCompileSdk = 34 }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(platform("androidx.compose:compose-bom:2026.08.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.runtime:runtime")
}

kotlin {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
    coreLibrariesVersion = "2.0.21"
}
