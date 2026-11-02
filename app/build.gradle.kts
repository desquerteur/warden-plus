import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing: CI passes WARDEN_* environment variables (see .github/workflows/release.yml);
// locally the values come from keystore.properties in the project root (created by
// scripts/create-signing-key.ps1, gitignored). Debug builds never need them.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(env: String, key: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("WARDEN_KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }
val releaseStorePassword = signingValue("WARDEN_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("WARDEN_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("WARDEN_KEY_PASSWORD", "keyPassword")

val releaseSigningProblems: List<String> = buildList {
    when {
        releaseStoreFile == null -> add("keystore file (WARDEN_KEYSTORE_FILE / storeFile)")
        !releaseStoreFile.isFile -> add("keystore file not found at $releaseStoreFile")
    }
    if (releaseStorePassword == null) add("keystore password (WARDEN_KEYSTORE_PASSWORD / storePassword)")
    if (releaseKeyAlias == null) add("key alias (WARDEN_KEY_ALIAS / keyAlias)")
    if (releaseKeyPassword == null) add("key password (WARDEN_KEY_PASSWORD / keyPassword)")
}

android {
    namespace = "com.warden.blocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.warden.blocker"
        minSdk = 26
        targetSdk = 34
        // CI overrides both from the git tag: v1.2.3 -> versionName 1.2.3, versionCode 10203
        // (major*10000 + minor*100 + patch). The defaults follow the same scheme.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 303
        versionName = providers.gradleProperty("versionName").orNull ?: "0.3.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // GitHub repository ("owner/name") the in-app updater checks for new releases.
        val updateRepo = providers.gradleProperty("updateRepo").orNull ?: "desquerteur/warden-plus"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (releaseSigningProblems.isEmpty()) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 is off until the minified build has been tested on devices (Room, WorkManager,
            // reflection in the accessibility/VPN services). Re-enable once verified.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true // for BuildConfig.DEBUG (FLAG_SECURE only in release)
    }
    // The exported Room schemas, for MigrationTestHelper in androidTest.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

// Every release must be signed with the same key, or installed apps can't be updated. Fail
// release builds early with a clear message instead of producing an unsigned APK.
val verifyReleaseSigning by tasks.registering {
    // Plain String: the configuration cache can't restore Kotlin's buildList implementation.
    val problems = releaseSigningProblems.joinToString("; ")
    doLast {
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing: $problems.\n" +
                    "Run scripts/create-signing-key.ps1 (creates keystore.properties) or set the " +
                    "WARDEN_* environment variables. See docs/RELEASING.md. Debug builds " +
                    "(assembleDebug) don't need a key."
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }

// The device-owner guide is read inside the app (Settings › Protection › Maximum protection ›
// Step-by-step guide), not on GitHub in a browser that may well be blocked. docs/DEVICE_OWNER.md
// is copied into the APK's assets at build time, so app and docs never drift apart.
abstract class DeviceOwnerGuideAsset : DefaultTask() {
    @get:InputFile abstract val guide: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun copy() {
        val out = outputDir.get().asFile.apply { mkdirs() }
        guide.get().asFile.copyTo(File(out, "DEVICE_OWNER.md"), overwrite = true)
    }
}
val deviceOwnerGuideAsset = tasks.register<DeviceOwnerGuideAsset>("deviceOwnerGuideAsset") {
    guide.set(rootProject.layout.projectDirectory.file("docs/DEVICE_OWNER.md"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(deviceOwnerGuideAsset, DeviceOwnerGuideAsset::outputDir)
    }
}

// Export Room schemas so migrations can be reviewed and (later) tested with MigrationTestHelper.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json) // real org.json for JVM tests (android.jar only has stubs)
    androidTestImplementation(libs.androidx.room.testing) // MigrationTestHelper for the Room migration tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
