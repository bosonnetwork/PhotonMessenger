plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.bosonnetwork.photon.core.boson"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The Boson stack (Vert.x 5, Netty, Jackson) targets Java 11+; desugaring backfills
        // java.time / java.util.function / try-with-resources APIs on minSdk 26.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The JVM dependencies ship overlapping META-INF metadata and multi-release / module-info
    // artifacts that Android packaging and D8 cannot merge. Strip the non-runtime ones.
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
    api(project(":core:model"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))

    // Boson stack from mavenLocal. Brings Vert.x 5, Netty, Jackson, sqlite-jdbc, slf4j,
    // boson-api and boson-dht transitively. NOTE (Android runtime risk - see M0 build report):
    // xerial sqlite-jdbc is a desktop-JVM artifact; the persistence backend is replaced per D-5
    // (SQLDroid-backed Android Database) before the client can run on-device.
    //
    // Logging (D-6): exclude the desktop logback-classic binding (uses JMX / java.lang.management,
    // absent on Android) and bind slf4j to an Android-friendly provider below. slf4j is just the
    // facade; only the runtime binding changes per platform.
    api(libs.boson.messaging.client) {
        exclude(group = "ch.qos.logback")
    }
    api(libs.boson.ion.store.client) {
        exclude(group = "ch.qos.logback")
    }
    // All Director communication (client API, OAuth sign-in, device pairing).
    api(libs.boson.director.client) {
        exclude(group = "ch.qos.logback")
    }
    // slf4j 2.x binding for Android (System.err -> logcat). A logcat-native binding
    // (slf4j-handroid / logback-android) is a M6 polish upgrade.
    runtimeOnly(libs.slf4j.simple)

    // Async bridges (design spec section 4.5)
    implementation(libs.kotlinx.coroutines.jdk8)          // CompletableFuture.await() for MessagingClient
    implementation(libs.vertx.lang.kotlin.coroutines)     // Future.coAwait() for IonStore
    implementation(libs.kotlinx.coroutines.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
