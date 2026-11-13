plugins {
    `java-library`
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

description = "ObjCraft plugin entry point"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":objcraft-api"))
    implementation(project(":objcraft-geometry"))
    implementation(project(":objcraft-importer"))
    implementation(project(":objcraft-platform"))
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.jar {
    archiveFileName.set("objcraft-plugin-plain.jar")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveFileName.set("objcraft-plugin.jar")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
