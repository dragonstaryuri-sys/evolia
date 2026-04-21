pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.itextsupport.com/android")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.objectbox") {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()

        // JitPack (虽然慢，但有时必须)
        maven("https://jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
    }
}

rootProject.name = "Evolia"
include(":app")
include(":highlight")
include(":ai")
include(":search")
include(":tts")
include(":common")
include(":app:baselineprofile")
include(":document")
include(":discover")
include(":core-data")
