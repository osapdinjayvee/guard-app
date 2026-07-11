# R8 rules for the release build.
#
# Most of this stack ships its own consumer rules inside its AAR — Retrofit, OkHttp, Moshi, Room,
# WorkManager and Hilt all do — so what follows is only what R8 cannot know from the libraries
# alone: the places where *this app's own* names are load-bearing at runtime.
#
# The failure mode for every rule below is a silent one. R8 does not complain, the build succeeds,
# and the app misbehaves in the field. That is why the release APK is exercised against the real
# API before it is tagged, rather than merely compiled.

# A stack trace from a minified build is unreadable without these. `mapping.txt`, under
# app/build/outputs/mapping/release/, is what turns the names back — keep it with every artefact
# you ship or the first production crash report is worthless.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Retrofit reads the generic type of a suspend function's return value to choose a converter, and
# generics survive minification only as an attribute. Without Signature it sees `Object`, and
# cannot tell an Envelope<AttendanceDto> from an Envelope<DutyDto>.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# ---------------------------------------------------------------------------------------------
# Enums whose constant names are *data*.
#
# SyncStatus and AttendanceType are persisted into Room by name ("PENDING", "TIME_IN") and read
# back with valueOf, and AttendanceType.wireName derives the API's `time_in` from name.lowercase().
# Renaming a constant to `a` would corrupt every stored record and every upload — at runtime, with
# no build error and no crash, just wrong data. This is the most dangerous thing R8 could do here.
-keepclassmembers enum com.minsu.guardapp.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------------------------
# Moshi.
#
# The adapters are generated at build time by KSP, but Moshi still *finds* them reflectively: it
# appends "JsonAdapter" to the class name and calls Class.forName. Rename the DTO and the lookup
# misses; rename the adapter and it misses too. Both names have to survive, together.
-keep,allowobfuscation @interface com.squareup.moshi.JsonClass
-keep class com.minsu.guardapp.core.network.dto.** { *; }
-keep class com.minsu.guardapp.core.network.dto.**JsonAdapter { *; }

# Kotlin writes the defaults of a data class into a synthetic constructor. Moshi's generated
# adapter calls it to honour default values for absent JSON keys — every optional field in
# MobileSettingsDto depends on this.
-keepclassmembers class com.minsu.guardapp.core.network.dto.** {
    synthetic <methods>;
}

# ---------------------------------------------------------------------------------------------
# WorkManager.
#
# The worker's class name is written into WorkManager's own database when work is enqueued, and read
# back as a string to instantiate it later. That string outlives the process, so a drain enqueued
# before an app update must still resolve after it.
-keep class com.minsu.guardapp.core.sync.AttendanceSyncWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# ---------------------------------------------------------------------------------------------
# ML Kit — the QR scanner, which is to say the product.
#
# Found by running the minified build, not by reading it. ML Kit registers its components through
# Firebase's ComponentDiscovery: it reads registrar class *names* out of the merged manifest and
# instantiates each one via a no-argument constructor it finds by reflection. R8 sees a constructor
# nobody calls and removes it, and discovery then fails with
#
#     NoSuchMethodException: com.google.mlkit.vision.barcode.internal.BarcodeRegistrar.<init> []
#
# which is logged at WARN and swallowed. Nothing crashes. The barcode scanner simply never works,
# in release builds only — the one configuration nobody runs during development.
-keep class com.google.mlkit.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
-dontwarn com.google.mlkit.**

# ---------------------------------------------------------------------------------------------
# Room reaches entities from generated code, but matches @TypeConverter methods by signature. Keep
# them, so the enum converters above are not shrunk away as unused.
-keepclassmembers class * {
    @androidx.room.TypeConverter <methods>;
}

-keep class kotlin.Metadata { *; }
-dontwarn org.jetbrains.annotations.**
