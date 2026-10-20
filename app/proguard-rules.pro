# DriverCall ProGuard rules (Phase 1).
# Minification is currently disabled; these rules apply when it is enabled later.

# Retrofit / Gson
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class com.drivercall.data.remote.dto.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
# WebRTC JNI entry points and Gson signaling payloads.
-keep class org.webrtc.** { *; }
-keep class com.drivercall.data.signaling.** { *; }
-dontwarn org.webrtc.**
