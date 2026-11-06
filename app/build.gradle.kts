plugins {
    alias(libs.plugins.android.application)
}

/** Puts only the contract's catalog in the APK, without committing a second copy of it. */
abstract class CatalogAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        catalog.get().asFile.copyTo(outputDir.file("catalog.json").get().asFile, overwrite = true)
    }
}

val catalogAsset =
    tasks.register<CatalogAsset>("catalogAsset") {
        catalog.set(rootProject.layout.projectDirectory.file("contracts/vehicle-read/catalog.json"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(catalogAsset, CatalogAsset::outputDir)
    }
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
    implementation(libs.vehicle.sdk)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
