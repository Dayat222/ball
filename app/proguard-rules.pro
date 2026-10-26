-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# View binding classes are resolved from the layout by name.
-keep class com.utility.toolkit.databinding.** { *; }

# Activities, services and receivers are instantiated by the framework by name.
-keep class com.utility.toolkit.MainActivity { *; }
-keep class com.utility.toolkit.OverlayService { *; }

# Custom views are inflated from XML by name.
-keep class com.utility.toolkit.CanvasOverlayView { *; }
-keep class com.utility.toolkit.HandleView { *; }

-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

# Reflection-free codebase: renaming the rest is safe and shrinks the APK.
-repackageclasses ''
-allowaccessmodification
