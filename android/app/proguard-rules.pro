# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aurum.edge.** {
    *** Companion;
}
-keepclasseswithmembers class com.aurum.edge.** {
    kotlinx.serialization.KSerializer serializer(...);
}
