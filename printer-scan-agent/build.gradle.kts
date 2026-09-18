plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.multiship"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.jmdns:jmdns:3.5.9")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.multiship.scanagent.ScanAgent")
}

tasks.test { useJUnitPlatform() }

tasks.shadowJar {
    archiveBaseName.set("multiship-lan-scanner")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    mergeServiceFiles()
}
