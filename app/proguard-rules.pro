# Proguard rules for Screen Angle Ruler
# Standard Android optimizations
-dontobfuscate
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}
