import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import groovy.json.JsonSlurper
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
        val sourceCommit = "0".repeat(40)
        val sourceDigest = "0".repeat(64)
        val manifest =
            """{"sourceCommit":"$sourceCommit","artifact":"odb-runtime.jar","sha256":"$digest","sourceArtifact":"odb-source-$sourceCommit.tar.gz","sourceSha256":"$sourceDigest","javaClassVersion":52,"integrationProtocol":1,"adapterClass":"com.lambda.Debugger.IntegrationLauncher","dependencies":["commons-io:commons-io:2.21.0","org.apache.bcel:bcel:6.12.0","org.apache.commons:commons-lang3:3.20.0","org.ow2.asm:asm:9.7.1"]}"""
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

abstract class VerifyBundledOdb : DefaultTask() {
    @get:InputFile
    abstract val pluginZip: RegularFileProperty

    @get:InputFile
    abstract val sourceArchive: RegularFileProperty

    @get:InputFile
    abstract val releaseChecksums: RegularFileProperty

    @get:Input
    abstract val maximumPluginBytes: Property<Long>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val zipFile = pluginZip.get().asFile
        if (zipFile.length() > maximumPluginBytes.get()) {
            throw GradleException(
                "Plugin ZIP is ${zipFile.length()} bytes; approved V1 budget is ${maximumPluginBytes.get()} bytes.",
            )
        }

        val outerEntries = archiveEntries(zipFile.readBytes(), zipFile.name)
        val pluginJars = outerEntries.filterKeys { it.matches(Regex("[^/]+/lib/[^/]+\\.jar")) }
        if (pluginJars.size != 1) {
            throw GradleException("Expected one plugin JAR in ${zipFile.name}, found ${pluginJars.keys}.")
        }
        val misplacedLibraries = outerEntries.keys.filter { name ->
            name.endsWith(".jar", ignoreCase = true) && name !in pluginJars
        }
        if (misplacedLibraries.isNotEmpty()) {
            throw GradleException("Unexpected plugin library JARs: $misplacedLibraries")
        }

        val pluginJar = pluginJars.entries.single()
        val pluginEntries = archiveEntries(pluginJar.value, "${zipFile.name}!/${pluginJar.key}")
        val nestedJars = pluginEntries.keys.filter { it.endsWith(".jar", ignoreCase = true) }
        if (nestedJars != listOf("odb/odb-runtime.jar")) {
            throw GradleException("Expected only odb/odb-runtime.jar inside the plugin JAR, found $nestedJars.")
        }
        val runtimeBytes = pluginEntries.getValue("odb/odb-runtime.jar")
        val manifestBytes = pluginEntries["odb/runtime.json"]
            ?: throw GradleException("Plugin JAR lacks odb/runtime.json.")
        val manifest = parseObject(manifestBytes, "odb/runtime.json")
        val expectedFields = setOf(
            "sourceCommit",
            "artifact",
            "sha256",
            "sourceArtifact",
            "sourceSha256",
            "javaClassVersion",
            "integrationProtocol",
            "adapterClass",
            "dependencies",
        )
        if (manifest.keys != expectedFields) {
            throw GradleException("Runtime manifest fields differ from the approved schema: ${manifest.keys}.")
        }

        val sourceCommit = manifest.requiredString("sourceCommit")
        val sourceName = manifest.requiredString("sourceArtifact")
        val runtimeSha = sha256(runtimeBytes)
        val sourceFile = sourceArchive.get().asFile
        val sourceSha = sha256(sourceFile.readBytes())
        val dependencies = manifest.requiredStrings("dependencies")
        val approvedDependencies = listOf(
            "commons-io:commons-io:2.21.0",
            "org.apache.bcel:bcel:6.12.0",
            "org.apache.commons:commons-lang3:3.20.0",
            "org.ow2.asm:asm:9.7.1",
        )
        if (
            !sourceCommit.matches(Regex("[0-9a-f]{40}")) ||
            manifest.requiredString("artifact") != "odb-runtime.jar" ||
            manifest.requiredString("sha256") != runtimeSha ||
            sourceName != "odb-source-$sourceCommit.tar.gz" ||
            sourceFile.name != sourceName ||
            manifest.requiredString("sourceSha256") != sourceSha ||
            manifest.requiredInt("javaClassVersion") != 52 ||
            manifest.requiredInt("integrationProtocol") != 1 ||
            manifest.requiredString("adapterClass") != "com.lambda.Debugger.IntegrationLauncher" ||
            dependencies != approvedDependencies
        ) {
            throw GradleException("Bundled ODB runtime manifest does not match the approved release inputs.")
        }

