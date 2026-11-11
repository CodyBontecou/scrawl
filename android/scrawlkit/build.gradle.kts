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
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = false
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.customview:customview:1.1.0")
    // Touch labels in Compose screens read Compose's semantics; never bundled, used only when the
    // app itself has Compose.
    compileOnly(platform("androidx.compose:compose-bom:2026.08.00"))
    compileOnly("androidx.compose.ui:ui")
    compileOnly("androidx.compose.runtime:runtime")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    // The Compose compiler plugin runs on the unit-test sources too and needs the runtime there.
    testImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    testImplementation("androidx.compose.runtime:runtime")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui")
    androidTestImplementation("androidx.compose.foundation:foundation")
    androidTestImplementation("androidx.activity:activity-compose:1.13.0")
    // android.jar only has stubs of org.json; the real one for JVM tests.
    testImplementation("org.json:json:20250517")
}


// Apps fill in `scrawlScheme` (their setup-link scheme); the library's device tests need one too.
androidComponents {
    onVariants { variant ->
        (variant as? com.android.build.api.variant.HasDeviceTests)?.deviceTests?.values?.forEach {
            it.manifestPlaceholders.put("scrawlScheme", "scrawltest")
        }
    }
}
