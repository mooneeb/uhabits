pluginManagement {
    repositories {
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

// Kotlin 2.3 metadata requires R8 8.13.19; AGP 8.9 bundles an older compiler.
// https://developer.android.com/build/kotlin-support
buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:8.13.19") }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven(url = "https://plugins.gradle.org/m2/")
        maven(url = "https://oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://jitpack.io")
    }
}

include(":uhabits-android", ":uhabits-core", ":uhabits-web")
