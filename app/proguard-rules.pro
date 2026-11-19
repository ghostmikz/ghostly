# kotlinx.serialization (Last.fm responses)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
-keep,includedescriptorclasses class com.ghostmikz.tune.**$$serializer { *; }
-keepclassmembers class com.ghostmikz.tune.** { *** Companion; }
-dontwarn org.slf4j.**
