pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "cashout-fraud-guard"

include("core", "world", "eval", "service")
