import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/*
 * Release signing. The credentials live in keystore.properties at the project
 * root, which is git-ignored and never committed:
 *
 *   storeFile=/absolute/or/project-relative/path/to/release.jks
 *   storePassword=...
 *   keyAlias=...
 *   keyPassword=...
 *
 * Without that file the release build is signed with the local debug key, so
 * it still installs for on-device testing (and over an installed debug build,
 * keeping its data) - but it is not a build to publish.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.leonidaskiachidis.shiftvault"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.leonidaskiachidis.shiftvault"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "ShiftVault: no keystore.properties - the release build is signed with " +
                        "the debug key. Fine for testing on your own device; not for publishing."
                )
                signingConfigs.getByName("debug")
            }
            // R8: strips unused code and resources (most of Compose's size in
            // a debug build) and obfuscates. Room, kotlinx.serialization and
            // the other libraries ship their own keep rules; the app has no
            // reflection of its own, so proguard-rules.pro needs nothing extra.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // Room writes each schema version here. Committed to git, these files are
    // what migration tests check a new version against.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    testOptions {
        // ExportHelper reaches android.graphics through ColorUtils when it
        // normalises a profile colour. The unit test is checking the workbook
        // XlsxWriter produces, not the colour maths, so stubbed defaults are fine.
        unitTests.isReturnDefaultValues = true
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    // Google Play in-app review: the rating card shown inside the app.
    implementation(libs.play.review)
    testImplementation(libs.junit)
    // Test-only: the unit tests open every workbook XlsxWriter produces with
    // POI, so a malformed file fails the build. Not shipped in the APK.
    testImplementation(libs.apache.poi)
    testImplementation(libs.apache.poi.ooxml)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

room {
    schemaDirectory("$projectDir/schemas")
}
