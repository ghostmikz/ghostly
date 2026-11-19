# kotlinx.serialization (Last.fm responses)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
-keep,includedescriptorclasses class com.ghostmikz.ghostly.**$$serializer { *; }
-keepclassmembers class com.ghostmikz.ghostly.** { *** Companion; }
-dontwarn org.slf4j.**
