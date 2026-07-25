plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.8.21"
    id("org.cyclonedx.bom") version "1.8.2"
}

repositories {
    mavenCentral()
}

group = "com.fishtongue"
version = "1.7.6-fishtongue.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
}
