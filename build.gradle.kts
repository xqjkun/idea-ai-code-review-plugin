plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.medcompany"
version = "0.5.2"

val ideaHome = providers.gradleProperty("ideaHome")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (ideaHome.isPresent) {
            local(ideaHome)
        } else {
            intellijIdeaCommunity("2024.1.7")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test-junit5"))
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellijPlatform {
    buildSearchableOptions = false

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }

    pluginConfiguration {
        name = "AI Code Review Gate"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "241"
            untilBuild = "262.*"
        }

        vendor {
            name = "suixq"
            email = "304589624@qq.com"
        }
    }

    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7.1")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7.2")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.3")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2025.3.6.1")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.1.3")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.2.1")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
