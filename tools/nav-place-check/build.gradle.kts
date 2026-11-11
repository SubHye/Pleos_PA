// Test support, not part of the app: sets each place of contracts/navigation/places.json as the
// stock navigation destination on the Pleos emulator and reads it back. See README.md.
plugins { alias(libs.plugins.android.application) }

/** Puts the place list in the APK, without committing a second copy of it. */
abstract class PlacesAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val places: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        places.get().asFile.copyTo(outputDir.file("places.json").get().asFile, overwrite = true)
    }
}

val placesAsset =
    tasks.register<PlacesAsset>("placesAsset") {
        places.set(rootProject.layout.projectDirectory.file("contracts/navigation/places.json"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(placesAsset, PlacesAsset::outputDir)
    }
}

android {
    namespace = "com.example.pleos_pa.placecheck"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.example.pleos_pa.placecheck"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.navi.helper)
    testImplementation(libs.junit)
}
