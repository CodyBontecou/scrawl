plugins {
    id("com.android.library")
}

android {
    namespace = "dev.kitlangton.scrawl.autoinstall"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // Apps compiling against SDK 34+ can use it (AGP 9 would otherwise require our compileSdk).
        aarMetadata { minCompileSdk = 34 }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":scrawlkit"))
}

kotlin {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
    coreLibrariesVersion = "2.0.21"
}
