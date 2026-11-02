plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.pleos_pa"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.pleos_pa"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.all {
            it.inputs
                .dir(rootProject.layout.projectDirectory.dir("contracts/vehicle-read"))
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.vehicle.sdk)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
