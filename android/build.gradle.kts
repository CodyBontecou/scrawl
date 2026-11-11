plugins {
    id("com.android.library") version "9.1.1" apply false
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

// Apps depend on "dev.kitlangton.scrawl:<module>" through includeBuild("…/scrawl/android"), or on
// the published artifacts (`./gradlew publishToMavenLocal` puts them in ~/.m2).
subprojects {
    group = "dev.kitlangton.scrawl"
    version = "0.1.0"
}

val publishedModules = setOf("scrawlkit", "scrawlkit-noop", "scrawlkit-autoinstall")
subprojects {
    if (name !in publishedModules) return@subprojects
    apply(plugin = "maven-publish")
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            publishing { singleVariant("release") }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension>("publishing") {
                publications.create<MavenPublication>("release") {
                    from(components["release"])
                    artifactId = project.name
                    pom {
                        name.set(project.name)
                        url.set("https://github.com/kitlangton/scrawl")
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/licenses/MIT")
                            }
                        }
                    }
                }
            }
        }
    }
}
