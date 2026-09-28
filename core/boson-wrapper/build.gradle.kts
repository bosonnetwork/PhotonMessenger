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

}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))

    // Boson stack. Brings Vert.x 5, Netty, Jackson, sqlite-jdbc, slf4j and boson-api transitively,
    // but not boson-dht: the clients only need it for their own tests, and the phone runs no node.
    // Persistence goes through the MessagingStore seam instead - :core:database's Room-backed store
    // (Option A) - so the transitive desktop-JVM xerial sqlite-jdbc is never loaded on-device
    // (consumer-rules.pro only silences the leftover references).
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
    // slf4j 2.x binding for Android (writes to System.err, which logcat picks up). A logcat-native
    // binding (slf4j-handroid / logback-android) would give tags and levels, and stays an option.
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
