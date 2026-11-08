plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.niklasweiblen"
// The release pipeline passes the version from the git tag: gradle ... -PpluginVersion=1.2.3
version = providers.gradleProperty("pluginVersion").getOrElse("1.0.1")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compile against the IntelliJ platform; Android Studio is built on the same platform,
        // so the plugin runs there as long as the platform build matches (see sinceBuild below).
        // To start a sandbox *Android Studio* with `./gradlew runIde`, replace the line with e.g.:
        //     androidStudio("2024.2.1.11")      // check the exact version on the JetBrains/Google download page
        intellijIdeaCommunity("2024.2.4")

        bundledPlugin("org.jetbrains.plugins.yaml")

        instrumentationTools()
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"            // 2024.2 / Android Studio Ladybug
            untilBuild = provider { null } // no upper bound
        }
    }

    // Optional plugin signing (https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).
    // Values come from environment variables, so nothing secret is stored in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Upload to JetBrains Marketplace (`gradle publishPlugin`), token from the environment.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    wrapper {
        gradleVersion = "8.10.2"
    }
}
