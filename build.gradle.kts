import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.avh.logconsole"
// Every commit gets its own version, <major.minor>.<number of commits>, so each push to main is a new release
// without editing this file. Bump the major/minor by hand. CI needs the full history (fetch-depth: 0), or the
// count would be 1; without git (e.g. a source zip) the patch is 0.
val commitCount = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("")
version = "1.0." + commitCount.ifEmpty { "0" }

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.5")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The IntelliJ Platform test setup references JUnit 4 classes even when the tests use JUnit 5.
    // Panel tests use the platform's BasePlatformTestCase (JUnit 3/4 style), run through the vintage engine.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
}

tasks.withType<JavaCompile> {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}

// `./gradlew runIde` opens the samples folder, so demo.sh is right there to try.
tasks.runIde {
    args(layout.projectDirectory.dir("samples").asFile.absolutePath)
}
