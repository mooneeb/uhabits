import java.security.MessageDigest

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.ktlint.plugin)
}

tasks.register<Copy>("prepareDriveGate") {
    dependsOn("jsBrowserDevelopmentWebpack", "jsProcessResources")
    from(layout.buildDirectory.dir("kotlin-webpack/js/developmentExecutable"))
    from(layout.buildDirectory.dir("processedResources/js/main"))
    into(layout.buildDirectory.dir("drive-gate"))
    doLast {
        val output = layout.buildDirectory.dir("drive-gate").get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        output.walkTopDown().filter { it.isFile && it.name != "sw.js" }
            .sortedBy { it.relativeTo(output).path }.forEach { digest.update(it.readBytes()) }
        val version = digest.digest().take(12).joinToString("") { "%02x".format(it) }
        val worker = output.resolve("app/sw.js")
        worker.writeText(worker.readText().replace("__SHELL_VERSION__", version))
    }
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