        val checksums = parseChecksums(releaseChecksums.get().asFile.readText())
        if (
            checksums["LewisOmniscientDebugger.jar"] != runtimeSha ||
            checksums[sourceName] != sourceSha
        ) {
            throw GradleException("Bundled runtime or source digest differs from release-inputs/odb/SHA256SUMS.")
        }

        val legalFiles = mapOf(
            "legal/COLLECTION-PROVENANCE.md" to "COLLECTION-PROVENANCE.md",
            "legal/COPYING" to "COPYING",
            "legal/odb-runtime.cdx.json" to "odb-runtime.cdx.json",
            "legal/odb-runtime-osv.json" to "odb-runtime-osv.json",
            "legal/third-party/asm/LICENSE.txt" to "third-party/asm/LICENSE.txt",
            "legal/third-party/bcel/LICENSE.txt" to "third-party/bcel/LICENSE.txt",
            "legal/third-party/bcel/NOTICE.txt" to "third-party/bcel/NOTICE.txt",
            "legal/third-party/commons-io/LICENSE.txt" to "third-party/commons-io/LICENSE.txt",
            "legal/third-party/commons-io/NOTICE.txt" to "third-party/commons-io/NOTICE.txt",
            "legal/third-party/commons-lang3/LICENSE.txt" to "third-party/commons-lang3/LICENSE.txt",
            "legal/third-party/commons-lang3/NOTICE.txt" to "third-party/commons-lang3/NOTICE.txt",
        )
        legalFiles.forEach { (resourceName, releaseName) ->
            val bytes = pluginEntries[resourceName]
                ?: throw GradleException("Plugin JAR lacks $resourceName.")
            if (sha256(bytes) != checksums[releaseName]) {
                throw GradleException("$resourceName differs from the approved release input.")
            }
        }

        val runtimeEntries = archiveEntries(runtimeBytes, "odb/odb-runtime.jar")
        val requiredRuntimeEntries = setOf(
            "com/lambda/Debugger/Debugger.class",
            "com/lambda/Debugger/IntegrationLauncher.class",
            "com/lambda/Debugger/IntegrationState.class",
            "META-INF/odb-runtime.cdx.json",
        )
        val missingRuntimeEntries = requiredRuntimeEntries - runtimeEntries.keys
        if (missingRuntimeEntries.isNotEmpty()) {
            throw GradleException("ODB runtime lacks required entries: $missingRuntimeEntries")
        }
        val forbiddenRuntimeEntries = runtimeEntries.keys.filter { name ->
            name.startsWith("com/lambda/tests/") ||
                name.startsWith("edu/insa/LSD/Test") ||
                name.endsWith(".so", ignoreCase = true) ||
                name.endsWith(".dll", ignoreCase = true) ||
                name.endsWith(".dylib", ignoreCase = true)
        }
        if (forbiddenRuntimeEntries.isNotEmpty()) {
            throw GradleException("ODB runtime contains forbidden test or native content: $forbiddenRuntimeEntries")
        }
        runtimeEntries.filterKeys { it.endsWith(".class") }.forEach { (name, bytes) ->
            if (bytes.size < 8 || bytes.readInt(0) != 0xcafebabe.toInt()) {
                throw GradleException("ODB runtime entry is not a class file: $name")
            }
            val majorVersion = (bytes[6].toInt() and 0xff) shl 8 or (bytes[7].toInt() and 0xff)
            if (majorVersion > 52) {
                throw GradleException("ODB runtime entry $name uses class-file version $majorVersion.")
            }
        }

        val sbomBytes = pluginEntries.getValue("legal/odb-runtime.cdx.json")
        if (!runtimeEntries.getValue("META-INF/odb-runtime.cdx.json").contentEquals(sbomBytes)) {
            throw GradleException("Runtime and plugin legal SBOM copies differ.")
        }
        val sbom = parseObject(sbomBytes, "legal/odb-runtime.cdx.json")
        if (sbom.requiredString("bomFormat") != "CycloneDX" || sbom.requiredString("specVersion") != "1.5") {
            throw GradleException("Bundled runtime SBOM is not approved CycloneDX 1.5.")
        }
        val sbomCoordinates = (sbom["components"] as? List<*>)?.map { component ->
            val objectValue = component as? Map<*, *>
                ?: throw GradleException("Invalid component in bundled runtime SBOM.")
            val properties = objectValue["properties"] as? List<*>
                ?: throw GradleException("Bundled runtime SBOM component lacks properties.")
            val coordinate = properties.map { it as? Map<*, *> }.singleOrNull {
                it?.get("name") == "lewisodb:coordinate"
            } ?: throw GradleException("Bundled runtime SBOM component lacks its coordinate.")
            coordinate.requiredString("value")
        }?.sorted() ?: throw GradleException("Bundled runtime SBOM lacks components.")
        if (sbomCoordinates != approvedDependencies) {
            throw GradleException("Bundled runtime SBOM differs from the approved dependencies: $sbomCoordinates")
        }

