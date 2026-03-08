rootProject.name = "kotlin-lang-mu"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.10"
        id("org.jetbrains.kotlin.js") version "2.3.10"
        id("org.jetbrains.kotlin.multiplatform") version "2.3.10"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
        id("com.android.application") version "8.13.2"
        id("com.android.library") version "8.13.2"
        id("com.android.kotlin.multiplatform.library") version "8.13.2"
        id("org.jetbrains.compose") version "1.9.1"
        id("com.gradleup.shadow") version "8.3.0"
        id("org.jetbrains.dokka") version "2.0.0"
        id("org.jetbrains.kotlinx.kover") version "0.9.3"
        id("org.jetbrains.intellij") version "1.17.2"
        id("io.papermc.paperweight.userdev") version "1.7.2"
        id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
    }
}

include(":kotlin-math-rational")
project(":kotlin-math-rational").projectDir = file("../kotlin-math-rational")
include(":kotlin-data")
project(":kotlin-data").projectDir = file("../kotlin-data")
include(":kotlin-extra-serializers")
project(":kotlin-extra-serializers").projectDir = file("../kotlin-extra-serializers")
include(":kotlin-parsing-charinput")
project(":kotlin-parsing-charinput").projectDir = file("../kotlin-parsing-charinput")
include(":kotlin-levenshtein")
project(":kotlin-levenshtein").projectDir = file("../kotlin-levenshtein")
include(":kotlin-random-gen")
project(":kotlin-random-gen").projectDir = file("../kotlin-random-gen")
include(":kotlin-data-need")
project(":kotlin-data-need").projectDir = file("../kotlin-data-need")
include(":kotlin-base58")
project(":kotlin-base58").projectDir = file("../kotlin-base58")
