# kotlinx.serialization — keep generated serializers for @Serializable models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.drokpo.android.**$$serializer { *; }
-keepclassmembers class app.drokpo.android.** { *** Companion; }
-keepclasseswithmembers class app.drokpo.android.** { kotlinx.serialization.KSerializer serializer(...); }

# Firestore maps documents onto these via reflection when using toObject().
-keepclassmembers class app.drokpo.android.core.** { <init>(); <fields>; }