        val vulnerabilityEvidence = parseObject(
            pluginEntries.getValue("legal/odb-runtime-osv.json"),
            "legal/odb-runtime-osv.json",
        )
        val vulnerabilityPackages = vulnerabilityEvidence["packages"] as? List<*>
            ?: throw GradleException("Bundled OSV evidence lacks packages.")
        val evidencePurls = vulnerabilityPackages.map { packageValue ->
            val objectValue = packageValue as? Map<*, *>
                ?: throw GradleException("Invalid package in bundled OSV evidence.")
            val vulnerabilities = objectValue["vulnerabilities"] as? List<*>
                ?: throw GradleException("Bundled OSV evidence lacks vulnerability results.")
            if (vulnerabilities.isNotEmpty()) {
                throw GradleException("Bundled OSV evidence reports vulnerabilities: $vulnerabilities")
            }
            objectValue.requiredString("purl")
        }.sorted()
        val expectedPurls = listOf(
            "pkg:maven/commons-io/commons-io@2.21.0",
            "pkg:maven/org.apache.bcel/bcel@6.12.0",
            "pkg:maven/org.apache.commons/commons-lang3@3.20.0",
            "pkg:maven/org.ow2.asm/asm@9.7.1",
        )
        if (evidencePurls != expectedPurls) {
            throw GradleException("Bundled OSV evidence differs from the approved dependency graph.")
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            """
            pluginZip=${zipFile.name}
            pluginSha256=${sha256(zipFile.readBytes())}
            pluginBytes=${zipFile.length()}
            sourceCommit=$sourceCommit
            runtimeSha256=$runtimeSha
            runtimeBytes=${runtimeBytes.size}
            sourceArtifact=$sourceName
            sourceSha256=$sourceSha
            dependencies=${dependencies.joinToString(",")}
            """.trimIndent() + "\n",
        )
    }

    private fun archiveEntries(bytes: ByteArray, label: String): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("/") || "\\" in name || name.split('/').any { it == ".." }) {
                    throw GradleException("Unsafe archive entry in $label: $name")
                }
                if (entries.put(name, archive.readAllBytes()) != null) {
                    throw GradleException("Duplicate archive entry in $label: $name")
                }
            }
        }
        return entries
    }

    private fun parseObject(bytes: ByteArray, label: String): Map<*, *> =
        JsonSlurper().parseText(bytes.toString(Charsets.UTF_8)) as? Map<*, *>
            ?: throw GradleException("$label is not a JSON object.")

    private fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
        .filter { it.isNotBlank() }
        .associate { line ->
            val match = Regex("([0-9a-f]{64})  (.+)").matchEntire(line)
                ?: throw GradleException("Invalid release checksum line: $line")
            match.groupValues[2] to match.groupValues[1]
        }

    private fun Map<*, *>.requiredString(name: String): String =
        this[name] as? String ?: throw GradleException("Expected string field $name.")

    private fun Map<*, *>.requiredInt(name: String): Int =
        (this[name] as? Number)?.toInt() ?: throw GradleException("Expected integer field $name.")

    private fun Map<*, *>.requiredStrings(name: String): List<String> =
        (this[name] as? List<*>)?.map {
            it as? String ?: throw GradleException("Expected string values in $name.")
        } ?: throw GradleException("Expected array field $name.")

    private fun ByteArray.readInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) shl 24 or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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

val verifyBundledOdb by tasks.registering(VerifyBundledOdb::class) {
    dependsOn(tasks.named("buildPlugin"))
    pluginZip = tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }
    sourceArchive = layout.projectDirectory.file(
        "release-inputs/odb/odb-source-40892aaef11f2585fb5a35755656662d8cbc8753.tar.gz",
    )
    releaseChecksums = layout.projectDirectory.file("release-inputs/odb/SHA256SUMS")
    maximumPluginBytes = 5L * 1024L * 1024L
    reportFile = layout.buildDirectory.file("reports/bundledOdb/verification.txt")
}

tasks.check {
    dependsOn(verifyProbeIsolation, verifyBundledOdb)
}
