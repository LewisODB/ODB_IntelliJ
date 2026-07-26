import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.gradle.api.tasks.bundling.Zip

abstract class WriteRuntimeManifest : DefaultTask() {
    @get:InputFile
    abstract val runtimeJar: RegularFileProperty

    @get:OutputFile
    abstract val outputManifest: RegularFileProperty

    @TaskAction
    fun writeManifest() {
        val runtime = runtimeJar.get().asFile.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(runtime).joinToString("") { "%02x".format(it) }
        val manifest =
            """{"artifact":"odb-runtime.jar","sha256":"$digest","javaClassVersion":52,"integrationProtocol":1}"""
        outputManifest.get().asFile.apply {
            parentFile.mkdirs()
            writeText(manifest)
        }
    }
}

abstract class VerifyProbeIsolation : DefaultTask() {
    @get:InputFile
    abstract val pluginZip: RegularFileProperty

    @TaskAction
    fun verify() {
        scanArchive(pluginZip.get().asFile.readBytes(), pluginZip.get().asFile.name)
    }

    private fun scanArchive(bytes: ByteArray, label: String) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.lowercase()
                val entryBytes = archive.readAllBytes()
                if (
                    "odb-test" in name ||
                    "src/test" in name ||
                    "org/lewisodb/fixture" in name ||
                    entryBytes.containsAscii("--odb-probe-mode=") ||
                    entryBytes.containsAscii("fixture-stderr")
                ) {
                    throw GradleException("Test probe or fixture leaked into $label!/${entry.name}")
                }
                if (name.endsWith(".jar") || name.endsWith(".zip")) {
                    scanArchive(entryBytes, "$label!/${entry.name}")
                }
            }
        }
    }

    private fun ByteArray.containsAscii(text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

version = providers.gradleProperty("pluginVersion").get()

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        languageVersion = KotlinVersion.KOTLIN_2_1
        apiVersion = KotlinVersion.KOTLIN_2_1
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "252"
            untilBuild = "261.*"
        }
    }
}

val compileProbeAdapter by tasks.registering(JavaCompile::class) {
    source = fileTree("src/test/fixtures/java8/adapter") { include("**/*.java") }
    classpath = files()
    destinationDirectory = layout.buildDirectory.dir("probe/adapter-classes")
    options.release = 8
}

val compileProbeApplication by tasks.registering(JavaCompile::class) {
    source = fileTree("src/test/fixtures/java8/application") { include("**/*.java") }
    classpath = files()
    destinationDirectory = layout.buildDirectory.dir("probe/application-classes")
    options.release = 8
}

val probeAdapterJar by tasks.registering(Jar::class) {
    dependsOn(compileProbeAdapter)
    archiveFileName = "odb-test-probe.jar"
    destinationDirectory = layout.buildDirectory.dir("probe")
    from(compileProbeAdapter.flatMap { it.destinationDirectory })
}

val probeApplicationJar by tasks.registering(Jar::class) {
    dependsOn(compileProbeApplication)
    archiveFileName = "odb-test-application.jar"
    destinationDirectory = layout.buildDirectory.dir("probe")
    from(compileProbeApplication.flatMap { it.destinationDirectory })
}

val probeRuntimeManifest = layout.buildDirectory.file("probe/runtime.json")
val writeProbeRuntimeManifest by tasks.registering(WriteRuntimeManifest::class) {
    dependsOn(probeAdapterJar)
    runtimeJar = probeAdapterJar.flatMap { it.archiveFile }
    outputManifest = probeRuntimeManifest
}

val localOdbCheckout = layout.projectDirectory.dir("LewisOmniscientDebugger")
val localOdbRuntimeJar = localOdbCheckout.file("build/libs/LewisOmniscientDebugger.jar")
val buildLocalOdbRuntime by tasks.registering(Exec::class) {
    workingDir(localOdbCheckout)
    commandLine(
        "./gradlew",
        "--no-daemon",
        "--offline",
        "clean",
        "jar",
        "verifyRuntimeDependencies",
        "verifyRuntimeArtifact",
    )
}

val localOdbRuntimeManifest = layout.buildDirectory.file("local-odb/runtime.json")
val writeLocalOdbRuntimeManifest by tasks.registering(WriteRuntimeManifest::class) {
    dependsOn(buildLocalOdbRuntime)
    runtimeJar = localOdbRuntimeJar
    outputManifest = localOdbRuntimeManifest
}

val testJdk8 = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(8)
}

tasks.test {
    dependsOn(writeProbeRuntimeManifest, probeApplicationJar)
    systemProperty("org.lewisodb.intellij.runtime", probeAdapterJar.flatMap { it.archiveFile }.get().asFile)
    systemProperty("org.lewisodb.intellij.runtimeManifest", probeRuntimeManifest.get().asFile)
    systemProperty("org.lewisodb.intellij.testApplication", probeApplicationJar.flatMap { it.archiveFile }.get().asFile)
    systemProperty("org.lewisodb.intellij.testJdk8", testJdk8.get().metadata.installationPath.asFile)
}

tasks.runIde {
    dependsOn(writeLocalOdbRuntimeManifest)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Dorg.lewisodb.intellij.runtime=${localOdbRuntimeJar.asFile.absolutePath}",
            "-Dorg.lewisodb.intellij.runtimeManifest=${localOdbRuntimeManifest.get().asFile.absolutePath}",
        )
    }
    argumentProviders += CommandLineArgumentProvider {
        listOf(layout.projectDirectory.dir("src/test/fixtures/ide-project").asFile.absolutePath)
    }
}

val verifyProbeIsolation by tasks.registering(VerifyProbeIsolation::class) {
    dependsOn(tasks.named("buildPlugin"))
    pluginZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
}

tasks.check {
    dependsOn(verifyProbeIsolation)
}
