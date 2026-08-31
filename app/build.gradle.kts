import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing is configured only when keystore.properties exists, so the
 * project still builds for anyone without the key. Create it yourself - see the
 * README. It is gitignored and must never be committed.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.stickervault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stickervault"
        // minSdk 30 (Android 11) gives us Bitmap.CompressFormat.WEBP_LOSSY and
        // matches the scoped-storage model we rely on.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en")
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // Falls back to the debug key so the release build is still
                // installable for sideloading and GitHub releases. This is NOT
                // publishable: the debug key is a public, well-known constant,
                // so it proves nothing about who built the APK, and Play will
                // reject it. Create keystore.properties before publishing.
                logger.lifecycle(
                    "StickerVault: no keystore.properties - signing release with " +
                        "the debug key. Sideload only.",
                )
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // Keeps a debug build installable alongside a release one.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    lint {
        // A shipped build should not carry known-bad patterns.
        warningsAsErrors = false
        abortOnError = true
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
}
