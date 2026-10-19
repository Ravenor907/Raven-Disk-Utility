import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The disk engine: USB mass storage, partition tables, filesystems, planning and execution.
// Plain Kotlin with no Android dependencies, so all of it runs in fast JVM unit tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
    // CI hands real-tool fixture and export directories to RealToolsTest through these.
    listOf("RAVEN_FIXTURES", "RAVEN_EXPORT", "RAVEN_MIN_FIXTURES").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
}
