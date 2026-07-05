# App-level R8 rules (M6-9 release hardening). Library consumer rules (:core:boson-wrapper,
# :core:network) are merged automatically; AndroidX/Hilt/OkHttp/Retrofit/Coil/CameraX/ML Kit ship
# their own consumer rules. Keep only what those don't cover.

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Enums crossing reflective (de)serialization boundaries (Jackson / kotlinx-serialization).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Coroutines: keep the internal volatile fields used by the atomicfu/state machines.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ML Kit barcode model dependencies may reference optional classes.
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# BouncyCastle (bcprov): the Boson crypto provider (Ed25519 / crypto_box) loads algorithm
# implementations reflectively by class name; stripping or renaming them would break crypto in release
# only, so keep the whole tree. (BCJSSE is no longer bundled - ECDSA certs use Conscrypt natively.)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
