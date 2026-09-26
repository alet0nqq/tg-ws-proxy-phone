pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tg-ws-proxy-phone"

include(":core")
// `-PcoreOnly` builds just the platform-independent proxy (no Android SDK needed).
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
