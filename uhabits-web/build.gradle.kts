import java.security.MessageDigest

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.ktlint.plugin)
}

tasks.register<Copy>("prepareDriveGate") {
    dependsOn("jsBrowserDevelopmentWebpack", "jsProcessResources")
    from(layout.buildDirectory.dir("kotlin-webpack/js/developmentExecutable"))
    from(layout.buildDirectory.dir("processedResources/js/main"))
    from("../uhabits-core/assets/main") { include("migrations/**"); into("app") }
    from("../build/js/node_modules/sql.js/dist/sql-wasm.wasm") { into("app") }
    into(layout.buildDirectory.dir("drive-gate"))
    doLast {
        val output = layout.buildDirectory.dir("drive-gate").get().asFile
        // A temporary session launches outside the owned service-worker scope,
        // so an older cached owned shell cannot open durable habit storage.
        output.resolve("session").mkdirs()
        output.resolve("session/index.html").writeText(
            output.resolve("app/index.html").readText()
                .replace("href=\"icon-", "href=\"../app/icon-")
                .replace("href=\"app.css\"", "href=\"../app/app.css\"")
                .replace("href=\"manifest.webmanifest\"", "href=\"../app/manifest.webmanifest\"")
                .replace("src=\"icon-", "src=\"../app/icon-")
                .replace("src=\"app.mjs\"", "src=\"../app/app.mjs\"")
        )
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
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
