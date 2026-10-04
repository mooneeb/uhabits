plugins {
    kotlin("multiplatform")
    alias(libs.plugins.ktlint.plugin)
}

tasks.register<Copy>("prepareDriveGate") {
    dependsOn("jsBrowserDevelopmentWebpack", "jsProcessResources")
    from(layout.buildDirectory.dir("kotlin-webpack/js/developmentExecutable"))
    from(layout.buildDirectory.dir("processedResources/js/main"))
    into(layout.buildDirectory.dir("drive-gate"))
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "loop-core.js"
            }
        }
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":uhabits-core"))
        }
    }
}
