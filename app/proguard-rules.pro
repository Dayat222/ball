-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Stealth hardening: strip SourceFile and LineNumberTable attributes and rename
# source files to a empty/generic tag so stack traces never leak Kotlin filenames.
-renamesourcefileattribute SourceFile

# View binding classes are resolved from the layout by name.
-keep class com.utility.toolkit.databinding.** { *; }

# Activities, services and receivers are instantiated by the framework by name.
-keep class com.utility.toolkit.MainActivity { *; }
-keep class com.utility.toolkit.OverlayService { *; }

# Custom views are inflated programmatically or from XML.
-keep class com.utility.toolkit.CanvasOverlayView { *; }
-keep class com.utility.toolkit.HandleView { *; }

-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# Reflection-free codebase: aggressively repackage and obfuscate all internal classes.
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
