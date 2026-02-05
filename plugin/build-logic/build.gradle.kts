plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(8)
    }
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("") {
            id = "org.jlleitschuh.gradle.ktlint.local.publish"
            implementationClass = "org.jlleitschuh.gradle.ktlint.MavenLocalPublishingPlugin"
        }
    }
}

repositories {
    mavenCentral()
}
