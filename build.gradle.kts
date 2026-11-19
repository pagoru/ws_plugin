plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.mixeddeno"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

repositories {
    intellijPlatform { defaultRepositories() }
    mavenCentral()
}

kotlin { jvmToolchain(21) }

val cli: Configuration by configurations.creating

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion")) { useInstaller = false }
        bundledPlugin("JavaScript")
    }
    cli("com.google.code.gson:gson:2.11.0")
    cli("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

// Runs the generator without an IDE: ./gradlew generateTypes --args="/path/to/repo --configure"
tasks.register<JavaExec>("generateTypes") {
    group = "mixed deno types"
    description = "Generate tsconfig.deno.json files for a repository"
    classpath = sourceSets.main.get().output + cli
    mainClass.set("dev.mixeddeno.core.CliKt")
}
