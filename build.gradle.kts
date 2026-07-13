import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider

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

val testJdk8 = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(8)
}

tasks.test {
    dependsOn(probeAdapterJar, probeApplicationJar)
    systemProperty("org.lewisodb.intellij.testProbe", probeAdapterJar.flatMap { it.archiveFile }.get().asFile)
    systemProperty("org.lewisodb.intellij.testApplication", probeApplicationJar.flatMap { it.archiveFile }.get().asFile)
    systemProperty("org.lewisodb.intellij.testJdk8", testJdk8.get().metadata.installationPath.asFile)
}

tasks.runIde {
    dependsOn(probeAdapterJar)
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dorg.lewisodb.intellij.testProbe=${probeAdapterJar.get().archiveFile.get().asFile.absolutePath}")
    }
    argumentProviders += CommandLineArgumentProvider {
        listOf(layout.projectDirectory.dir("src/test/fixtures/ide-project").asFile.absolutePath)
    }
}
