plugins {
    id("com.android.library") version "9.1.1" apply false
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

// So apps can depend on "dev.kitlangton.scrawl:scrawlkit" through includeBuild("…/scrawl/android").
subprojects {
    group = "dev.kitlangton.scrawl"
}
