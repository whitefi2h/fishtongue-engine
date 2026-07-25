plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "lexurgy"
include("core", "cli", "api", "desktop-api")
