# Consumer R8 rules for :core:network (M6-9 release hardening).
# Wire layer = Retrofit + OkHttp + kotlinx-serialization. OkHttp/Retrofit ship their own consumer
# rules in their artifacts; these cover kotlinx-serialization (whose generated serializers and the
# annotated DTO fields are reached only reflectively) and the Director DTOs.

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# --- kotlinx.serialization (canonical rules) ---
# Keep the Companion of every @Serializable class so `.serializer()` resolves.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
# Keep serializer() on those companions.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep INSTANCE + serializer() of @Serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the generated $serializer classes and the DTO members they read/write by name.
-keep,includedescriptorclasses class io.photonmessenger.core.network.model.**$$serializer { *; }
-keepclassmembers class io.photonmessenger.core.network.model.** { *; }

-dontwarn kotlinx.serialization.**
