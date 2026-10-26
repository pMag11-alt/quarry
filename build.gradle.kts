import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "dev.quarry"
version = "0.1.0"

repositories {
    mavenCentral()
}

// A separate source set for the Lucene comparison harness, so the engine
// itself never depends on Lucene.
val compare: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val lucene = "10.5.2"

dependencies {
    // The engine itself uses only the Kotlin and Java standard libraries.
    // Lucene is here purely as the reference implementation that the tests
    // and the comparison harness check quarry against.
    testImplementation(kotlin("test"))
    testImplementation("org.apache.lucene:lucene-core:$lucene")
    testImplementation("org.apache.lucene:lucene-analysis-common:$lucene")
    "compareImplementation"("org.apache.lucene:lucene-core:$lucene")
    "compareImplementation"("org.apache.lucene:lucene-analysis-common:$lucene")
}

configurations["compareImplementation"].extendsFrom(configurations.implementation.get())

// ./gradlew compareLucene -Pdatasets=data/scifact,data/fiqa [-Pk=10]
tasks.register<JavaExec>("compareLucene") {
    description = "Index BEIR datasets with quarry and with Lucene, and compare rankings, scores and speed."
    classpath = compare.runtimeClasspath
    mainClass = "quarry.compare.LuceneCompareKt"
    maxHeapSize = "6g"
    // Give Lucene its SIMD vectorization, so the speed comparison is fair.
    jvmArgs("--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED")
    args = (project.findProperty("datasets") as String? ?: "data/scifact").split(",")
    (project.findProperty("k") as String?)?.let { systemProperty("quarry.k", it) }
}

// Build with whatever JDK runs Gradle (21 or newer); emit Java 21 bytecode
// against the Java 21 API.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjdk-release=21")
        allWarningsAsErrors = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass = "quarry.MainKt"
    applicationName = "quarry"
    applicationDefaultJvmArgs = listOf("-Xss16m", "-XX:+UseParallelGC")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
