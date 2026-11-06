// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // COM-13 experiment: puts KGP 2.4.21 on the classpath for AGP's built-in Kotlin.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}
