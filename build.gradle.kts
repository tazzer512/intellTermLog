plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.logconsole"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.5")
    }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The IntelliJ Platform test setup references JUnit 4 classes even when the tests use JUnit 5.
    testRuntimeOnly("junit:junit:4.13.2")
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
