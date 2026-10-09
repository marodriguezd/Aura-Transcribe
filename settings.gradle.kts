pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AuraTranscribe"
include(":app")
include(":model_assets")
// Macrobenchmark + Baseline Profile generation. Kept out of `check`/`build`
// so a device-less CI run (unit tests + lint + assemble) is unaffected; it is
// invoked explicitly, e.g. `./gradlew :benchmark:connectedBenchmarkAndroidTest`.
include(":benchmark")
