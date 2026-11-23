# Gravity Well release (R8) rules.

-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers,allowobfuscation class **$$serializer {
    *;
}
-keep class us.snakepit.gravitywell.net.** { *; }
