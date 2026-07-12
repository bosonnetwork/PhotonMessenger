plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "io.photonmessenger.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.photonmessenger.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing material is supplied out-of-band (CI secrets or ~/.gradle properties), never
    // committed. When absent, assembleRelease still builds an unsigned APK so the pipeline is testable.
    val releaseStoreFile = (project.findProperty("PHOTON_KEYSTORE_FILE") as String?)
        ?: System.getenv("PHOTON_KEYSTORE_FILE")

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = (project.findProperty("PHOTON_KEYSTORE_PASSWORD") as String?)
                    ?: System.getenv("PHOTON_KEYSTORE_PASSWORD")
                keyAlias = (project.findProperty("PHOTON_KEY_ALIAS") as String?)
                    ?: System.getenv("PHOTON_KEY_ALIAS")
                keyPassword = (project.findProperty("PHOTON_KEY_PASSWORD") as String?)
                    ?: System.getenv("PHOTON_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required: the app dexes the JVM Boson stack (Vert.x 5 / Netty / Jackson).
        isCoreLibraryDesugaringEnabled = true
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
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/DISCLAIMER",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/**",
                "META-INF/io.netty.versions.properties",
                "META-INF/native-image/**",
                "**/module-info.class",
            )
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:database"))
    implementation(project(":core:boson-wrapper"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:contacts"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)

    // Installs the bundled baseline profile at first run so ART can AOT-compile hot paths.
    implementation(libs.androidx.profileinstaller)
    // Producer module that generates app/src/<variant>/generated/baselineProfiles/.
    baselineProfile(project(":baselineprofile"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
