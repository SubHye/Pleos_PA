import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Experiment support, not part of the app (COM-55): a relay the car app dials out to, so an AI
// client can reach the app's vehicle read tools over HTTPS. See README.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The app's unit tests load this module, and the app compiles for Java 11.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

dependencies {
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.org.json)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.cio)
}

application { mainClass.set("com.example.pleos_pa.relayserver.MainKt") }

tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

tasks.test {
    inputs
        .dir(rootProject.layout.projectDirectory.dir("contracts"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
