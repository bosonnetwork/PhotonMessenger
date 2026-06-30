# Consumer ProGuard/R8 rules for the Boson stack (Vert.x / Netty / Jackson / sqlite-jdbc).
# Finalized in M6-9. Vert.x and Netty use reflection and service loaders; keep their metadata.
-keep class io.bosonnetwork.** { *; }
-keep class io.vertx.** { *; }
-dontwarn io.vertx.**
-dontwarn io.netty.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn org.sqlite.**
