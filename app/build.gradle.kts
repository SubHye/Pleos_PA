import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Release signing values. The file is not committed; without it the release build stays unsigned,
 * so CI and other checkouts keep building.
 */
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }

/** Puts only the contracts' catalogs in the APK, without committing a second copy of them. */
abstract class CatalogAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalog: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val navigationCatalog: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val navigationPlaces: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        navigationPlaces
            .get()
            .asFile
            .copyTo(outputDir.file("navigation-places.json").get().asFile, overwrite = true)
        catalog.get().asFile.copyTo(outputDir.file("catalog.json").get().asFile, overwrite = true)
        navigationCatalog
            .get()
            .asFile
            .copyTo(outputDir.file("navigation-catalog.json").get().asFile, overwrite = true)
    }
}

val catalogAsset =
    tasks.register<CatalogAsset>("catalogAsset") {
        catalog.set(rootProject.layout.projectDirectory.file("contracts/vehicle-read/catalog.json"))
        navigationCatalog.set(
            rootProject.layout.projectDirectory.file("contracts/navigation/catalog.json")
        )
        navigationPlaces.set(
            rootProject.layout.projectDirectory.file("contracts/navigation/places.json")
        )
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(catalogAsset, CatalogAsset::outputDir)
    }
}

android {
    useLibrary("android.car", false)
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
        manifestPlaceholders["com5DiagnosticsEnabled"] = false
        manifestPlaceholders["com5AppLabel"] = "@string/app_name"
    }

    buildFeatures { resValues = true }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            val com5Diagnostic = providers.gradleProperty("com5Diagnostic").orNull == "true"
            val com9GuardDiagnostic =
                providers.gradleProperty("com9GuardDiagnostic").orNull == "true"
            require(!(com5Diagnostic && com9GuardDiagnostic)) {
                "Choose either com5Diagnostic or com9GuardDiagnostic, not both"
            }
            if (com5Diagnostic) {
                applicationIdSuffix = ".com5"
                manifestPlaceholders["com5DiagnosticsEnabled"] = true
                manifestPlaceholders["com5AppLabel"] = "@string/com22_app_name"
            } else if (com9GuardDiagnostic) {
                applicationIdSuffix = ".com9guard"
                resValue("string", "app_name", "Pleos PA 규제 검수 (.com9guard)")
            }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
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
            it.inputs
                .dir(rootProject.layout.projectDirectory.dir("contracts/navigation"))
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.vehicle.sdk)
    implementation(libs.navi.helper)
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.server.cio)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.ktor.client.cio)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.mcp.kotlin.sdk.client)
    androidTestImplementation(libs.ktor.client.cio)
}
