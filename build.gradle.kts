// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Puts KGP 2.4.21 on the build classpath, so AGP's built-in Kotlin compiles with it.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("app/src/**/*.kt", "tools/*/src/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "tools/*/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}
