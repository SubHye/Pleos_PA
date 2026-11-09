// Test support, not part of the app: drives the app's MCP server over an adb forward and reports
// what each tool and property answered. See README.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(libs.mcp.kotlin.sdk.client)
    implementation(libs.ktor.client.cio)
    testImplementation(libs.junit)
}

application { mainClass.set("com.example.pleos_pa.sweep.MainKt") }

tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
