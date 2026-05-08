# Add project specific ProGuard rules here.
-keepattributes Signature, *Annotation*

-keep class com.xs.reader.data.db.** { *; }
-keep class com.xs.reader.tts.** { *; }

-dontwarn org.slf4j.**
-dontwarn javax.xml.**
-dontwarn io.documentnode.epub4j.**
